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
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 工作跟进看板的写 MCP 工具：单一 mutate_tasks（按 action 分发）。
 * action 必填：create / update / status / add_log / delete_log / delete。
 * 结果以 JSON 文本返回；失败时 isError=true 并附中文原因。
 */
@Component
public class TaskWriteMcpTools {

    private static final List<String> STATUSES = List.of("未启动", "进行中", "亟待解决", "持续跟进", "已完成");
    private static final List<String> PRIORITIES = List.of("高", "中", "低");

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public TaskWriteMcpTools(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    /** 把写工具注册到给定的 MCP server。 */
    public void registerAll(McpSyncServer server) {
        server.addTool(mutateTasks());
    }

    // ---------------- 工具定义 ----------------

    private McpServerFeatures.SyncToolSpecification mutateTasks() {
        // 服务端已关闭 SDK 入参校验（validateToolInputs(false)），
        // 必填项与枚举合法性由 handler 手动校验并返回中文错误。
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", enumStr("要执行的操作（必填）。create=新建事项；update=编辑事项字段（部分更新，未传字段保持不变）；status=仅改状态；add_log=添加跟进记录；delete_log=删除跟进记录；delete=删除事项。", List.of("create", "update", "status", "add_log", "delete_log", "delete")));
        props.put("id", str("事项的数字主键 ID（update/status/add_log/delete_log/delete 必填；以【字符串】形式传入，例如 '67'；不是 taskCode 如 QF-E01）。create 不需要。"));
        props.put("taskCode", str("事项ID（仅 create 可选，如 'QF-E01'；不传则为空）。update 忽略此字段。"));
        props.put("board", str("看板代码（create 必填；update 可选，保留原值）：quanfa(全发) / happy(会幸福)。"));
        props.put("module", str("工作模块（create 必填；update 可选，保留原值）。"));
        props.put("title", str("标题/事项（create 必填；update 可选，保留原值）。"));
        props.put("description", str("描述（可选）。"));
        props.put("status", str("状态（可选）：未启动/进行中/亟待解决/持续跟进/已完成。"));
        props.put("priority", str("优先级（可选）：高/中/低。"));
        props.put("owner", str("负责人（可选，可直接传新名字）。"));
        props.put("collab", str("协作方（可选）。"));
        props.put("pain", str("痛点（可选）。"));
        props.put("nextStep", str("下一步（可选；add_log 时表示该条跟进记录的下一步）。"));
        props.put("deadline", str("截止日期，YYYY-MM-DD（可选）。"));
        props.put("risk", str("风险，如 '★' 或文本（可选）。"));
        props.put("subItems", strArr("子项列表（可选；update 未传则保留原子项）。"));
        props.put("logDate", str("跟进日期，YYYY-MM-DD（add_log 可选，默认今天）。"));
        props.put("person", str("跟进人（add_log 可选）。"));
        props.put("summary", str("跟进摘要（add_log 必填）。"));
        props.put("logId", str("跟进记录的数字主键 ID（delete_log 必填，来自 detail 返回的 logs[].id）。"));
        return tool("mutate_tasks",
                "工作跟进看板的【唯一写工具】，按 action 分发六种操作："
                        + "create=新建事项（board/module/title 必填，其余可选）；"
                        + "update=编辑事项字段（【部分更新】：只覆盖本次传入的非空字段，其余保持不变，传入 id 指定事项）；"
                        + "status=仅修改事项状态（传 id + status）；"
                        + "add_log=给事项添加跟进记录（传 id + summary，可选 logDate/person/nextStep）；"
                        + "delete_log=删除某条跟进记录（传 id + logId）；"
                        + "delete=删除事项（传 id，谨慎）。"
                        + "id 均为数字主键（字符串形式），不是 taskCode。成功返回更新后的事项详情；失败返回中文错误。",
                objSchema(props, List.of("action")),
                this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, CallToolRequest req) {
        Map<String, Object> a = args(req);
        String action = asText(a, "action");
        if (action == null || action.isBlank()) {
            return err("缺少必填参数 action（create/update/status/add_log/delete_log/delete）");
        }
        try {
            return switch (action) {
                case "create" -> json(taskService.create(buildCreateRequest(a)));
                case "update" -> json(taskService.update(requireId(a), buildUpdateRequest(a)));
                case "status" -> {
                    Long id = requireId(a);
                    String status = asText(a, "status");
                    if (status == null || status.isBlank()) {
                        yield err("缺少参数 status（可选: 未启动/进行中/亟待解决/持续跟进/已完成）");
                    }
                    yield json(taskService.transition(id, status));
                }
                case "add_log" -> {
                    Long id = requireId(a);
                    String summary = asText(a, "summary");
                    if (summary == null || summary.isBlank()) {
                        yield err("缺少参数 summary（跟进摘要不能为空）");
                    }
                    LocalDate logDate = parseDate(asText(a, "logDate"), "logDate", true);
                    yield json(taskService.addLog(id,
                            new TaskDtos.TaskLogRequest(logDate, asText(a, "person"), summary, asText(a, "nextStep"))));
                }
                case "delete_log" -> {
                    Long id = requireId(a);
                    Long logId = asLong(a, "logId");
                    if (logId == null) {
                        yield err("缺少或非法参数 logId（跟进记录的数字主键）");
                    }
                    taskService.deleteLog(id, logId);
                    yield json(Map.of("deleted", true, "message", "跟进记录已删除"));
                }
                case "delete" -> {
                    taskService.delete(requireId(a));
                    yield json(Map.of("deleted", true, "message", "事项已删除"));
                }
                default -> err("非法 action: " + action + "（可选: create/update/status/add_log/delete_log/delete）");
            };
        } catch (ResponseStatusException e) {
            return err(e.getReason() == null ? "操作失败" : e.getReason());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err("操作失败: " + e.getMessage());
        }
    }

    // ---------------- 请求构造 ----------------

    /** create：只取传入字段；ownerUserid 前端已停用，置 null。 */
    private TaskDtos.TaskRequest buildCreateRequest(Map<String, Object> a) throws IllegalArgumentException {
        String board = requireText(a, "board", "create 缺少看板 board（quanfa/happy）");
        String module = requireText(a, "module", "create 缺少工作模块 module");
        String title = requireText(a, "title", "create 缺少标题 title");
        return new TaskDtos.TaskRequest(
                asText(a, "taskCode"),
                board,
                module,
                title,
                asText(a, "description"),
                asText(a, "status"),
                asText(a, "priority"),
                asText(a, "owner"),
                null,
                asText(a, "collab"),
                asText(a, "pain"),
                asText(a, "nextStep"),
                parseDate(asText(a, "deadline"), "deadline", true),
                asText(a, "risk"),
                asStringList(a, "subItems"),
                null);
    }

    /**
     * update：TaskService.applyRequest 不是干净的部分更新（null 字段会被置空），
     * 因此先读全量 detail，对每个字段做「传了才覆盖，未传保留原值」叠加，
     * 再构造完整请求提交，避免误清空其它字段。
     */
    private TaskDtos.TaskRequest buildUpdateRequest(Map<String, Object> a) throws IllegalArgumentException {
        Long id = requireId(a);
        TaskDtos.TaskDetail d = taskService.detail(id);
        String title = asText(a, "title");
        String module = asText(a, "module");
        String board = asText(a, "board");
        String description = asText(a, "description");
        String status = asText(a, "status");
        String priority = asText(a, "priority");
        String owner = asText(a, "owner");
        String collab = asText(a, "collab");
        String pain = asText(a, "pain");
        String nextStep = asText(a, "nextStep");
        String risk = asText(a, "risk");
        LocalDate deadline = parseDate(asText(a, "deadline"), "deadline", true);
        List<String> subItems = asStringList(a, "subItems");
        // taskCode 由服务层管理（applyRequest 不处理），不参与更新
        return new TaskDtos.TaskRequest(
                d.taskCode(),
                board != null && !board.isBlank() ? board : d.board(),
                module != null && !module.isBlank() ? module : d.module(),
                title != null && !title.isBlank() ? title : d.title(),
                description != null ? description : d.description(),
                status != null && !status.isBlank() ? status : d.status(),
                priority != null && !priority.isBlank() ? priority : d.priority(),
                owner != null ? owner : d.owner(),
                d.ownerUserid(),
                collab != null ? collab : d.collab(),
                pain != null ? pain : d.pain(),
                nextStep != null ? nextStep : d.nextStep(),
                deadline != null ? deadline : d.deadline(),
                risk != null ? risk : d.risk(),
                subItems != null ? subItems : d.subItems(),
                null);
    }

    // ---------------- 参数解析 ----------------

    private static Long requireId(Map<String, Object> a) throws IllegalArgumentException {
        Long id = asLong(a, "id");
        if (id == null) {
            throw new IllegalArgumentException("缺少或非法参数 id（数字主键，字符串形式，如 '67'；不是 taskCode）");
        }
        return id;
    }

    private static String requireText(Map<String, Object> a, String k, String message) throws IllegalArgumentException {
        String v = asText(a, k);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return v;
    }

    private static LocalDate parseDate(String s, String name, boolean nullable) throws IllegalArgumentException {
        if (s == null || s.isBlank()) {
            if (nullable) {
                return null;
            }
            throw new IllegalArgumentException("缺少参数 " + name + "（YYYY-MM-DD）");
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("参数 " + name + " 不是合法日期（YYYY-MM-DD）: " + s);
        }
    }

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

    private static Long asLong(Map<String, Object> a, String k) {
        Object v = a.get(k);
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

    // ---------------- JSON Schema 构造 ----------------

    private static Map<String, Object> objSchema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> enumStr(String description, List<String> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("enum", values);
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> strArr(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }
}
