import { recordOpLog, type OpLogRequest } from '../api/task'

/** 操作类型 → 中文标签 */
const ACTION_LABELS: Record<string, string> = {
  CREATE: '新建事项',
  UPDATE: '编辑事项',
  DELETE: '删除事项',
  STATUS: '状态流转',
  IMPORT: '数据导入',
  EXPORT: '数据导出',
  LOG_ADD: '添加跟进',
  LOG_DELETE: '删除跟进',
}

/** 操作日志新增后广播的事件名（悬浮面板打开时监听，自动刷新列表） */
export const OP_LOGGED_EVENT = 'op-logged'

/**
 * 记录一条操作日志（fire-and-forget）：失败静默，绝不阻塞主流程。
 * 成功后广播 OP_LOGGED_EVENT，供打开中的操作日志悬浮面板自动刷新。
 */
export function logOp(req: OpLogRequest) {
  recordOpLog(req)
    .then(() => window.dispatchEvent(new CustomEvent(OP_LOGGED_EVENT)))
    .catch(() => {})
}

/** 操作类型英文枚举 → 中文展示标签 */
export function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action
}
