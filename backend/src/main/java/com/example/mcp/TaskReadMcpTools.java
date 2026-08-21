package com.example.mcp;

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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 工作跟进看板的只读 MCP 工具（第一阶段）。
 * 每个工具包装一个现有 Service 方法，结果以 JSON 文本返回，便于 LLM 解析。
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
        server.addTool(listTasks());
        server.addTool(searchTasks());
        server.addTool(getTask());
        server.addTool(getTaskOwners());
        server.addTool(getStats());
    }

    // ---------------- 工具定义 ----------------

    private McpServerFeatures.SyncToolSpecification listTasks() {
        // 规避 MCP Java SDK v2.0.1 ToolInputValidator 的已知缺陷：当 inputSchema 声明多个嵌套/可选属性时，
        // 若调用方未携带某些属性会抛出「message must not be null」。故 list_tasks 仅在 schema 声明 keyword，
        // 其余可选维度（boards/statuses/owners/deadline*/sortMode/cursor/limit）仅写进 description，
        // handler 仍从 arguments 自由读取并容错（asText/asStringList 对缺失/Null 返回 null）。
        Map<String, Object> props = Map.of(
                "keyword", str("可按关键词检索；也可自由附加以下【可选】维度（直接放进 arguments 即可，不传=不限）："
                        + "boards=看板代码数组如 [\"quanfa\",\"happy\"]；statuses=状态数组如 [\"亟待解决\"]；"
                        + "owners=负责人数组；deadlineFrom/deadlineTo=YYYY-MM-DD 区间；"
                        + "sortMode=asc|desc|priority|updateDate；cursor=上一页 nextCursor（不传=首页）；"
                        + "limit=每页条数(字符串数字，默认50)。关键词跨 标题/事项ID(taskCode)/模块/描述/负责人/协作方/痛点/下一步/风险 空白拆词 AND 匹配；例如 'QF-E01' 可命中。"));
        return tool("list_tasks",
                "按条件列出工作事项卡片。支持按看板分组(boards)、状态(statuses)、负责人(owners)筛选；"
                        + "可按截止日期区间(deadlineFrom/deadlineTo)与关键词(keyword)过滤；支持排序(sortMode)与分页(cursor/limit)。"
                        + "关键词跨 标题/事项ID(taskCode)/模块/描述/负责人/协作方/痛点/下一步/风险 做空白拆词 AND 匹配。"
                        + "返回卡片列表、是否还有更多(hasMore)与下一页游标(nextCursor)。"
                        + "【重要限制】本工具不支持按优先级(priority)或模块(module)筛选（服务层未提供该维度），请勿传 priorities/module 参数；"
                        + "如需高优先级事项，请用 keyword 近似（不保证准确），或先用 get_stats 看 high 数量。",
                objSchema(props, List.of()),
                (ex, req) -> {
                    Map<String, Object> a = args(req);
                    Object data = taskService.list(
                            asStringList(a, "boards"),
                            asStringList(a, "statuses"),
                            null,
                            asStringList(a, "owners"),
                            asText(a, "keyword"),
                            asText(a, "deadlineFrom"),
                            asText(a, "deadlineTo"),
                            asText(a, "sortMode"),
                            asText(a, "cursor"),
                            asInt(a),
                            false);
                    return json(data);
                });
    }

    private McpServerFeatures.SyncToolSpecification searchTasks() {
        Map<String, Object> props = Map.of(
                "keyword", str("搜索关键词：空白拆词 AND，跨 标题/事项ID(taskCode)/模块/描述/负责人/协作方/痛点/下一步/风险 字段匹配；可直接传事项ID如 'QF-E01'"),
                "boards", strArr("看板分组过滤（可选），只能传代码 quanfa(全发) / happy(会幸福)。不传=不限"));
        return tool("search_tasks",
                "对工作事项做全文搜索。关键词空白拆词 AND，跨 标题/事项ID(taskCode)/模块/描述/负责人/协作方/痛点/下一步/风险 字段匹配，"
                        + "因此直接搜事项ID（如 'QF-E01'）也能命中。可选叠加看板过滤 boards。适合用自然语言或事项ID查找事项。"
                        + "返回卡片列表、hasMore 与 nextCursor。"
                        + "【注意】返回的是卡片摘要（不含跟进记录）；要查看某事项的完整字段与跟进时间轴，请用 get_task 并传入本工具返回的 numeric id。",
                objSchema(props, List.of("keyword")),
                (ex, req) -> {
                    Map<String, Object> a = args(req);
                    Object data = taskService.list(
                            asStringList(a, "boards"),
                            null, null, null,
                            asText(a, "keyword"),
                            null, null, "asc", null, 50, false);
                    return json(data);
                });
    }

    private McpServerFeatures.SyncToolSpecification getTask() {
        // id 用 string 而非 integer：真实客户端（LLM）常把数字主键作为 JSON 字符串传入（如 "67"），
        // SDK 的 ToolInputValidator 会按 schema 拦截与类型不符的 integer；handler 端 asLong 已兼容字符串/数字。
        // 描述明确要求「传字符串形式的数字」，避免 LLM 误传纯数字被拒。
        Map<String, Object> props = Map.of("id", str("事项的数字主键 ID（list_tasks/search_tasks 结果里的 'id' 字段，请以【字符串】形式传入，例如 '67'，不是事项ID/taskCode 如 QF-E01）"));
        return tool("get_task",
                "获取单个事项的【全部字段】与【跟进记录时间轴】。"
                        + "【关键】id 是数字主键，不是事项ID(taskCode)：list/search 结果里的 'id' 是数字（如 67），才传 67；"
                        + "不要传 'QF-E01' 这类 taskCode——若只知道 taskCode，先用 search_tasks 查到 numeric id 再调用本工具。",
                objSchema(props, List.of("id")),
                (ex, req) -> {
                    Map<String, Object> a = args(req);
                    Long id = asLong(a);
                    if (id == null) {
                        return err("缺少或非法参数 id（必须是数字主键，不是 taskCode）");
                    }
                    return json(taskService.detail(id));
                });
    }

    private McpServerFeatures.SyncToolSpecification getTaskOwners() {
        Map<String, Object> props = Map.of("q", str("模糊匹配关键字（可选），如 '数智' 只返回含该字的 owner；不传则返回全部去重 owner"));
        return tool("get_task_owners",
                "获取去重后的负责人(owner)候选列表（按 q 模糊匹配；不传 q 返回全部）。"
                        + "用于确认负责人名字的正确写法，再把准确值传给 list_tasks 的 owners 过滤。",
                objSchema(props, List.of()),
                (ex, req) -> {
                    Map<String, Object> a = args(req);
                    return json(taskService.owners(asText(a, "q")));
                });
    }

    private McpServerFeatures.SyncToolSpecification getStats() {
        Map<String, Object> props = Map.of(
                "boards", strArr("看板分组过滤（可选），只能传代码 quanfa / happy。不传=不限"),
                "statuses", strArr("状态过滤（可选），枚举：未启动/进行中/亟待解决/持续跟进/已完成。不传=不限"),
                "owners", strArr("负责人过滤（可选），值须与 owner 文本一致。不传=不限"),
                "keyword", str("关键词（可选），同 list_tasks 的 keyword 匹配规则。不传=不限"),
                "deadlineFrom", str("截止日期下界(含)，YYYY-MM-DD（可选）。不传=不限"),
                "deadlineTo", str("截止日期上界(含)，YYYY-MM-DD（可选）。不传=不限"));
        return tool("get_stats",
                "返回统计条带（基于与列表相同的筛选条件缩小范围）。返回字段含义："
                        + "total=事项总数；urgent=状态为【亟待解决】的数量；ongoing=状态为【进行中】的数量；"
                        + "high=优先级为【高】的数量；near=截止日期在【今天起 7 天内(含)】的数量。"
                        + "可按 boards/statuses/owners/keyword/deadlineFrom/deadlineTo 缩小统计范围。"
                        + "【注意】本工具不支持按优先级筛选统计（无 priorities 参数）。",
                objSchema(props, List.of()),
                (ex, req) -> {
                    Map<String, Object> a = args(req);
                    Object data = taskService.stats(
                            asStringList(a, "boards"),
                            asStringList(a, "statuses"),
                            null,
                            asStringList(a, "owners"),
                            asText(a, "keyword"),
                            asText(a, "deadlineFrom"),
                            asText(a, "deadlineTo"));
                    return json(data);
                });
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

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }
}
