# 工作跟进看板 · MCP Server（SSE）接入指南

把现有看板后端叠加了一层 **MCP（Model Context Protocol）能力**，以 **SSE 传输**对外暴露看板操作，
供 AI 客户端（VS Code、Claude Desktop 等）通过 MCP 协议读取看板数据。

> 现有 REST API（`/api`）与 Vue 前端**完全不变**；MCP 是叠加层。
> 工具精简为**一读一写**两个：`query_tasks`（查询）与 `mutate_tasks`（变更），按 `action` 参数分发子操作。

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

## 工具清单

只有 **2 个工具**（一读一写），全部返回 JSON 文本，便于 LLM 解析。
状态枚举：`未启动 / 进行中 / 亟待解决 / 持续跟进 / 已完成`；优先级：`高 / 中 / 低`；看板：`quanfa`（全发）/ `happy`（会幸福）。

服务端已关闭 SDK 对工具入参的强校验（`validateToolInputs(false)`，规避 v2.0.1 校验缺陷），
必填项与枚举合法性由工具 handler 兜底校验，非法参数返回 `isError=true` 的中文错误。

### `query_tasks` —— 读

按 `action` 分发 4 种查询（不传 `action` 默认为 `list`）：

| action | 说明 | 关键参数 |
|--------|------|----------|
| `list` | 按条件列出事项（**紧凑摘要**：id/taskCode/board/module/title/status/priority/owner/collab/deadline/risk/nextStep/logCount/updateDate，不含描述/痛点/子项） | `boards[]`、`statuses[]`、`owners[]`、`keyword`、`deadlineFrom`、`deadlineTo`、`sortMode`、`cursor`、`limit` |
| `detail` | 获取单个事项**全字段 + 跟进记录时间轴** | `id`（必填，数字主键字符串） |
| `owners` | 去重负责人候选列表 | `q`（可选） |
| `stats` | 统计条带：总数 / 亟待解决 / 进行中 / 高优先级 / 7 日内到期 | `boards[]`、`statuses[]`、`owners[]`、`keyword`、`deadlineFrom`、`deadlineTo` |

> `id` 均为数字主键（字符串形式，如 `"67"`），不是 taskCode（如 `QF-E01`）；要查完整信息先用 `list` 拿 id 再 `detail`。
>
> `owners[]` 支持**模糊匹配**：传部分名字（如 `"张"`）按 `LIKE %张%` 匹配，多值取 OR，传完整名字等同精确（2026-08-22）。REST `/api/tasks?owner=` 保持精确 IN 不变。

### `mutate_tasks` —— 写

按 `action` 分发 6 种变更（`action` 必填）：

| action | 说明 | 关键参数 |
|--------|------|----------|
| `create` | 新建事项 | `board`/`module`/`title`（必填），`taskCode`/`description`/`status`/`priority`/`owner`/`collab`/`pain`/`nextStep`/`deadline`/`risk`/`subItems[]`（可选） |
| `update` | 编辑事项（**部分更新**：只覆盖传入的非空字段，其余保持原值） | `id`（必填），其余字段同 create（可选） |
| `status` | 仅改状态 | `id`（必填）、`status`（必填） |
| `add_log` | 添加跟进记录 | `id`（必填）、`summary`（必填）、`logDate`/`person`/`nextStep`（可选） |
| `delete_log` | 删除单条跟进记录 | `id`（必填）、`logId`（必填，来自 detail 的 `logs[].id`） |
| `delete` | 删除事项（谨慎） | `id`（必填） |

> `update` 采用「读全量 → 非 null 参数叠加 → 整单提交」，避免服务层部分更新把未传字段置空。
> 写工具**不含批量导入/全量覆盖**；导入仍走 REST 接口。

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
- `WeComApiClient.sendTaskNotify(...)` 在未配置企微时为安全 no-op，写操作不依赖企微服务可用。
