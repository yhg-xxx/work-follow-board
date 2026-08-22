package com.example.mcp;

import com.example.task.dto.TaskDtos;
import com.example.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 工作跟进看板的只读 MCP 工具：单一 query_tasks（按 action 分发）。
 * 结果以 JSON 文本返回，便于 LLM 解析。
 */
@Component
public class TaskReadMcpTools {

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public TaskReadMcpTools(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    /** 把全部只读工具注册到给定的 MCP server。 */
    public void registerAll(McpSyncServer server) {
        server.addTool(queryTasks());
    }

    // ---------------- 工具定义 ----------------

    private McpServerFeatures.SyncToolSpecification queryTasks() {
        // 单一读工具：action 分发 list / detail / owners / stats。
        // 完整 schema 声明全部可选参数（供 LLM 理解结构）；服务端已关闭 SDK 入参校验（validateToolInputs(false)），
        // 参数是否合法由 handler 兜底校验并返回中文错误。
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", str("要执行的操作（可选，默认 list）。list=按条件列出事项（紧凑摘要）；detail=按数字主键查单个事项的【全部字段+跟进记录】；owners=负责人候选列表；stats=统计条带（total 总数/urgent 亟待解决/ongoing 进行中/high 高优先级/near 7日内到期）。"));
        props.put("id", str("事项的数字主键 ID（仅 detail 需要；以【字符串】形式传入，例如 '67'；不是 taskCode 如 QF-E01）。"));
        props.put("keyword", str("关键词检索（可选）：空白拆词 AND，跨 标题/事项ID(taskCode)/模块/描述/负责人/协作方/痛点/下一步/风险 匹配；例如 'QF-E01'。"));
        props.put("boards", strArr("看板分组过滤（可选）：quanfa(全发) / happy(会幸福)。"));
        props.put("statuses", strArr("状态过滤（可选）：未启动/进行中/亟待解决/持续跟进/已完成。"));
        props.put("owners", strArr("负责人过滤（可选），支持模糊：传部分名字（如 '张'）即按 LIKE %张% 匹配；多值取 OR；传完整名字等同精确。"));
        props.put("deadlineFrom", str("截止日期下界(含)，YYYY-MM-DD（可选）。"));
        props.put("deadlineTo", str("截止日期上界(含)，YYYY-MM-DD（可选）。"));
        props.put("sortMode", str("排序（可选）：asc=截止日期正序(默认，先到先处理) / desc=截止日期倒序 / priority=优先级高→低 / updateDate=更新时间新→旧。"));
        props.put("cursor", str("分页游标（可选）：上一页返回的 nextCursor，不传=首页。"));
        props.put("limit", str("每页条数（可选，字符串数字，默认 50）。"));
        props.put("q", str("负责人模糊匹配关键字（仅 owners 用，可选）：如 '数智' 只返回含该字的负责人；不传返回全部。"));
        return tool("query_tasks",
                "工作跟进看板的【唯一读工具】，按 action 分发四种查询："
                        + "list=按条件列出事项（紧凑摘要：id/taskCode/board/module/title/status/priority/owner/collab/deadline/risk/nextStep/logCount/updateDate，不含描述/痛点/子项），"
                        + "支持 boards/statuses/owners/keyword/deadlineFrom/deadlineTo 过滤与 sortMode/cursor/limit 排序分页；"
                        + "detail=按数字主键 id 查单个事项的【全部字段】与【跟进记录时间轴】；"
                        + "owners=去重负责人候选列表；stats=统计条带。"
                        + "要查某事项完整信息：先用 list 拿到数字 id，再 detail。",
                objSchema(props, List.of()),
                (ex, req) -> {
                    Map<String, Object> a = args(req);
                    String action = asText(a, "action");
                    if (action == null || action.isBlank()) {
                        action = "list";
                    }
                    return switch (action) {
                        case "detail" -> {
                            Long id = asLong(a);
                            if (id == null) {
                                yield err("缺少或非法参数 id（必须是数字主键，字符串形式，如 '67'；不是 taskCode）");
                            }
                            yield json(taskService.detail(id));
                        }
                        case "owners" -> json(taskService.owners(asText(a, "q")));
                        case "stats" -> json(taskService.stats(
                                asStringList(a, "boards"),
                                asStringList(a, "statuses"),
                                null,
                                asStringList(a, "owners"),
                                true,
                                asText(a, "keyword"),
                                asText(a, "deadlineFrom"),
                                asText(a, "deadlineTo")));
                        default -> json(compactPage(taskService.list(
                                asStringList(a, "boards"),
                                asStringList(a, "statuses"),
                                null,
                                asStringList(a, "owners"),
                                true,
                                asText(a, "keyword"),
                                asText(a, "deadlineFrom"),
                                asText(a, "deadlineTo"),
                                asText(a, "sortMode"),
                                asText(a, "cursor"),
                                asInt(a),
                                false)));
                    };
                });
    }

    // ---------------- 紧凑投影（列表瘦身） ----------------

    /** 列表分页响应瘦身：只保留卡片关键字段，丢弃描述/痛点/子项等大字段。 */
    private Map<String, Object> compactPage(TaskDtos.TaskPage page) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", page.items().stream().map(this::compact).toList());
        out.put("hasMore", page.hasMore());
        out.put("nextCursor", page.nextCursor());
        return out;
    }

    private Map<String, Object> compact(TaskDtos.TaskListItem t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("taskCode", t.taskCode());
        m.put("board", t.board());
        m.put("module", t.module());
        m.put("title", t.title());
        m.put("status", t.status());
        m.put("priority", t.priority());
        m.put("owner", t.owner());
        m.put("collab", t.collab());
        m.put("deadline", t.deadline() == null ? null : t.deadline().toString());
        m.put("risk", t.risk());
        m.put("nextStep", t.nextStep());
        m.put("logCount", t.logCount());
        m.put("updateDate", t.updateDate() == null ? null : t.updateDate().toString());
        return m;
    }

    // ---------------- 工具构造与结果辅助 ----------------

    private McpServerFeatures.SyncToolSpecification tool(
            String name, String description, Map<String, Object> inputSchema,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        Tool t = Tool.builder(name, inputSchema).description(description).build();
        return new McpServerFeatures.SyncToolSpecification(t, handler);
    }

    private static Map<String, Object> args(CallToolRequest req) {
        Map<String, Object> a = req.arguments();
        return a == null ? Map.of() : a;
    }

    private CallToolResult json(Object data) {
        try {
            String text = objectMapper.writeValueAsString(data);
            return CallToolResult.builder(List.of(text(text))).isError(false).build();
        } catch (Exception e) {
            return err("序列化结果失败: " + e.getMessage());
        }
    }

    private CallToolResult err(String message) {
        return CallToolResult.builder(List.of(text(message))).isError(true).build();
    }

    private static Content text(String s) {
        return TextContent.builder(s == null ? "" : s).build();
    }

    // ---------------- 参数解析 ----------------

    private static String asText(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v == null ? null : v.toString();
    }

    private static List<String> asStringList(Map<String, Object> a, String k) {
        Object v = a.get(k);
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> list) {
            return list.stream().map(e -> e == null ? null : e.toString())
                    .filter(Objects::nonNull).toList();
        }
        return List.of(v.toString());
    }

    private static Long asLong(Map<String, Object> a) {
        Object v = a.get("id");
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer asInt(Map<String, Object> a) {
        Object v = a.get("limit");
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ---------------- JSON Schema 构造 ----------------

    private static Map<String, Object> objSchema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> strArr(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }
}
