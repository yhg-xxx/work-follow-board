// 列表数据源：按当前导航 + 筛选 + 排序分页请求后端（keyset 游标，逐页累积）；
// 筛选/排序变化自动重置并重拉第一页；统计条带聚合与列表同套筛选条件
import { computed, ref, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { listTasks, taskStats } from '../api/task'
import type { TaskListItem, TaskStats } from '../api/task'
import type { Filters, MenuGroup, SortField, SortOrder } from '../utils/taskShared'
import type { NavState } from './useMenuStats'

/** 单页条数（服务端分页，滚动到底加载下一页） */
export const PAGE_SIZE = 20

export interface QueryContext {
  navState: NavState
  filters: Filters
  sortState: { field: SortField; order: SortOrder }
  allBoardCodes: ComputedRef<string[]>
  menuGroups: ComputedRef<MenuGroup[]>
}

/** 根据当前导航 + 筛选栏构造后端查询参数 */
export function buildTaskQuery(ctx: QueryContext): Record<string, unknown> {
  const { navState, filters, sortState, allBoardCodes, menuGroups } = ctx
  const params: Record<string, unknown> = {}

  // 看板：导航分组 boardFilters 与筛选栏 boards 同时约束（任一方为空表示不约束该方）
  // 'all' 分组的 boardFilters = 全部看板 code；双方非空且无交集时用哨兵值匹配空集
  const navGroup = navState.group !== 'all' ? menuGroups.value.find((m) => m.id === navState.group) : null
  const navBoards = navState.group === 'all' ? allBoardCodes.value : (navGroup?.boardFilters ?? [])
  const tbBoards = filters.boards
  const navConstrained = navBoards.length > 0
  const tbConstrained = tbBoards.length > 0
  let boards: string[] | null = null
  if (navConstrained && tbConstrained) {
    const inter = tbBoards.filter((b) => navBoards.includes(b))
    boards = inter.length ? inter : ['__none__']
  } else if (navConstrained) {
    boards = navBoards
  } else if (tbConstrained) {
    boards = tbBoards
  }
  if (boards) params.board = boards

  // 状态
  if (filters.statuses.length) params.status = filters.statuses

  // 工作模块：点击具体模块 → 该模块精确匹配（看板为真实看板，无特殊派生分组）
  let modules: string[] = []
  if (navState.module) {
    modules = [navState.module]
  }
  if (modules.length) params.module = modules

  if (filters.owners.length) params.owner = filters.owners
  const kw = (filters.keyword ?? '').trim()
  if (kw) params.keyword = kw
  const [df, dt] = filters.dateRange
  if (df) params.deadlineFrom = df
  if (dt) params.deadlineTo = dt
  // 排序：全部排序模式走后端（分页按全局排序取页），置顶恒最前
  if (sortState.field === 'deadline') {
    params.sortOrder = sortState.order === 'ascending' ? 'asc' : 'desc'
  } else if (sortState.field === 'manual') {
    params.sortOrder = 'manual'
  } else if (sortState.field === 'priority') {
    params.sortOrder = 'priority'
  } else if (sortState.field === 'updateDate') {
    params.sortOrder = 'updateDate'
  }
  return params
}

export function useTaskData(params: {
  ctx: QueryContext
  onLoaded?: () => void
}) {
  const { ctx, onLoaded } = params

  // filteredTasks：按当前导航 + 筛选条件分页累积的卡片数据源（后端已按全局排序）
  const loading = ref(false)
  const filteredTasks = ref<TaskListItem[]>([])
  const loadedAll = ref(true)
  const stats = ref<TaskStats>({ total: 0, urgent: 0, ongoing: 0, high: 0, near: 0 })
  // 查询重置计数：TaskList 借此在筛选变化时清空勾选（追加页不清空）
  const resetTick = ref(0)

  // 筛选/排序条件变化 → 重置并重拉第一页（关键词仅在回车/点击搜索时提交，无需防抖）
  const filterSignature = computed(() =>
    JSON.stringify({
      g: ctx.navState.group,
      m: ctx.navState.module,
      b: ctx.filters.boards,
      s: ctx.filters.statuses,
      o: ctx.filters.owners,
      k: ctx.filters.keyword ?? '',
      d: ctx.filters.dateRange,
      sf: ctx.sortState.field,
      so: ctx.sortState.order,
    }),
  )
  watch(filterSignature, () => fetchFiltered())

  // 请求序号：只采纳最新一次请求的结果，丢弃过期响应，避免快速切换筛选/排序时旧请求覆盖新结果
  let fetchSeq = 0
  let nextCursor: string | null = null

  /** 重置：清空已累积列表，拉第一页 + 统计聚合 */
  async function fetchFiltered() {
    const seq = ++fetchSeq
    loading.value = true
    try {
      const [pageRes, statsRes] = await Promise.all([
        listTasks({ ...buildTaskQuery(ctx), limit: PAGE_SIZE }),
        taskStats(buildTaskQuery(ctx)),
      ])
      if (seq !== fetchSeq) return // 已有更新的请求发出，本次结果过期，丢弃
      filteredTasks.value = pageRes.data.items
      nextCursor = pageRes.data.nextCursor
      loadedAll.value = !pageRes.data.hasMore
      stats.value = statsRes.data
      resetTick.value++
    } finally {
      // 只有最新一次请求完成才结束 loading（旧请求提前返回时仍需等待新请求）
      if (seq === fetchSeq) {
        loading.value = false
        onLoaded?.()
      }
    }
  }

  /** 追加下一页（无限滚动触发；有下一页且无并发请求时才会真正发起） */
  async function loadMore() {
    if (loading.value || loadedAll.value || nextCursor == null) return
    const seq = ++fetchSeq
    loading.value = true
    try {
      const res = await listTasks({ ...buildTaskQuery(ctx), cursor: nextCursor, limit: PAGE_SIZE })
      if (seq !== fetchSeq) return
      // 去重追加（同一键值并列时的边界行可能在两页重复）
      const seen = new Set(filteredTasks.value.map((t) => t.id))
      const add = res.data.items.filter((t) => !seen.has(t.id))
      filteredTasks.value = [...filteredTasks.value, ...add]
      nextCursor = res.data.nextCursor
      loadedAll.value = !res.data.hasMore
    } finally {
      if (seq === fetchSeq) {
        loading.value = false
        onLoaded?.()
      }
    }
  }

  /** 全量拉取当前筛选结果（导出 / 跨页全选使用），不受分页限制 */
  async function fetchAllMatching(): Promise<TaskListItem[]> {
    const res = await listTasks({ ...buildTaskQuery(ctx), all: true })
    return res.data.items
  }

  return { loading, filteredTasks, loadedAll, stats, resetTick, fetchFiltered, loadMore, fetchAllMatching }
}
