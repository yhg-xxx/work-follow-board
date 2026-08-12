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
export const listTasks = (params: Record<string, unknown>) => http.get<TaskListItem[]>('/tasks', { params })
export const getTask = (id: number) => http.get<TaskDetail>(`/tasks/${id}`)

// ---------- 侧边栏菜单聚合统计（替代全量拉取） ----------
export interface MenuModuleStat {
  name: string
  count: number
}
export interface MenuGroupStat {
  id: string
  count: number
  modules: MenuModuleStat[]
}
export interface MenuStats {
  allCount: number
  groups: MenuGroupStat[]
  temp: MenuGroupStat
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

export interface OpLogRequest {
  action: OpLogAction
  targetType?: string
  targetId?: number
  targetCode?: string
  detail?: string
}

export const recordOpLog = (data: OpLogRequest) => http.post<OpLogItem>('/op-logs', data)
export const listOpLogs = (params: Record<string, unknown>) => http.get<OpLogItem[]>('/op-logs', { params })
