// 排序状态与当前展示列表：排序全部由后端完成（分页按全局排序取页，置顶恒最前），
// 前端不再二次排序，避免与服务端分页顺序冲突
// sortState 由组合根创建并注入（数据拉取与排序均依赖它，避免循环接线）
import { computed } from 'vue'
import type { Ref } from 'vue'
import type { TaskListItem } from '../api/task'
import type { SortField, SortOrder } from '../utils/taskShared'

export interface SortState {
  field: SortField
  order: SortOrder
}

export const SORT_OPTIONS: { label: string; field: SortField; order: SortOrder }[] = [
  { label: '截止日期 · 正序（先到先处理）', field: 'deadline', order: 'ascending' },
  { label: '截止日期 · 倒序（最远在前）', field: 'deadline', order: 'descending' },
  { label: '优先级 · 高→低', field: 'priority', order: 'descending' },
  { label: '更新时间 · 新→旧', field: 'updateDate', order: 'descending' },
  { label: '手动排序 · 拖拽卡片调整顺序', field: 'manual', order: 'ascending' },
]

// 系统默认排序：首次访问 / 「重置」的落点
export const DEFAULT_SORT: SortState = { field: 'manual', order: 'ascending' }

// 排序偏好全局记忆（同主题偏好先例）：记住上次选择，首次访问用系统默认
const STORAGE_KEY = 'tmo-sort'
function saveSortState(s: SortState) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ field: s.field, order: s.order }))
  } catch {
    /* 存储失败时忽略，当前会话仍可用 */
  }
}

/** 读取记忆的排序（供组合根在创建 sortState 时初始化，首次访问返回系统默认） */
export function loadSortState(): SortState {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const parsed = JSON.parse(saved) as Partial<SortState>
      const hit = SORT_OPTIONS.find((o) => o.field === parsed.field && o.order === parsed.order)
      if (hit) return { field: hit.field, order: hit.order }
    }
  } catch {
    /* localStorage 不可用时用系统默认 */
  }
  return { ...DEFAULT_SORT }
}

// 优先级权重（后端排序语义对齐：高=3，中=2，低=1，其他=0）
export const priWeight = (p: string | null) =>
  p === '高' ? 3 : p === '中' ? 2 : p === '低' ? 1 : 0

export function useTaskSorting(params: { filteredTasks: Ref<TaskListItem[]>; sortState: SortState }) {
  const { filteredTasks, sortState } = params

  const currentSortLabel = computed(() => {
    // 按钮始终显示当前选中的排序项
    const hit = SORT_OPTIONS.find((o) => o.field === sortState.field && o.order === sortState.order)
    return hit ? hit.label.split(' · ')[0] : '排序'
  })
  const isManualSort = computed(() => sortState.field === 'manual')

  function changeSort(opt: { field: SortField; order: SortOrder }) {
    sortState.field = opt.field
    sortState.order = opt.order
    saveSortState(sortState)
  }

  // 当前展示列表：后端已按当前排序分页返回，直接透传（追加页保持全局顺序）
  const tasks = computed<TaskListItem[]>(() => filteredTasks.value)

  return { SORT_OPTIONS, sortState, currentSortLabel, isManualSort, changeSort, priWeight, tasks }
}
