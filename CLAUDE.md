# CLAUDE.md — 工作跟进看板（TMO 项目）

## 项目定位

一个**轻量、方便的日常记录看板**，用于「全发 / 会幸福」两个团队的工作事项跟进。一眼看清：谁在做什么、什么紧急、下一步是什么。

**当前阶段（第一阶段）**：仅给**领导一个人**使用，是单用户的个人记录工具，不做多租户、不做登录鉴权、不面向团队开放。

## 重要约定

- **企业微信（企微）相关代码一律不动**：`backend/src/main/java/com/example/wecom/`、`backend/src/main/java/com/qq/weixin/mp/aes/`（回调加解密 SDK）、`WeComProperties`、`application.yaml` 中的 `wecom.*` 配置。
- 当前**不连接企微**，不需要配置企微密钥，也不依赖企微服务可用。
- 前端的「推送」「推送记录」等企微相关功能入口可保留，但不作为当前阶段的重点。
- 前端**不展示企微 userid**：卡片「协作」行与新建/编辑弹窗均已移除该字段；表单状态仍静默携带原值（编辑时不误清空），后端 `owner_userid` 字段保留。

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 backend/ | Spring Boot 4.1.0（Java 17）+ Spring Data JPA + MySQL（库名 `tmo_task`） |
| 前端 front/ | Vue 3.5 + Vite 8 + TypeScript + Element Plus + vue-router |
| 数据来源 | `工作跟进看板_2026-08-03.json`（48 条事项：quanfa 34 + happy 14） |

## 核心功能

- 事项管理：新建 / 编辑 / 批量删除；**负责人为可创建下拉**（el-select filterable+allow-create：选已有或输入新名字回车创建；打开弹窗时经 `GET /tasks/owners` 全量拉取候选本地过滤，编辑时当前负责人自动并入候选），新负责人保存后自动进入下次候选（后端按 `t.owner` 去重派生，无独立负责人表）
- 状态流转：未启动 / 进行中 / 亟待解决 / 持续跟进 / 已完成；**卡片上状态为只读展示（无下拉），修改仅能在编辑弹窗内进行**（后端 `PATCH /tasks/{id}/status` 保留）
- 优先级：高 / 中 / 低
- 看板分组：`quanfa`（全发）/ `happy`（会幸福）；模块名含「临时」归入紫色「临时事项」分组
- 左侧菜单栏：侧栏顶部按钮条可**收起/展开**（收起后仅剩 52px 窄栏 + 打开按钮；导航与底部统计随宽度收缩淡出，箭头平滑旋转；窄屏收起保持整行宽）
- 卡片视图：事项以**文件夹卡片**展示，**每行 4 个**（≤1280px 降 3 列，≤860px 降 2 列）——左上角身份色标签（蓝=全发 / 橙=会幸福 / 紫=临时，标注事项 ID）、底部身份色脊线；卡片展示除「跟进记录」外的全部字段（标题、模块、状态、优先级、负责人、描述、协作方、痛点、下一步、风险★、子项、截止/更新日期），长内容 2 行省略，逾期/临期截止高亮；底部操作区（更新/截止/跟进/编辑）`margin-top: auto` 始终贴底，不随中间字段缺失上移
- 跟进面板：点卡片底部「跟进 N 条 ›」在卡片**右侧悬浮展开**（右缘放不下自动弹左侧、宽度自适应收窄、不遮被点卡片），展示跟进记录时间轴 + 「＋ 添加跟进」（按钮点开表单：日期/跟进人/摘要/下一步，日期与跟进人各占 50%）；一次只展开一张，面板随卡片滚动；每条记录 **hover 浮现删除按钮**，确认后删除并同步卡片跟进计数
- 操作日志悬浮面板（2026-08-07 由独立路由 `/plan` 改为主页悬浮框，路由与 OpLogView.vue 已删除）：顶部 header「操作日志」按钮触发，面板 `position:fixed` 贴按钮下方（宽 `min(480px, 100vw-24px)` × 高 `min(60vh, 520px)`，`lp-in` 滑入）；顶部筛选一行 = 日期区间 + 操作类型多选；下方**紧凑两行列表**（上行：等宽时间 + 类型彩色徽标 + 事项ID，下行：详情），时间倒序（最新在上），滚动到底自动加载（首次 300 条）；打开时拉取 + 监听 `op-logged` 事件自动刷新（`useOpLog.logOp` 成功后广播）+ 手动刷新按钮；关闭方式：关闭按钮 / 再点入口 / 点面板外（沿用 EP 弹层守卫）
- 列表筛选 / 排序（工具栏单行布局）
  - **工具栏单行**：左侧「搜索框 + 排序下拉按钮 + 高级筛选按钮」，右侧「导入 / 导出 / 新建 / 全选 / 删除」，弹性水平排布，窄屏自动横滚（toolbar 内 `overflow-x:auto`），不移用两行布局
  - **搜索框**：独立关键词搜索（事项 ID / 名称 / 工作模块），不占用高级筛选项，回车/按钮触发，搜索结果 <mark> 高亮
  - **排序下拉**（el-dropdown）：4 项无默认占位：截止日期正序（先到先处理，默认）/ 截止日期倒序 / 优先级高→低 / 更新时间新→旧，已选项前加 ✓，禁用重复选择
  - **高级筛选悬浮面板**（与跟进面板同风格：`lp-in` 滑入动画 / 头部 + 滚动体 + 底部重置/应用）：4 行筛选项（看板分组多选 / 事项状态多选 / 负责人 el-autocomplete 自动补全 / 截止日期区间 daterange），无关键词行（避免与外部搜索框重复）；面板用红色徽标计数「已应用的筛选数」，面板头部显示「当前草稿中生效的筛选数」；关闭时草稿不清空，重新打开会回填；点击「应用筛选」才同步到 `filters` 并刷新列表、同步关闭面板
- 统计条带（工具栏上方）：总事项 / 亟待解决 / 进行中 / 7 日内到期，4 项分色数值（等宽数字）
- 数据导入：由 `tools/gen-import-sql.mjs` 从看板 JSON 生成 SQL 脚本手动导入（`backend/src/main/resources/sql/data_2026-08-03.sql`）

## 目录结构

```
TMO/
├── backend/                        # Spring Boot 后端（REST API + JPA + MySQL）
│   └── src/main/java/com/example/
│       ├── task/                   # 核心业务：controller / dto / entity / repository / service
│       ├── wecom/                  # 企微对接（当前阶段不改动）
│       ├── config/WeComProperties  # 企微配置（当前阶段不改动）
│       └── (com.qq.weixin.mp.aes)  # 企微回调加解密 SDK（当前阶段不改动）
│   └── src/main/resources/
│       ├── application.yaml        # 数据源 + wecom.* 占位配置
│       ├── application-local.yaml  # 本地密钥（不随仓库提交）
│       └── sql/                    # init.sql / migration / data_2026-08-03.sql
├── front/                          # Vue3 前端（单页应用）
│   └── src/
│       ├── views/TaskList.vue      # 主页面壳：统计条带 / 工具栏 / 卡片区 + 全局协调（导航、筛选草稿、排序分页、导出、批量删除）
│       ├── components/             # 2026-08-07 从 TaskList.vue 拆出的子组件
│       │   ├── SidebarNav.vue      # 左侧导航菜单（受控：props/emits 与父组件通信）
│       │   ├── FilterPanel.vue     # 高级筛选悬浮面板（受控：props/emits，expose 根元素供外点关闭判定）
│       │   ├── EditorDialog.vue    # 新建/编辑弹窗（自持全部表单逻辑，expose open(task?)）
│       │   ├── ImportDialog.vue    # 导入预览弹窗（自持解析/映射/校验，expose open()）
│       │   ├── FollowPanel.vue     # 卡片跟进面板（自持详情/定位/增删记录，按 taskId 重建，ResizeObserver 重定位）
│       │   ├── OpLogPanel.vue      # 操作日志悬浮面板（自持筛选/列表/滚动加载/自动刷新，挂 App.vue，expose 根元素供外点关闭判定）
│       │   ├── ClockLabel.vue      # 通用时钟小组件
│       │   └── ThemeToggle.vue     # 主题切换按钮
│       ├── composables/            # useOpLog.ts（操作日志）/ useTheme.ts（主题切换）
│       ├── utils/taskShared.ts     # 共享常量/类型/导入导出辅助（STATUSES、PRIORITIES、BOARDS、MAIN_COLUMNS 等）
│       ├── api/task.ts             # 后端 API 封装
│       ├── router/index.ts
│       └── assets/                 # base.css / main.css（设计令牌：配色/字体/圆角/阴影）
├── tools/gen-import-sql.mjs        # 看板 JSON → 导入 SQL 生成器
├── weworkapi_java-master/          # 企微官方回调加解密 SDK 源码（参考）
└── 工作跟进看板_2026-08-03.json    # 原始看板数据
```

## 数据库（tmo_task）

- `t_task`：事项主表（task_code 唯一键，字段对齐看板 JSON）
- `t_sub_item`：子项表
- `t_task_log`：跟进记录表
- `t_notify_log`：企微推送记录表（当前阶段基本不使用）

初始化脚本见 `backend/src/main/resources/sql/init.sql`；已建库环境的迁移脚本见 `migration_20260803_add_deadline_month.sql`。

## REST API（/api）

- `GET /tasks`：列表（可按 board / status / owner / keyword / deadlineFrom / deadlineTo 过滤；返回卡片全字段 + logCount + subItems，跟进记录 logs 仅在详情接口返回）
- `GET /tasks/owners?q=`：负责人去重列表（编辑弹窗负责人候选）
- `GET /tasks/{id}`：详情
- `POST /tasks`：新建
- `POST /tasks/import`：批量导入
- `PUT /tasks/{id}`：更新
- `DELETE /tasks/{id}`：删除
- `PATCH /tasks/{id}/status?status=`：状态流转
- `POST /tasks/{id}/logs`：添加跟进记录
- `DELETE /tasks/{id}/logs/{logId}`：删除单条跟进记录（校验归属该事项，404 表示不存在；经 orphanRemoval 级联删除）
- `POST /tasks/{id}/notify`：企微推送（当前阶段不使用）
- `GET /notify-logs?taskId=`：推送记录（当前阶段不使用）

## 本地启动

- 后端：`cd backend && ./mvnw spring-boot:run`（需本机 MySQL 已建 `tmo_task` 库并执行 init.sql）
- 前端：`cd front && npm run dev`（开发服务器，通常 http://localhost:5173）
- 前端类型检查：`cd front && npm run type-check`

---

## 前端交互 · 踩坑与经验（2026-08-07 单行工具栏改造）

### 1. 单行工具栏 + 筛选排序按钮
- **需求背景**：把两行筛选（看板/状态/负责人/日期/关键词 + 导入/导出/新建）合并为单行，筛选/排序改为「按钮」+「悬浮面板」，节省纵向空间。
- **决策**：关键词搜索保留行内（使用频次最高），其余 4 项移入「高级筛选悬浮面板」；排序新增独立按钮（el-dropdown）。
- **选型教训**：一开始把「高级筛选」做成 Dialog，打断感强；改为与跟进面板一致的悬浮式面板（贴按钮右/左侧、滑入动画），一致性更好。

### 2. 悬浮面板 DOM 放哪里，才能不被裁、叠在卡片上方
| 尝试 | 结果 |
|------|------|
| ❌ `toolbar > .filter-panel`，toolbar 做 `position:relative + overflow-x:auto` | toolbar 的 `overflow-x:auto` 会裁掉超出其高度/宽度的面板（尤其右侧向下溢出被横滚容器裁剪） |
| ❌ `cards-region > .filter-panel`（cards-region 做定位包含块） | cards-region 有 `overflow:hidden`，**再次裁剪**，而且 z-index 低于卡片自身 |
| ✅ `.board-page > .filter-panel`（toolbar 的**同级兄弟元素**，board-page 做 `position:relative`） | 面板独立于两个 overflow 容器，定位用 `getBoundingClientRect(btn) - getBoundingClientRect(board-page)` 差值换算内部 left/top，不受父级 overflow 影响；z-index:7 保证叠在卡片上方 |

### 3. 「点击面板外部关闭」与 Element Plus 弹层（teleport）冲突
- **Bug**：面板里 el-select/el-autocomplete/el-date-picker 展开选项后，点击选项立即关掉了面板，v-model 还没来得及赋值。
- **根因**：Element Plus 的下拉/日期弹层默认 `teleport` 到 `<body>`，不在 `.filter-panel` DOM 内，所以 `panel.contains(target)` 误判为「点面板外」。
- **修复**：在 `onDocClickForFilter` 中增加 `isInsideEpPopup()` 兜底，命中 10 种 EP 弹层类名（`.el-select-dropdown / .el-popper / .el-picker-panel …`）时 return 不关。注意关闭判定优先级：「按钮上 → 面板内 → EP 弹层内 → 真外部关」。

### 4. 深浅色模式：只使用「base.css 中已定义过」的设计令牌
- 两个未定义变量把深色模式效果拖崩：
  - `--c-primary` ❌ 不存在（项目主色令牌叫 `--c-blue`）→ 大量 `color-mix(in srgb, var(--c-primary) X%, …)` 全失效，高亮/描边/图标蓝全丢。
  - `--c-bg-soft` ❌ 不存在 → fallback 硬编码 `#f7f8fa` 白，深色下 filter-body 卡片是一块刺眼亮白。
- **一律替换为既有令牌**：
  - `--c-primary` → `--c-blue`（浅 `#2456C9` / 深 `#5B8AE6`，都有）
  - `--c-bg-soft,#f7f8fa` → `--c-row-zebra`（浅 `#F5F9FF` / 深 `#16213A`，都有，视觉近似）
- **面板阴影**也按主题分写：浅色蓝雾 `rgba(15,27,61,.22)`；深色三层叠加（重投影+细节阴影+1px 内描边），避免面板「贴在背景上发灰」。

### 5. 主题挂点确认：`<html data-theme="dark" class="dark">` 双属性
- `applyTheme()` 会同步写 `<html>` 的 `data-theme="dark"`（驱动我们自己的语义令牌）与 `class="dark"`（驱动 Element Plus 官方 dark/css-vars.css 变量）。
- 两者都挂在 `<html>`，因此 teleport 到 body 的 EP 弹层（直接子节点）能正确命中 `html.dark` 的 EP 深色变量，不需要额外 `popper-class` 补丁。
