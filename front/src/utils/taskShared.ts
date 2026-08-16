// 共享常量 / 类型 / 工具函数：由 TaskList.vue 及其拆分子组件共同使用
// （原 TaskList.vue 内的常量与导入导出辅助函数，拆组件后避免重复声明）
import type { BoardItem, TaskBatchItem, TaskDetail } from '../api/task'
import { getTask } from '../api/task'

// ---------- 基础枚举 ----------
export const STATUSES = ['未启动', '进行中', '亟待解决', '持续跟进', '已完成']
export const PRIORITIES = ['高', '中', '低']

// ---------- 看板/模块预设配色（6 色，唯一色源；不支持自定义色值） ----------
export const PRESET_COLORS = ['#2B59C3', '#E8862C', '#8B5CF6', '#0EA5E9', '#10B981', '#E11D48']

// ---------- 看板（全部动态来自后端 /boards，无静态常量） ----------
export type BoardMap = Record<string, BoardItem>

// ---------- 主导航分组 ----------
/** 看板 code 或 'all'（全部看板） */
export type NavGroupId = string
export interface MenuModule {
  name: string
  count: number
}
export interface MenuGroup {
  id: string
  label: string
  accent: string // 身份色（十六进制）
  prefix: string // 事项ID前缀
  // boards 过滤器：该分组对应的 board 值数组（'all' 为全部看板 code，其余为该看板 code）
  boardFilters: string[]
  count: number
  modules: MenuModule[]
}

// ---------- 高级筛选（正式生效 / 草稿共用结构） ----------
export interface Filters {
  boards: string[]
  statuses: string[]
  owners: string[]
  dateRange: string[]
  /** 关键词：仅生效筛选含该字段（草稿面板不展示关键词），其余场景可缺省 */
  keyword?: string
}

// ---------- 排序（截止/手动走后端，优先级/更新时间走前端二次排序；置顶始终排最前） ----------
export type SortField = 'deadline' | 'priority' | 'updateDate' | 'manual'
export type SortOrder = 'ascending' | 'descending'

// ---------- 通用小工具 ----------
// 统一提取后端错误信息（变更类操作失败时用于提示）
export function errMsg(err: any): string {
  return err?.response?.data?.message || err?.message || '网络错误'
}

export function fmtISODate(s: string | null | undefined): string {
  if (!s) return ''
  return s.slice(0, 10)
}
/** 看板 code → 名称（未知 code 回退 code 本身） */
export function boardLabel(b: string, boardMap?: BoardMap): string {
  const hit = boardMap?.[b]
  return hit ? hit.name : b
}
/** 看板 code → 事项ID前缀（未知 code 回退 'QF'） */
export function boardPrefix(b: string, boardMap?: BoardMap): string {
  return boardMap?.[b]?.prefix ?? 'QF'
}
export function downloadBlob(blob: Blob, filename: string) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(a.href), 2000)
}
/** 本地时区的今天（YYYY-MM-DD）：避免 toISOString() 按 UTC 截取导致凌晨日期差一天 */
export function localISODate(d = new Date()): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
export const dateTag = () => localISODate()

// ---------- 列映射：中文表头 → 字段 key（xlsx/csv 导入导出共用） ----------

/** xlsx/csv 主 sheet 列定义（导出时按此顺序；导入时按中文表头匹配） */
export interface ColumnDef {
  key: keyof TaskBatchItem | 'subItemsText'
  label: string            // 中文表头
  width: number            // 列宽
  required?: boolean       // 导入必填
  enumValues?: string[]    // 枚举校验
}
export const MAIN_COLUMNS: ColumnDef[] = [
  { key: 'taskCode', label: '事项ID', width: 14 },
  { key: 'board', label: '看板', width: 10, required: true },
  { key: 'module', label: '工作模块', width: 20 },
  { key: 'title', label: '具体事项', width: 36, required: true },
  { key: 'description', label: '描述', width: 40 },
  { key: 'status', label: '当前状态', width: 12, enumValues: STATUSES },
  { key: 'priority', label: '优先级', width: 10, enumValues: PRIORITIES },
  { key: 'owner', label: '负责人', width: 12 },
  { key: 'collab', label: '协作方', width: 20 },
  { key: 'pain', label: '痛点', width: 30 },
  { key: 'nextStep', label: '下一步', width: 30 },
  { key: 'deadline', label: '计划完成日期', width: 16 },
  { key: 'risk', label: '风险·备注', width: 30 },
  { key: 'subItemsText', label: '子项', width: 30 },
  { key: 'updateDate', label: '更新日期', width: 16 },
]
export const LABEL_TO_KEY: Record<string, string> = Object.fromEntries(
  MAIN_COLUMNS.map((c) => [c.label, c.key]),
)

/** 导入 xlsx/csv/JSON 时，"看板"列 → code：优先中文名→code；已是合法 code 直通；未知值原样返回由导入校验报错 */
export function normalizeBoard(v: unknown, boardMap: BoardMap): string | null {
  if (v == null) return null
  const s = String(v).trim()
  if (!s) return null
  if (boardMap[s]) return s
  const hit = Object.values(boardMap).find((b) => b.name === s)
  if (hit) return hit.code
  return s
}

/** exceljs 单元格 → 纯字符串（避免 RichText/Formula/对象） */
export function cellToPlain(v: unknown): string {
  if (v == null) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  if (v instanceof Date) {
    const y = v.getFullYear()
    const m = String(v.getMonth() + 1).padStart(2, '0')
    const d = String(v.getDate()).padStart(2, '0')
    return `${y}-${m}-${d}`
  }
  if (typeof v === 'object') {
    const any = v as any
    if (typeof any.text === 'string') return any.text
    if (Array.isArray(any.richText)) return any.richText.map((r: any) => r.text ?? '').join('')
    if (typeof any.result !== 'undefined') return cellToPlain(any.result)
    if (typeof any.hyperlink === 'string') return any.hyperlink
  }
  return String(v)
}

/**
 * 分批并发拉取事项详情（默认每批 12 个），返回与 ids 顺序一致的 (TaskDetail | null) 数组。
 * 用于导出/备份等需要逐个拉 logs 的场景，避免一次性 Promise.all 打爆连接池。
 */
export async function fetchDetailsInBatches(ids: number[], batchSize = 12): Promise<(TaskDetail | null)[]> {
  const out: (TaskDetail | null)[] = new Array(ids.length).fill(null)
  for (let i = 0; i < ids.length; i += batchSize) {
    const chunk = ids.slice(i, i + batchSize)
    const results = await Promise.all(
      chunk.map((id) => getTask(id).then((r) => r.data).catch(() => null as TaskDetail | null)),
    )
    results.forEach((d, j) => {
      out[i + j] = d
    })
  }
  return out
}
