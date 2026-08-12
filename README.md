# 工作跟进看板 (Work Follow Board)

一个**轻量、方便的日常记录看板**，用于团队工作事项跟进。一眼看清：谁在做什么、什么紧急、下一步是什么。

> 本项目由「全发 / 会幸福」两个团队的实际使用场景提炼而来，代码完全开源，业务数据不随仓库分发（见「数据导入」一节）。

## 核心功能

- **事项管理**：新建 / 编辑 / 批量删除；负责人为可创建下拉（输入新名字回车即创建，后续自动进入候选）
- **状态流转**：未启动 / 进行中 / 亟待解决 / 持续跟进 / 已完成（卡片只读展示，编辑弹窗内修改）
- **优先级**：高 / 中 / 低
- **看板分组**：`quanfa`（全发）/ `happy`（会幸福），模块名含「临时」自动归入紫色「临时事项」
- **卡片视图**：文件夹卡片按行展示全部字段，逾期 / 临期截止高亮，底部操作区（更新 / 截止 / 跟进 / 编辑）
- **跟进面板**：点卡片「跟进 N 条 ›」在卡片右侧悬浮展开，时间轴展示 + 增删跟进记录，自动定位不遮挡卡片
- **操作日志**：悬浮面板，记录所有增删改操作，支持日期区间 + 类型筛选、滚动加载、实时刷新
- **列表筛选 / 排序**：关键词搜索（<mark> 高亮）+ 排序下拉 + 高级筛选悬浮面板（分组 / 状态 / 负责人 / 截止日期）
- **统计条带**：总事项 / 亟待解决 / 进行中 / 7 日内到期
- **导入 / 导出**：Excel（.xlsx，双 Sheet：事项 + 跟进记录）/ CSV / 备份 JSON 导出；JSON / XLSX / CSV 增量 upsert 或全量覆盖导入（全量覆盖前自动下载备份）
- **深色模式**：一键切换，Element Plus 弹层同步适配

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 `backend/` | Spring Boot 4.1.0（Java 17）+ Spring Data JPA + MySQL（库名 `tmo_task`） |
| 前端 `front/` | Vue 3.5 + Vite + TypeScript + Element Plus + vue-router |

## 目录结构

```
├── backend/        # Spring Boot 后端（REST API + JPA + MySQL）
│   └── src/main/resources/sql/   # init.sql（建表）+ migration_*.sql（增量迁移）
├── front/          # Vue3 前端（单页应用）
├── tools/          # 看板 JSON → 导入 SQL 生成器（gen-import-sql.mjs）
└── CLAUDE.md       # 项目工作文档（架构 / API / 本地启动）
```

## 本地启动

**前置**：本机 MySQL 已建 `tmo_task` 库并执行 `backend/src/main/resources/sql/init.sql`。

```bash
# 后端（默认端口 8084）
cd backend && ./mvnw spring-boot:run

# 前端（开发服务器，默认 http://localhost:5173，API 经 vite proxy 转发）
cd front && npm install && npm run dev
```

生产单 jar 部署时，前端需先构建并拷贝到后端静态目录（被仓库忽略，本地生成）：

```bash
cd front && npm run build && cp -r dist/* ../backend/src/main/resources/static/
```

## 数据导入

- `tools/gen-import-sql.mjs`：将看板 JSON 转换为导入 SQL（内部工具）
- 业务数据含真实人名与工作内容，出于隐私考虑**不随公开仓库分发**；建表结构见 `init.sql`，导入后即为空看板

## REST API（/api）

- `GET /tasks`：列表（按 board / status / owner / keyword / deadlineFrom / deadlineTo 过滤）
- `GET /tasks/owners?q=`：负责人去重列表
- `GET /tasks/{id}`：详情（含跟进记录）
- `POST /tasks` / `PUT /tasks/{id}` / `DELETE /tasks/{id}`：增删改
- `PATCH /tasks/{id}/status?status=`：状态流转
- `POST /tasks/{id}/logs` / `DELETE /tasks/{id}/logs/{logId}`：跟进记录增删
- `POST /tasks/import-batch`：批量导入（增量 upsert / 全量覆盖）

## License

[MIT](https://opensource.org/licenses/MIT)
