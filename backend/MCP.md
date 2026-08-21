# 工作跟进看板 · MCP Server（SSE）接入指南

把现有看板后端叠加了一层 **MCP（Model Context Protocol）能力**，以 **SSE 传输**对外暴露看板操作，
供 AI 客户端（VS Code、Claude Desktop 等）通过 MCP 协议读取看板数据。

> 现有 REST API（`/api`）与 Vue 前端**完全不变**；MCP 是叠加层。
> 本阶段为**只读**工具（第一阶段）；写操作（新建/更新/删除/状态流转/跟进/导入）为规划中的第二阶段。

## 端点

MCP 挂在 `/mcp` 命名空间（基于 MCP Java SDK v2 的 `HttpServletSseServerTransportProvider`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/mcp/sse`      | SSE 长连接（服务端→客户端事件），客户端首先连这里建立会话 |
| POST | `/mcp/message`  | 客户端→服务端 JSON-RPC 消息，需带 `?sessionId=xxx`（建立 SSE 后由服务端下发） |

默认端口 `8084`，即 `http://localhost:8084/mcp/sse`。

## 客户端配置示例

### VS Code（`mcp.json` / `.vscode/mcp.json` 或 `settings.json` 的 `mcp` 段）

```json
{
  "servers": {
    "work-follow-board": {
      "type": "sse",
      "url": "http://localhost:8084/mcp/sse"
    }
  }
}
```

### Claude Desktop（`claude_desktop_config.json`）

```json
{
  "mcpServers": {
    "work-follow-board": {
      "url": "http://localhost:8084/mcp/sse"
    }
  }
}
```

> SSE 类型客户端连接的是 **SSE 端点**（`/mcp/sse`），消息端点由协议在握手阶段自动协商，无需手动填。

## 只读工具清单（第一阶段）

所有工具返回 JSON 文本，便于 LLM 解析。状态枚举：`未启动 / 进行中 / 亟待解决 / 持续跟进 / 已完成`；
看板枚举：`quanfa`（全发）/ `happy`（会幸福）。

| 工具 | 说明 | 主要参数 |
|------|------|----------|
| `list_tasks` | 按条件列出事项卡片（含 `hasMore` / `nextCursor` 分页） | `boards[]`、`statuses[]`、`owners[]`、`keyword`、`deadlineFrom`、`deadlineTo`、`sortMode`、`cursor`、`limit` |
| `search_tasks` | 全文搜索（空白拆词 AND，跨多字段）；可选叠加看板过滤 | `keyword`（必填）、`boards[]` |
| `get_task` | 获取单个事项全字段 + 跟进记录时间轴 | `id`（必填） |
| `get_task_owners` | 去重负责人候选列表（模糊匹配） | `q`（可选） |
| `get_stats` | 统计条带：总数 / 亟待解决 / 进行中 / 7 日内到期 | `boards[]`、`statuses[]`、`owners[]`、`keyword`、`deadlineFrom`、`deadlineTo` |

## 启动前置

与普通后端一致：

1. 本机 MySQL 已建 `tmo_task` 库并执行 `backend/src/main/resources/sql/init.sql`；
2. 配置 `application.yaml` 数据源（参考 `application-local.yaml`）；
3. 启动后端：

   ```bash
   cd backend && ./mvnw spring-boot:run
   ```

启动后访问 `http://localhost:8084/mcp/sse` 即可被 MCP 客户端连接。
可用 [MCP Inspector](https://github.com/modelcontextprotocol/inspector) 验证：
`npx @modelcontextprotocol/inspector` → 选择 **SSE** 传输 → 填入 `http://localhost:8084/mcp/sse`。

## 安全说明

- 当前 SSE 端点**无鉴权、全网暴露**（按需求有意选择，便于个人本地/局域网使用）。
- 后续如需远程访问，建议在网关层或本配置中增加 **token 校验 + 仅绑 localhost**，并保留 CORS 白名单。

## 第二阶段（规划，尚未实现）

写工具将复用现有 Service 方法，每个一个工具：`create_task`、`update_task`、`delete_task`、
`change_status`、`add_follow_log`、`delete_follow_log`、`import_tasks`。
实施前需先确认 `WeComApiClient.sendTaskNotify(...)` 在「未配置企微」阶段为安全 no-op。
