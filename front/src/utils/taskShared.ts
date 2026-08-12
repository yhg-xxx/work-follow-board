// 共享常量 / 类型 / 工具函数：由 TaskList.vue 及其拆分子组件共同使用
// （原 TaskList.vue 内的常量与导入导出辅助函数，拆组件后避免重复声明）
import type { TaskBatchItem } from '../api/task'

// ---------- 基础枚举 ----------
export const STATUSES = ['未启动', '进行中', '亟待解决', '持续跟进', '已完成']
export const PRIORITIES = ['高', '中', '低']
export const BOARDS = [
  { label: '全发', value: 'quanfa' },
  { label: '会幸福', value: 'happy' },
]
// 临时类模块：聚合为「临时事项」分组（模块名含有「临时」字眼）
export const TEMP_MODULE_HINT = '临时'

// ---------- 主导航分组 ----------
export type NavGroupId = 'quanfa' | 'happy' | 'temp' | 'all'
export interface MenuModule {
  name: string
  count: number
}
export interface MenuGroup {
  id: NavGroupId
  label: string
  accent: string // 身份色（十六进制）
  // boards 过滤器：该分组对应的 board 值数组（为空表示不按 board 过滤）
  boardFilters: string[]
  // 仅匹配模块名命中该关键字的事项（用于「临时事项」）
  moduleKeyword?: string
  count: number
  modules: MenuModule[]
}
// 分组静态配置（身份色 / 过滤语义）；count 与 modules 由后端聚合接口填充
export const GROUP_META: Record<NavGroupId, { label: string; accent: string; boardFilters: string[]; moduleKeyword?: string }> = {
  all: { label: '全部看板', accent: '#0EA5E9', boardFilters: ['quanfa', 'happy'] },
  quanfa: { label: '全发', accent: '#2B59C3', boardFilters: ['quanfa'] },
  happy: { label: '会幸福', accent: '#E8862C', boardFilters: ['happy'] },
  temp: { label: '临时事项', accent: '#8B5CF6', boardFilters: [], moduleKeyword: TEMP_MODULE_HINT },
}

// ---------- 高级筛选（正式生效 / 草稿共用结构） ----------
export interface Filters {
  boards: string[]
  statuses: string[]
  owners: string[]
  dateRange: string[]
}

// ---------- 通用小工具 ----------
// 统一提取后端错误信息（变更类操作失败时用于提示）
export function errMsg(err: any): string {
  return err?.response?.data?.message || err?.message || '网络错误'
}

export function fmtISODate(s: string | null | undefined): string {
  if (!s) return ''
  return s.slice(0, 10)
}
export function boardLabel(b: string) {
  return b === 'happy' ? '会幸福' : '全发'
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
export const dateTag = () => new Date().toISOString().slice(0, 10)

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
  { key: 'board', label: '看板', width: 10, required: true, enumValues: ['全发', '会幸福', 'quanfa', 'happy'] },
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

/** 导入 xlsx/csv 时，"看板"列的中文→英文映射 */
export function normalizeBoard(v: unknown): string | null {
  if (v == null) return null
  const s = String(v).trim()
  if (!s) return null
  if (s === '全发' || s.toLowerCase() === 'quanfa') return 'quanfa'
  if (s === '会幸福' || s.toLowerCase() === 'happy') return 'happy'
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
