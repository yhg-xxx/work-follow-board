import axios from 'axios'

// 数组参数序列化为重复键（board=quanfa&board=happy），便于后端 @RequestParam List<String> 绑定
const http = axios.create({ baseURL: '/api', timeout: 15000, paramsSerializer: { indexes: null } })

// ---------- 类型定义 ----------
export interface TaskListItem {
  id: number
  taskCode: string | null
  board: string
  module: string | null
  title: string
  description: string | null
  status: string
  priority: string
  owner: string | null
  ownerUserid: string | null
  collab: string | null
  pain: string | null
  nextStep: string | null
  deadline: string | null
  risk: string | null
  pinned: boolean
  sortOrder: number | null
  notifyStatus: string
  updateDate: string | null
  updatedAt: string | null
  subItems: string[]
  logCount: number | null
}

export interface TaskLogRequest {
  logDate?: string | null
  person?: string
  summary: string
  nextStep?: string
}

export interface TaskRequest {
  taskCode?: string | null
  board?: string
  module?: string | null
  title: string
  description?: string | null
  status?: string
  priority?: string
  owner?: string | null
  ownerUserid?: string | null
  collab?: string | null
  pain?: string | null
  nextStep?: string | null
  deadline?: string | null
  risk?: string | null
  subItems?: string[]
}

export interface TaskLogItem {
  id: number
  logDate: string | null
  person: string | null
  summary: string | null
  nextStep: string | null
}

export interface TaskDetail extends TaskListItem {
  logs: TaskLogItem[]
}

export interface NotifyLogItem {
  id: number
  taskId: number | null
  touser: string
  content: string
  result: string
  errcode: number | null
  errmsg: string | null
  createdAt: string | null
}

// ---------- 全量覆盖导入（对应看板导出 JSON 的 data.quanfa / data.happy） ----------
export interface TaskImportLog {
  date: string | null
  person?: string
  summary: string
  next?: string
}

export interface TaskImportItem {
  id?: string
  module?: string
  item: string
  status?: string
  priority?: string
  owner?: string
  collab?: string
  pain?: string
  next?: string
  deadline?: string
  risk?: string
  subItems?: string[]
  logs?: TaskImportLog[]
  deadlineMonth?: string
  updateDate?: string
}

export interface TaskImportRequest {
  quanfa: TaskImportItem[]
  happy: TaskImportItem[]
}

// ---------- 批量导入（新接口 /tasks/import-batch，平面结构 + mode） ----------
export interface TaskBatchLog {
  date?: string | null
  person?: string
  summary?: string
  next?: string
}

/** 批量导入事项（平面结构，board 字段区分看板） */
export interface TaskBatchItem {
  taskCode?: string
  board: string              // 'quanfa' | 'happy'
  module?: string | null
  title: string
  description?: string | null
  status?: string | null
  priority?: string | null
  owner?: string | null
  collab?: string | null
  pain?: string | null
  nextStep?: string | null
  deadline?: string | null
  risk?: string | null
  subItems?: string[]
  logs?: TaskBatchLog[]
  updateDate?: string | null
}

export type ImportMode = 'overwrite' | 'upsert'

export interface ImportBatchRequest {
  mode?: ImportMode
  skipOnError?: boolean
  items: TaskBatchItem[]
}

export interface ImportBatchError {
  rowIndex: number
  field: string
  message: string
  value?: string
}

export interface ImportBatchResult {
  imported: number
  updated: number
  skipped: number
  total: number
  errors: ImportBatchError[]
}

// ---------- API ----------
/** 列表分页响应（keyset 游标分页）：items 当前页；hasMore 是否还有下一页；nextCursor 下一页游标（首页为 null） */
export interface TaskPage {
  items: TaskListItem[]
  hasMore: boolean
  nextCursor: string | null
}

/** 统计条带聚合（当前筛选范围）：总事项 / 亟待解决 / 进行中 / 高优先级 / 7 日内到期 */
export interface TaskStats {
  total: number
  urgent: number
  ongoing: number
  high: number
  near: number
}

/** 列表查询：cursor 为上一页 nextCursor（首页不传）；limit 单页条数；all=true 一次返回全部（导出/全选用） */
export const listTasks = (params: Record<string, unknown>) => http.get<TaskPage>('/tasks', { params })
export const taskStats = (params: Record<string, unknown>) => http.get<TaskStats>('/tasks/stats', { params })
export const getTask = (id: number) => http.get<TaskDetail>(`/tasks/${id}`)

// ---------- 侧边栏菜单聚合统计（替代全量拉取） ----------
export interface MenuModuleStat {
  name: string
  count: number
}
export interface MenuGroupStat {
  id: string
  label: string
  accent: string
  prefix: string
  count: number
  modules: MenuModuleStat[]
}
export interface MenuStats {
  allCount: number
  groups: MenuGroupStat[]
}

// ---------- 菜单统计 / 负责人 / 模块 / 下一事项ID（按需请求） ----------
export const menuStats = () => http.get<MenuStats>('/tasks/menu-stats')
export const suggestOwners = (q?: string) =>
  http.get<string[]>('/tasks/owners', { params: { q: q || undefined } })
export const suggestModules = (board?: string, q?: string) =>
  http.get<string[]>('/tasks/modules', { params: { board: board || undefined, q: q || undefined } })
export const nextTaskCode = (board?: string, module?: string | null) =>
  http.get<{ code: string }>('/tasks/next-code', { params: { board: board || undefined, module: module || undefined } })
export const createTask = (data: TaskRequest) => http.post<TaskDetail>('/tasks', data)
export const updateTask = (id: number, data: TaskRequest) => http.put<TaskDetail>(`/tasks/${id}`, data)
export const deleteTask = (id: number) => http.delete(`/tasks/${id}`)
export const importTasks = (data: TaskImportRequest) => http.post<{ imported: number }>('/tasks/import', data)
export const importBatch = (data: ImportBatchRequest) => http.post<ImportBatchResult>('/tasks/import-batch', data)
export const transitionStatus = (id: number, status: string) =>
  http.patch<TaskDetail>(`/tasks/${id}/status`, null, { params: { status } })
export const addLog = (id: number, data: TaskLogRequest) => http.post<TaskDetail>(`/tasks/${id}/logs`, data)
export const deleteLog = (taskId: number, logId: number) => http.delete(`/tasks/${taskId}/logs/${logId}`)
export const notifyTask = (id: number, scene?: string) =>
  http.post<TaskDetail>(`/tasks/${id}/notify`, null, { params: { scene } })
export const notifyLogs = (taskId: number) => http.get<NotifyLogItem[]>('/notify-logs', { params: { taskId } })
export const pinTask = (id: number, pinned: boolean) => http.patch<TaskDetail>(`/tasks/${id}/pin`, { pinned })
/** 手动排序重排：ids = 被移动的块（新顺序）；afterId/beforeId = 两端锚点（可省略，均缺省时按 ids 整体重排） */
export const reorderTasks = (body: { ids: number[]; afterId?: number | null; beforeId?: number | null }) =>
  http.put('/tasks/reorder', body)

// ---------- 看板管理（/api/boards，t_board 动态看板） ----------
export interface BoardItem {
  id: number
  code: string
  name: string
  accent: string
  prefix: string
  sortOrder: number
  systemFlag: boolean
  taskCount: number
}

export interface BoardRequest {
  code?: string
  name: string
  accent: string
  prefix: string
  sortOrder?: number
}

export const listBoards = () => http.get<BoardItem[]>('/boards')
export const createBoard = (data: BoardRequest) => http.post<BoardItem>('/boards', data)
export const updateBoard = (id: number, data: Partial<BoardRequest>) => http.put<BoardItem>(`/boards/${id}`, data)
export const deleteBoard = (id: number) => http.delete(`/boards/${id}`)
export const reorderBoards = (ids: number[]) => http.put('/boards/reorder', { ids })

// ---------- 工作模块管理（/api/modules，t_module 注册表：侧栏三点菜单） ----------
export interface ModuleItem {
  id: number
  board: string
  name: string
  sortOrder: number
  taskCount: number
}

export const listModules = (board?: string) =>
  http.get<ModuleItem[]>('/modules', { params: { board: board || undefined } })
export const createModule = (data: { board: string; name: string }) => http.post<ModuleItem>('/modules', data)
export const renameModule = (data: { board: string; from: string; to: string }) =>
  http.put<ModuleItem>('/modules/rename', data)
export const deleteModule = (board: string, name: string) =>
  http.delete<number>('/modules', { params: { board, name } })
/** 模块拖拽重排：names = 该看板完整模块名新顺序（后端按序写 sort_order，未注册模块自动落库） */
export const reorderModules = (board: string, names: string[]) =>
  http.put('/modules/reorder', { board, names })

// ---------- 操作日志（/api/op-logs） ----------
export type OpLogAction =
  | 'CREATE'
  | 'UPDATE'
  | 'DELETE'
  | 'STATUS'
  | 'IMPORT'
  | 'EXPORT'
  | 'LOG_ADD'
  | 'LOG_DELETE'

export interface OpLogItem {
  id: number
  action: OpLogAction
  targetType: string | null
  targetId: number | null
  targetCode: string | null
  detail: string | null
  createdAt: string
}

/** 操作日志分页响应（游标分页：nextCursor 为下一页起点，hasMore 表示是否还有更多） */
export interface OpLogPage {
  items: OpLogItem[]
  hasMore: boolean
  nextCursor: number | null
}

export interface OpLogRequest {
  action: OpLogAction
  targetType?: string
  targetId?: number
  targetCode?: string
  detail?: string
}

export const recordOpLog = (data: OpLogRequest) => http.post<OpLogItem>('/op-logs', data)
export const listOpLogs = (params: Record<string, unknown>) => http.get<OpLogPage>('/op-logs', { params })
