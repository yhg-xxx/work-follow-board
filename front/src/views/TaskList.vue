<script setup lang="ts">
import {computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import ExcelJS from 'exceljs'
import type {MenuGroupStat, MenuStats, TaskDetail, TaskImportItem, TaskImportRequest, TaskListItem} from '../api/task'
import {
  deleteTask,
  getTask,
  listTasks,
  menuStats as fetchMenuStatsApi,
  suggestOwners,
} from '../api/task'
import { logOp } from '../composables/useOpLog'
import SidebarNav from '../components/SidebarNav.vue'
import FilterPanel from '../components/FilterPanel.vue'
import EditorDialog from '../components/EditorDialog.vue'
import ImportDialog from '../components/ImportDialog.vue'
import FollowPanel from '../components/FollowPanel.vue'
import {
  GROUP_META,
  MAIN_COLUMNS,
  boardLabel,
  dateTag,
  downloadBlob,
  errMsg,
  fmtISODate,
} from '../utils/taskShared'
import type { Filters, MenuGroup, NavGroupId } from '../utils/taskShared'

const PAGE_SIZE = 20

// ---------- 当前选择：navGroupId 决定 boards + 临时筛选；navModule 决定模块筛选（null=全部） ----------
interface NavState {
  group: NavGroupId
  module: string | null
}
const navState = reactive<NavState>({
  group: 'all',
  module: null,
})

// ---------- 左侧菜单栏：收起 / 展开 ----------
const sidebarCollapsed = ref(false)

// ---------- 左侧菜单数据：看板分组 + 临时聚合（来自后端聚合接口，不再全量拉取） ----------
const menuStatsData = ref<MenuStats | null>(null)
const menuGroups = computed<MenuGroup[]>(() => {
  const data = menuStatsData.value
  if (!data) return []
  const findGroup = (id: string): MenuGroupStat | undefined =>
    id === 'temp' ? data.temp : data.groups.find((g) => g.id === id)
  return (['quanfa', 'happy', 'temp'] as NavGroupId[]).map((id) => {
    const g = findGroup(id)
    return {
      id,
      ...GROUP_META[id],
      count: g?.count ?? 0,
      modules: g?.modules ?? [],
    }
  })
})
const navAllCount = computed(() => menuStatsData.value?.allCount ?? 0)
const expandedMenus = ref<Set<NavGroupId>>(new Set(['quanfa', 'happy']))
function toggleMenuExpand(gid: NavGroupId) {
  if (expandedMenus.value.has(gid)) expandedMenus.value.delete(gid)
  else expandedMenus.value.add(gid)
}
function pickNavGroup(gid: NavGroupId) {
  navState.group = gid
  navState.module = null
  if (!expandedMenus.value.has(gid)) expandedMenus.value.add(gid)
}
function pickNavModule(gid: NavGroupId, mod: string | null) {
  navState.group = gid
  navState.module = mod
  if (!expandedMenus.value.has(gid)) expandedMenus.value.add(gid)
}
function pickAll() {
  navState.group = 'all'
  navState.module = null
}

const statusDot = (s: string) => {
  switch (s) {
    case '已完成': return 'st-done'
    case '进行中': return 'st-ongoing'
    case '亟待解决': return 'st-urgent'
    case '持续跟进': return 'st-follow'
    default: return 'st-idle'
  }
}
const priClass = (p: string) => (p === '高' ? 'pri-high' : p === '中' ? 'pri-mid' : 'pri-low')
// 卡片身份色：模块名含「临时」→ 紫，会幸福 → 橙，全发 → 蓝（文件夹标签 / 底部脊线）
const boardKey = (t: Pick<TaskListItem, 'board' | 'module'>) =>
  t.module?.includes('临时') ? 'temp' : t.board === 'happy' ? 'happy' : 'quanfa'
const cardClass = (t: TaskListItem) => `b-${boardKey(t)}`
const riskIsStar = (r: string | null) => !!r && r.includes('★')
const fmtShort = (d: string | null) => {
  if (!d) return '—'
  const parts = d.split('-')
  return parts.length === 3 ? `${parts[1]}-${parts[2]}` : d
}
const fmtDate = (d: string | null) => d || '—'

function deadlineState(d: string | null) {
  if (!d) return ''
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const dd = new Date(d + 'T00:00:00')
  const diff = Math.round((dd.getTime() - today.getTime()) / 86400000)
  if (diff < 0) return 'dl-overdue'
  if (diff <= 7) return 'dl-near'
  return ''
}

// ---------- 筛选条件（看板/状态支持多选，全部前端过滤） ----------
const filters = reactive<{
  boards: string[]
  statuses: string[]
  owners: string[]
  keyword: string
  dateRange: string[]
}>({
  boards: ['quanfa', 'happy'],
  statuses: [],
  owners: [],
  keyword: '',
  dateRange: [],
})

const keywordInput = ref('')

function applyKeyword() {
  filters.keyword = keywordInput.value.trim()
}

// 负责人自动补全：走后端去重接口（用于筛选面板 el-select 远程搜索）
async function queryOwners(query: string) {
  try {
    const { data } = await suggestOwners(query || undefined)
    // 已选负责人需保留在候选中，避免远程刷新后已选 tag 丢失
    const selected = draftFilters.owners.filter((o) => !data.includes(o))
    ownerOptions.value = [...data.slice(0, 20), ...selected]
  } catch {
    ownerOptions.value = [...draftFilters.owners]
  }
}

const escHtml = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')

// 搜索关键词高亮（先转义防注入，再包 <mark>）
function hl(text: string | null | undefined): string {
  if (!text) return '—'
  const kw = filters.keyword.trim()
  if (!kw) return escHtml(text)
  const re = new RegExp(`(${kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi')
  return text
    .split(re)
    .map((p, i) => (i % 2 === 1 ? `<mark class="hl">${escHtml(p)}</mark>` : escHtml(p)))
    .join('')
}

// ---------- 统计条带 ----------
const stats = computed(() => {
  const list = tasks.value
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const t0 = today.getTime()
  const near = list.filter((t) => {
    if (!t.deadline) return false
    const diff = Math.round((new Date(t.deadline + 'T00:00:00').getTime() - t0) / 86400000)
    return diff >= 0 && diff <= 7
  }).length
  return {
    total: list.length,
    urgent: list.filter((t) => t.status === '亟待解决').length,
    ongoing: list.filter((t) => t.status === '进行中').length,
    high: list.filter((t) => t.priority === '高').length,
    near,
  }
})

// ---------- 列表数据 ----------
// filteredTasks：按当前导航 + 筛选条件请求后端得到，作为卡片数据源
const loading = ref(false)
const filteredTasks = ref<TaskListItem[]>([])
const pageCount = ref(PAGE_SIZE)

// ---------- 排序（截止日期走后端，优先级/更新时间走前端二次排序） ----------
type SortField = 'deadline' | 'priority' | 'updateDate'
const SORT_OPTIONS: { label: string; field: SortField; order: 'ascending' | 'descending' }[] = [
  { label: '截止日期 · 正序（先到先处理）', field: 'deadline', order: 'ascending' },
  { label: '截止日期 · 倒序（最远在前）', field: 'deadline', order: 'descending' },
  { label: '优先级 · 高→低', field: 'priority', order: 'descending' },
  { label: '更新时间 · 新→旧', field: 'updateDate', order: 'descending' },
]
const sortState = reactive<{ field: SortField; order: 'ascending' | 'descending' }>({
  field: 'deadline',
  order: 'ascending',
})
const currentSortLabel = computed(() => {
  const hit = SORT_OPTIONS.find((o) => o.field === sortState.field && o.order === sortState.order)
  return hit ? hit.label.split(' · ')[0] : '排序'
})

function changeSort(opt: { field: SortField; order: 'ascending' | 'descending' }) {
  sortState.field = opt.field
  sortState.order = opt.order
}

// 工具栏「重置」：回到默认状态（全部看板、无任何筛选、默认排序、清关键词搜索、清勾选）
function resetAll() {
  navState.group = 'all'
  navState.module = null
  filters.boards = ['quanfa', 'happy']
  filters.statuses = []
  filters.owners = []
  filters.keyword = ''
  filters.dateRange = []
  keywordInput.value = ''
  draftFilters.boards = ['quanfa', 'happy']
  draftFilters.statuses = []
  draftFilters.owners = []
  draftFilters.dateRange = []
  sortState.field = 'deadline'
  sortState.order = 'ascending'
  selectedIds.value.clear()
  closeFilterPanel()
}

// 优先级权重：高=3，中=2，低=1，其他=0
const priWeight = (p: string | null) =>
  p === '高' ? 3 : p === '中' ? 2 : p === '低' ? 1 : 0

// 当前展示列表：后端筛选 + deadline 默认后端排序；其他字段走前端稳定二次排序
const tasks = computed(() => {
  const list = filteredTasks.value
  if (sortState.field === 'deadline') return list
  const copy = [...list]
  const dir = sortState.order === 'ascending' ? 1 : -1
  if (sortState.field === 'priority') {
    copy.sort((a, b) => {
      const d = priWeight(a.priority) - priWeight(b.priority)
      if (d !== 0) return d * dir
      return (a.deadline ?? '').localeCompare(b.deadline ?? '')
    })
  } else if (sortState.field === 'updateDate') {
    copy.sort((a, b) => {
      const d = (a.updateDate ?? '').localeCompare(b.updateDate ?? '')
      if (d !== 0) return d * dir
      return (a.deadline ?? '').localeCompare(b.deadline ?? '')
    })
  }
  return copy
})

// ---------- 高级筛选悬浮面板（和跟进面板同风格，480px宽，贴高级筛选按钮右侧，放不下弹左侧） ----------
// 受控模式：面板 UI 在子组件 FilterPanel，草稿/定位/外点关闭等逻辑留在本组件
const FILTER_PANEL_W = 480
const filterPanelVisible = ref(false)
const filterPanelStyle = ref<Record<string, string>>({})
const filterBtnRef = ref<HTMLElement | null>(null)
const filterPanelRef = ref<{ el: HTMLElement | null } | null>(null)
const toolbarRef = ref<HTMLElement | null>(null)
const boardPageRef = ref<HTMLElement | null>(null)
const draftFilters = reactive<Filters>({
  boards: ['quanfa', 'happy'],
  statuses: [],
  owners: [],
  dateRange: [],
})
// 负责人远程搜索候选（el-select 多选远程模式）
const ownerOptions = ref<string[]>([])

function toggleFilterPanel() {
  if (filterPanelVisible.value) {
    closeFilterPanel()
    return
  }
  draftFilters.boards = [...filters.boards]
  draftFilters.statuses = [...filters.statuses]
  draftFilters.owners = [...filters.owners]
  draftFilters.dateRange = [...filters.dateRange]
  // 已选负责人需作为候选展示（el-select 值需在 options 中才显示 tag）
  ownerOptions.value = [...filters.owners]
  filterPanelVisible.value = true
  nextTick(() => positionFilterPanel())
}
function closeFilterPanel() {
  filterPanelVisible.value = false
}
function positionFilterPanel() {
  const host = boardPageRef.value
  const btn = filterBtnRef.value
  if (!host || !btn) return
  const hostRect = host.getBoundingClientRect()
  const btnRect = btn.getBoundingClientRect()
  const gap = 12
  const MIN_W = 360
  // 可用水平宽度：board-page 的右边界（考虑 padding）为限制
  const availRight = hostRect.right - btnRect.right - gap - 16 // 16 = right padding 预留
  const availLeft = btnRect.left - hostRect.left - gap - 16  // left padding 预留
  let left: number
  let width: number
  // 优先贴按钮右侧，右侧不够贴左侧，两侧都不够则自适应整宽
  if (availRight >= MIN_W) {
    width = Math.min(FILTER_PANEL_W, availRight)
    left = btnRect.right - hostRect.left + gap
  } else if (availLeft >= MIN_W) {
    width = Math.min(FILTER_PANEL_W, availLeft)
    left = btnRect.left - hostRect.left - gap - width
  } else {
    width = Math.min(FILTER_PANEL_W, hostRect.width - 32)
    left = 16
  }
  left = Math.max(16, left)
  // 纵向：以按钮顶部为基准，相对于 board-page 包含块
  const top = btnRect.top - hostRect.top
  const maxH = Math.max(320, window.innerHeight - (btnRect.top - hostRect.top) - 24)
  filterPanelStyle.value = {
    left: `${left}px`,
    top: `${top}px`,
    width: `${width}px`,
    maxHeight: `${maxH}px`,
  }
}
function applyDraftFilters() {
  filters.boards = [...draftFilters.boards]
  filters.statuses = [...draftFilters.statuses]
  filters.owners = [...draftFilters.owners]
  // keyword 由行内搜索框主导，面板中不展示 keyword，但保持 draft 值不覆盖（外部 keywordInput 同步）
  filters.dateRange = [...draftFilters.dateRange]
  closeFilterPanel()
}
function resetDraftFilters() {
  draftFilters.boards = ['quanfa', 'happy']
  draftFilters.statuses = []
  draftFilters.owners = []
  draftFilters.dateRange = []
}

// 点击面板外部 + 不在按钮上时关闭
// Element Plus 的下拉/日期/自动补全弹层默认 teleport 到 body，这些弹层内的交互也算面板内部，不能关
const EP_POPUP_SELECTORS = [
  '.el-select-dropdown',
  '.el-autocomplete-suggestion',
  '.el-picker-panel',
  '.el-date-picker',
  '.el-time-panel',
  '.el-popper',
  '.el-dropdown-menu',
  '.el-cascader__dropdown',
  '.el-color-picker__panel',
  '.el-transfer-panel',
]
function isInsideEpPopup(tgt: EventTarget | null): boolean {
  if (!tgt) return false
  let el: Element | null = (tgt as any).nodeType === 1 ? (tgt as Element) : (tgt as Element)?.parentElement ?? null
  while (el) {
    for (const sel of EP_POPUP_SELECTORS) {
      try {
        if (el.matches(sel)) return true
      } catch { /* IE-like fallback: skip */ }
    }
    el = el.parentElement
  }
  return false
}
function onDocClickForFilter(e: MouseEvent) {
  if (!filterPanelVisible.value) return
  const target = e.target as Node | null
  if (!target) return
  // 点在按钮上：由按钮自身 toggle 处理（已 return，避免双关）
  if (filterBtnRef.value && filterBtnRef.value.contains(target)) return
  // 点在面板内部：不关
  if (filterPanelRef.value?.el && filterPanelRef.value.el.contains(target)) return
  // —— 关键修复：点在 Element Plus teleport 到 body 的弹层上（本面板控件触发的），也不关 ——
  if (isInsideEpPopup(target)) return
  closeFilterPanel()
}
onMounted(() => document.addEventListener('mousedown', onDocClickForFilter))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocClickForFilter))

// 当前激活的筛选条件数量（用于按钮徽章）：4项，不含关键词（关键词走外部搜索框
const activeFilterCount = computed(() => {
  let n = 0
  const defaultBoards = ['quanfa', 'happy']
  const sameBoards =
    draftFilters.boards.length === defaultBoards.length &&
    draftFilters.boards.every((b) => defaultBoards.includes(b))
  if (!sameBoards) n++
  if (draftFilters.statuses.length) n++
  if (draftFilters.owners.length) n++
  if (draftFilters.dateRange.length === 2 && draftFilters.dateRange[0] && draftFilters.dateRange[1]) n++
  return n
})
const appliedFilterCount = computed(() => {
  let n = 0
  const defaultBoards = ['quanfa', 'happy']
  const sameBoards =
    filters.boards.length === defaultBoards.length &&
    filters.boards.every((b) => defaultBoards.includes(b))
  if (!sameBoards) n++
  if (filters.statuses.length) n++
  if (filters.owners.length) n++
  if (filters.dateRange.length === 2 && filters.dateRange[0] && filters.dateRange[1]) n++
  return n
})

const visibleTasks = computed(() => tasks.value.slice(0, pageCount.value))
const loadedAll = computed(() => !tasks.value.length || visibleTasks.value.length >= tasks.value.length)

// ---------- 勾选（跨分页全选当前筛选结果） ----------
const selectedIds = ref(new Set<number>())
const selectedCount = computed(() => selectedIds.value.size)
const someSelected = computed(() => selectedIds.value.size > 0 && selectedIds.value.size < tasks.value.length)
const allSelected = computed(() => tasks.value.length > 0 && selectedIds.value.size === tasks.value.length)

function toggleRow(id: number, checked: boolean) {
  if (checked) selectedIds.value.add(id)
  else selectedIds.value.delete(id)
}

function toggleAllRows(checked: boolean) {
  selectedIds.value = new Set(checked ? tasks.value.map((t) => t.id) : [])
}

watch(tasks, () => {
  pageCount.value = PAGE_SIZE
  selectedIds.value.clear()
})

function loadMore() {
  if (loading.value || loadedAll.value) return
  pageCount.value += PAGE_SIZE
}

// 卡片网格底部哨兵：进入视口即加载下一页（无限滚动）
const gridSentinel = ref<HTMLElement | null>(null)
let sentinelObserver: IntersectionObserver | null = null
function setupSentinel() {
  const el = gridSentinel.value
  if (!el) return
  sentinelObserver?.disconnect()
  sentinelObserver = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting) loadMore()
    },
    { rootMargin: '240px 0px' },
  )
  sentinelObserver.observe(el)
}
function teardownSentinel() {
  sentinelObserver?.disconnect()
  sentinelObserver = null
}

// 首屏不足一屏时自动补载，直到撑满可视区
function fillViewport(iter = 0) {
  if (iter > 3 || loadedAll.value) return
  nextTick(() => {
    const scrollEl = document.querySelector('.cards-scroll') as HTMLElement | null
    if (!scrollEl) return
    if (scrollEl.scrollHeight <= scrollEl.clientHeight + 1) {
      pageCount.value += PAGE_SIZE
      fillViewport(iter + 1)
    }
  })
}

// 筛选/排序条件变化 → 重新请求后端（关键词仅在回车/点击搜索时提交，无需防抖）
const filterSignature = computed(() =>
  JSON.stringify({
    g: navState.group,
    m: navState.module,
    b: filters.boards,
    s: filters.statuses,
    o: filters.owners,
    k: filters.keyword,
    d: filters.dateRange,
    sf: sortState.field,
    so: sortState.order,
  }),
)
watch(filterSignature, () => fetchFiltered())

// 根据当前导航 + 筛选栏构造后端查询参数
function buildQuery(): Record<string, unknown> {
  const params: Record<string, unknown> = {}

  // 看板：导航分组 boardFilters 与筛选栏 boards 同时约束（任一方为空表示不约束该方）
  // 双方非空且无交集时用哨兵值匹配空集，保持与原前端交集逻辑一致
  const navGroup = navState.group !== 'all' ? menuGroups.value.find((m) => m.id === navState.group) : null
  const navBoards = navGroup?.boardFilters ?? []
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

  // 工作模块：点击具体模块 → 该模块精确匹配；临时分组未选模块 → 全部含「临时」的模块名
  let modules: string[] = []
  if (navState.module) {
    modules = [navState.module]
  } else if (navState.group === 'temp') {
    const tempGroup = menuGroups.value.find((m) => m.id === 'temp')
    modules = tempGroup ? tempGroup.modules.map((m) => m.name) : []
  }
  if (modules.length) params.module = modules

  if (filters.owners.length) params.owner = filters.owners
  if (filters.keyword.trim()) params.keyword = filters.keyword.trim()
  const [df, dt] = filters.dateRange
  if (df) params.deadlineFrom = df
  if (dt) params.deadlineTo = dt
  // 排序：仅当字段为 deadline 时交由后端按 deadline 处理；其他字段走前端二次排序
  if (sortState.field === 'deadline') {
    params.sortOrder = sortState.order === 'ascending' ? 'asc' : 'desc'
  }
  return params
}

async function fetchMenuStats() {
  try {
    menuStatsData.value = (await fetchMenuStatsApi()).data
  } catch {
    /* 忽略：菜单计数失败不影响表格使用 */
  }
}

async function fetchFiltered() {
  loading.value = true
  try {
    filteredTasks.value = (await listTasks(buildQuery())).data
  } finally {
    loading.value = false
    fillViewport()
  }
}

function reload() {
  // 数据变更后，菜单统计与筛选结果都需要刷新
  fetchMenuStats()
  fetchFiltered()
}

// ---------- 新建/编辑（弹窗逻辑在子组件 EditorDialog，父组件用 ref 调用） ----------
const editorRef = ref<InstanceType<typeof EditorDialog> | null>(null)
// ---------- 导入（弹窗逻辑在子组件 ImportDialog，父组件用 ref 调用） ----------
const importRef = ref<InstanceType<typeof ImportDialog> | null>(null)
const importing = ref(false)

// ---------- 批量删除 ----------
async function deleteSelected() {
  if (!selectedIds.value.size) return
  try {
    await ElMessageBox.confirm(
      `确认删除选中的 ${selectedIds.value.size} 项事项？删除后不可恢复。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  const ids = [...selectedIds.value]
  try {
    for (const id of ids) await deleteTask(id)
    ElMessage.success(`已删除 ${ids.length} 项`)
    logOp({ action: 'DELETE', targetType: 'task', detail: `批量删除 ${ids.length} 项事项` })
    reload()
  } catch (err: any) {
    ElMessage.error('删除失败：' + errMsg(err))
    reload()
  }
}

// ---------- 导出：当前要导出的行（勾选优先，否则当前筛选） ----------
const rowsForExport = computed<TaskListItem[]>(() =>
  selectedIds.value.size > 0
    ? tasks.value.filter((t) => selectedIds.value.has(t.id))
    : tasks.value,
)
const exportRowsHint = computed(() => {
  const n = rowsForExport.value.length
  if (selectedIds.value.size > 0) return `将导出 ${n} 条（勾选 ${selectedIds.value.size} 条）`
  if (tasks.value.length === navAllCount.value || !navAllCount.value) return `将导出 ${n} 条（当前筛选）`
  return `将导出 ${n} 条（当前筛选，全库 ${navAllCount.value} 条）`
})

// ---------- 导出：Excel (多 sheet + 样式) ----------
const exporting = ref(false)
async function exportExcel() {
  const rows = rowsForExport.value
  if (!rows.length) {
    ElMessage.warning('没有可导出的数据')
    return
  }
  exporting.value = true
  try {
    const wb = new ExcelJS.Workbook()
    wb.creator = 'TMO 工作跟进看板'
    wb.created = new Date()

    // --- Sheet1：事项 ---
    const ws = wb.addWorksheet('事项')
    ws.columns = MAIN_COLUMNS.map((c) => ({ header: c.label, key: c.key as string, width: c.width }))
    // 表头样式：加粗 + 灰底
    ws.getRow(1).font = { bold: true, size: 11 }
    ws.getRow(1).fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFF2F3F5' } }
    ws.getRow(1).alignment = { vertical: 'middle', wrapText: true }
    ws.views = [{ state: 'frozen', ySplit: 1 }]

    const today = new Date()
    const deadlineColIdx = MAIN_COLUMNS.findIndex((c) => c.key === 'deadline') + 1

    for (const t of rows) {
      const row: Record<string, unknown> = {
        taskCode: t.taskCode ?? '',
        board: boardLabel(t.board),
        module: t.module ?? '',
        title: t.title,
        description: t.description ?? '',
        status: t.status,
        priority: t.priority,
        owner: t.owner ?? '',
        collab: t.collab ?? '',
        pain: t.pain ?? '',
        nextStep: t.nextStep ?? '',
        deadline: t.deadline ? fmtISODate(t.deadline) : '',
        risk: t.risk ?? '',
        subItemsText: (t.subItems ?? []).join('；'),
        updateDate: t.updateDate ? fmtISODate(t.updateDate) : '',
      }
      const r = ws.addRow(row)
      r.alignment = { vertical: 'top', wrapText: true }
      // 截止日期：逾期红，7日内黄
      if (t.deadline && deadlineColIdx > 0) {
        const d = new Date(t.deadline.slice(0, 10))
        const diffDays = Math.ceil((d.getTime() - today.getTime()) / 86400000)
        const cell = r.getCell(deadlineColIdx)
        if (diffDays < 0) {
          cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFFFE5E5' } }
          cell.font = { color: { argb: 'FFDC2626' } }
        } else if (diffDays <= 7) {
          cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFFFF3CD' } }
          cell.font = { color: { argb: 'FFA16207' } }
        }
      }
    }

    // --- Sheet2：跟进记录（需要拉详情） ---
    const ws2 = wb.addWorksheet('跟进记录')
    ws2.columns = [
      { header: '事项ID', key: 'taskCode', width: 14 },
      { header: '具体事项', key: 'title', width: 36 },
      { header: '日期', key: 'date', width: 14 },
      { header: '跟进人', key: 'person', width: 12 },
      { header: '摘要', key: 'summary', width: 50 },
      { header: '下一步', key: 'next', width: 30 },
    ]
    ws2.getRow(1).font = { bold: true, size: 11 }
    ws2.getRow(1).fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFF2F3F5' } }
    ws2.getRow(1).alignment = { vertical: 'middle', wrapText: true }
    ws2.views = [{ state: 'frozen', ySplit: 1 }]

    // 批量拉详情：只对有 logCount > 0 的事项拉详情，省请求
    const needLogs = rows.filter((t) => (t.logCount ?? 0) > 0)
    if (needLogs.length) {
      const details = await Promise.all(needLogs.map((t) => getTask(t.id).then((r) => r.data).catch(() => null as TaskDetail | null)))
      for (const d of details) {
        if (!d || !d.logs?.length) continue
        for (const l of d.logs) {
          const r = ws2.addRow({
            taskCode: d.taskCode ?? '',
            title: d.title,
            date: l.logDate ? fmtISODate(l.logDate) : '',
            person: l.person ?? '',
            summary: l.summary ?? '',
            next: l.nextStep ?? '',
          })
          r.alignment = { vertical: 'top', wrapText: true }
        }
      }
    }

    const buf = await wb.xlsx.writeBuffer()
    const blob = new Blob([buf], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    downloadBlob(blob, `工作跟进看板_${dateTag()}.xlsx`)
    ElMessage.success(`已导出 Excel，共 ${rows.length} 条${needLogs.length ? `（含 ${needLogs.length} 条事项的跟进记录）` : ''}`)
    logOp({ action: 'EXPORT', detail: `导出 Excel，共 ${rows.length} 条${needLogs.length ? `（含 ${needLogs.length} 条事项的跟进记录）` : ''}` })
  } catch (err: any) {
    ElMessage.error('导出 Excel 失败：' + (err?.message || '未知错误'))
  } finally {
    exporting.value = false
  }
}

// ---------- 导出：CSV（全字段，同 xlsx 主 sheet） ----------
function exportCSV() {
  const rows = rowsForExport.value
  if (!rows.length) {
    ElMessage.warning('没有可导出的数据')
    return
  }
  const headers = MAIN_COLUMNS.map((c) => c.label)
  const esc = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`
  const lines = [
    headers.join(','),
    ...rows.map((t) =>
      MAIN_COLUMNS.map((c) => {
        let v: unknown
        switch (c.key) {
          case 'taskCode': v = t.taskCode ?? ''; break
          case 'board': v = boardLabel(t.board); break
          case 'module': v = t.module ?? ''; break
          case 'title': v = t.title; break
          case 'description': v = t.description ?? ''; break
          case 'status': v = t.status; break
          case 'priority': v = t.priority; break
          case 'owner': v = t.owner ?? ''; break
          case 'collab': v = t.collab ?? ''; break
          case 'pain': v = t.pain ?? ''; break
          case 'nextStep': v = t.nextStep ?? ''; break
          case 'deadline': v = t.deadline ? fmtISODate(t.deadline) : ''; break
          case 'risk': v = t.risk ?? ''; break
          case 'subItemsText': v = (t.subItems ?? []).join('；'); break
          case 'updateDate': v = t.updateDate ? fmtISODate(t.updateDate) : ''; break
          default: v = ''
        }
        return esc(v)
      }).join(','),
    ),
  ]
  const blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' })
  downloadBlob(blob, `工作跟进看板_${dateTag()}.csv`)
  ElMessage.success(`已导出 CSV，共 ${rows.length} 条`)
  logOp({ action: 'EXPORT', detail: `导出 CSV，共 ${rows.length} 条` })
}

// ---------- 导出：备份 JSON（全库，格式对称于 {data:{quanfa[],happy[]}}，可直接回导） ----------
async function exportBackupJSON() {
  exporting.value = true
  try {
    // 1. 拉全量列表
    const { data: all } = await listTasks({})
    if (!all.length) {
      ElMessage.warning('当前没有任何数据可备份')
      return
    }
    // 2. 拉每条详情拿 logs（有 logCount 才拉）
    const needLogs = all.filter((t) => (t.logCount ?? 0) > 0)
    const detailMap = new Map<number, TaskDetail>()
    if (needLogs.length) {
      const ds = await Promise.all(needLogs.map((t) => getTask(t.id).then((r) => r.data).catch(() => null as TaskDetail | null)))
      for (const d of ds) if (d) detailMap.set(d.id, d)
    }
    // 3. 映射到 {quanfa, happy} → 对齐 TaskImportItem
    const toImportItem = (t: TaskListItem): TaskImportItem => {
      const d = detailMap.get(t.id)
      return {
        id: t.taskCode ?? undefined,
        module: t.module ?? undefined,
        item: t.title,
        status: t.status,
        priority: t.priority,
        owner: t.owner ?? undefined,
        collab: t.collab ?? undefined,
        pain: t.pain ?? undefined,
        next: t.nextStep ?? undefined,
        deadline: t.deadline ? fmtISODate(t.deadline) : undefined,
        risk: t.risk ?? undefined,
        subItems: t.subItems && t.subItems.length ? t.subItems : undefined,
        logs: d?.logs?.length
          ? d.logs.map((l) => ({
              date: l.logDate ? fmtISODate(l.logDate) : null,
              person: l.person ?? undefined,
              summary: l.summary ?? '',
              next: l.nextStep ?? undefined,
            }))
          : undefined,
        updateDate: t.updateDate ? fmtISODate(t.updateDate) : undefined,
      }
    }
    const payload: TaskImportRequest = {
      quanfa: all.filter((t) => t.board === 'quanfa').map(toImportItem),
      happy: all.filter((t) => t.board === 'happy').map(toImportItem),
    }
    const blob = new Blob([JSON.stringify({ data: payload }, null, 2)], { type: 'application/json;charset=utf-8;' })
    downloadBlob(blob, `工作跟进看板_备份_${dateTag()}.json`)
    ElMessage.success(`已备份 JSON，共 ${all.length} 条（全发 ${payload.quanfa.length} + 会幸福 ${payload.happy.length}）`)
    logOp({ action: 'EXPORT', detail: `导出备份 JSON，共 ${all.length} 条（全发 ${payload.quanfa.length} + 会幸福 ${payload.happy.length}）` })
  } catch (err: any) {
    ElMessage.error('备份 JSON 失败：' + (err?.message || '未知错误'))
  } finally {
    exporting.value = false
  }
}

// ---------- 卡片跟进面板（悬浮于卡片右侧，放不下时弹左侧；一次只开一张） ----------
// 展开 id 由父组件控制；面板详情/定位/增删记录逻辑在子组件 FollowPanel
const expandedId = ref<number | null>(null)
async function togglePanel(row: TaskListItem) {
  if (expandedId.value === row.id) {
    expandedId.value = null
    return
  }
  expandedId.value = row.id
}
function onLogCountSync(payload: { id: number; logCount: number }) {
  const card = filteredTasks.value.find((t) => t.id === payload.id)
  if (card) card.logCount = payload.logCount
}

// 收起/打开侧栏：侧栏宽度变化会改变卡片区域可视宽度，已打开的跟进面板由子组件 ResizeObserver 自动重新定位
function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

onMounted(() => {
  fetchMenuStats() // 侧边栏菜单计数 / 底部统计
  fetchFiltered()  // 卡片数据：按当前筛选请求后端
  nextTick(setupSentinel)
})
onBeforeUnmount(() => {
  teardownSentinel()
})
</script>

<template>
  <div class="board-shell">
    <!-- 左侧：主导航菜单栏（三个一级分组 + 二级模块），逻辑在子组件 SidebarNav -->
    <SidebarNav
      :collapsed="sidebarCollapsed"
      :nav-all-count="navAllCount"
      :menu-groups="menuGroups"
      :expanded-menus="expandedMenus"
      :current-group="navState.group"
      :current-module="navState.module"
      @toggle-collapse="toggleSidebar"
      @pick-all="pickAll"
      @pick-group="pickNavGroup"
      @pick-module="pickNavModule"
      @toggle-menu="toggleMenuExpand"
    />

    <!-- 右侧：看板主区域（统计 + 筛选 + 表格） -->
    <section class="board-page" ref="boardPageRef">
      <!-- 统计条带 -->
      <section class="stat-strip card">
        <div class="stat">
          <span class="stat-num num st-ink">{{ stats.total }}</span>
          <span class="stat-label">总事项</span>
        </div>
        <div class="stat">
          <span class="stat-num num st-urgent">{{ stats.urgent }}</span>
          <span class="stat-label">亟待解决</span>
        </div>
        <div class="stat">
          <span class="stat-num num st-ongoing">{{ stats.ongoing }}</span>
          <span class="stat-label">进行中</span>
        </div>
        <div class="stat">
          <span class="stat-num num st-high">{{ stats.high }}</span>
          <span class="stat-label">高优先级</span>
        </div>
        <div class="stat">
          <span class="stat-num num st-follow">{{ stats.near }}</span>
          <span class="stat-label">7 日内到期</span>
        </div>
      </section>

      <!-- 单行工具栏：搜索 + 排序（左） + 高级筛选（按钮在筛选左侧排序右侧→面板弹在筛选按钮右侧） —— 弹性 —— 导入/导出/新建/全选/删除 -->
      <section class="toolbar card" ref="toolbarRef">
        <div class="toolbar-left">
          <div class="search-box">
            <el-input
              v-model="keywordInput"
              placeholder="搜索事项ID/名称/工作模块..."
              clearable
              style="width: 260px"
              @keyup.enter="applyKeyword"
              @clear="applyKeyword"
            />
            <el-button type="primary" @click="applyKeyword">搜索</el-button>
          </div>
          <el-dropdown trigger="click" @command="(cmd: number) => { const o = SORT_OPTIONS[cmd]; if (o) changeSort(o); }">
            <el-button plain class="btn-sort">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h13M3 12h9M3 18h5M17 8l4 4-4 4"/></svg>
              {{ currentSortLabel }}
              <i class="caret">▾</i>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="(o, i) in SORT_OPTIONS"
                  :key="i"
                  :command="i"
                  :disabled="o.field === sortState.field && o.order === sortState.order"
                >
                  <span class="sort-check">
                    {{ o.field === sortState.field && o.order === sortState.order ? '✓' : '' }}
                  </span>
                  {{ o.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span ref="filterBtnRef" class="filter-btn-wrap">
            <el-badge
              :value="appliedFilterCount"
              :hidden="appliedFilterCount === 0"
              :max="9"
              class="filter-badge"
            >
              <el-button
                plain
                class="btn-filter"
                :class="{ active: appliedFilterCount > 0, on: filterPanelVisible }"
                @click.stop="toggleFilterPanel"
              >
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/></svg>
                高级筛选
              </el-button>
            </el-badge>
          </span>
          <el-button plain class="btn-reset" @click="resetAll">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>
            重置
          </el-button>
        </div>
        <div class="spacer" />
        <div class="toolbar-actions">
          <el-button plain :loading="importing" @click="importRef?.open()">导入</el-button>
          <el-dropdown trigger="click" @command="(cmd: string) => { if (cmd === 'xlsx') exportExcel(); else if (cmd === 'csv') exportCSV(); else if (cmd === 'json') exportBackupJSON(); }">
            <el-button plain :loading="exporting" class="btn-export">
              导出
              <i class="caret">▾</i>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="xlsx">
                  <span style="display:inline-flex;align-items:center;gap:6px;">📊 导出 Excel (.xlsx)</span>
                </el-dropdown-item>
                <el-dropdown-item command="csv">
                  <span style="display:inline-flex;align-items:center;gap:6px;">📄 导出 CSV</span>
                </el-dropdown-item>
                <el-dropdown-item command="json">
                  <span style="display:inline-flex;align-items:center;gap:6px;">💾 备份 JSON（全库）</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span class="export-hint">{{ exportRowsHint }}</span>
          <el-button type="primary" @click="editorRef?.open()">＋ 新建事项</el-button>
          <el-checkbox
            class="toolbar-select-all"
            :model-value="allSelected"
            :indeterminate="someSelected"
            :disabled="!tasks.length"
            @change="toggleAllRows"
          >
            全选
          </el-checkbox>
          <el-button type="danger" plain :disabled="!selectedCount" @click="deleteSelected">
            删除{{ selectedCount ? `（${selectedCount}）` : '' }}
          </el-button>
        </div>
      </section>

      <!-- 高级筛选悬浮面板（受控组件：草稿/定位/外点关闭在父组件） -->
      <FilterPanel
        v-if="filterPanelVisible"
        ref="filterPanelRef"
        :draft-filters="draftFilters"
        :owner-options="ownerOptions"
        :panel-style="filterPanelStyle"
        :active-count="activeFilterCount"
        :remote-query="queryOwners"
        @apply="applyDraftFilters"
        @reset="resetDraftFilters"
        @close="closeFilterPanel"
      />

      <!-- 卡片视图：文件夹卡片 + 卡片跟进面板（仅此区域滚动加载） -->
      <section class="cards-region" v-loading="loading">
        <div class="cards-scroll">
          <div class="cards-grid">
            <article
              v-for="t in visibleTasks"
              :key="t.id"
              class="task-card"
              :class="cardClass(t)"
              :data-id="t.id"
            >
              <span
                class="card-tab"
                :class="cardClass(t)"
                :title="t.board === 'happy' ? '会幸福' : t.module?.includes('临时') ? '临时事项' : '全发'"
              >{{ t.taskCode || '——' }}</span>

              <header class="card-head">
                <h3 class="card-title" v-html="hl(t.title)" />
                <el-checkbox
                  class="card-check"
                  :model-value="selectedIds.has(t.id)"
                  @click.stop
                  @change="(v: boolean | string | number) => toggleRow(t.id, !!v)"
                />
              </header>

              <div class="card-chips">
                <!-- 状态：只读展示，修改需进编辑弹窗 -->
                <span class="status-chip" :class="statusDot(t.status)">
                  <i class="dot" />{{ t.status }}
                </span>
                <span class="pri-pill" :class="priClass(t.priority)">{{ t.priority }}</span>
                <span class="card-owner">{{ t.owner || '未指派' }}</span>
              </div>

              <p v-if="t.module" class="card-module" v-html="hl(t.module)" />

              <!-- 除跟进记录外的其余字段 -->
              <dl class="card-lines">
                <div v-if="t.description" class="card-line">
                  <dt>描述</dt>
                  <dd v-html="hl(t.description)" />
                </div>
                <div v-if="t.collab" class="card-line card-line-1">
                  <dt>协作</dt>
                  <dd>{{ t.collab }}</dd>
                </div>
                <div v-if="t.pain" class="card-line">
                  <dt>痛点</dt>
                  <dd v-html="hl(t.pain)" />
                </div>
                <div v-if="t.nextStep" class="card-line">
                  <dt>下一步</dt>
                  <dd v-html="hl(t.nextStep)" />
                </div>
                <div v-if="t.risk" class="card-line">
                  <dt>风险</dt>
                  <dd :class="{ 'risk-star': riskIsStar(t.risk) }" v-html="hl(t.risk)" />
                </div>
                <div v-if="t.subItems?.length" class="card-line card-line-1">
                  <dt>子项</dt>
                  <dd class="card-sub-list">
                    <span v-for="(s, i) in t.subItems" :key="i" class="sub-tag">{{ s }}</span>
                  </dd>
                </div>
              </dl>

              <footer class="card-foot">
                <span v-if="t.updateDate" class="card-field" :title="'更新日期 ' + fmtDate(t.updateDate)">
                  <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
                  更新 {{ fmtShort(t.updateDate) }}
                </span>
                <span class="card-field" :title="'截止日期 ' + fmtDate(t.deadline)">
                  <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>
                  <span class="deadline num" :class="deadlineState(t.deadline)">{{ fmtShort(t.deadline) }}</span>
                </span>
                <button
                  type="button"
                  class="card-open"
                  :class="[{ has: (t.logCount ?? 0) > 0 }, { on: expandedId === t.id }]"
                  :aria-expanded="expandedId === t.id"
                  @click.stop="togglePanel(t)"
                >
                  <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-5 4V5z"/><path d="M8 9h8M8 13h5"/></svg>
                  跟进 {{ t.logCount ?? 0 }} 条 ›
                </button>
                <button class="card-edit" @click.stop="editorRef?.open(t)">编辑</button>
              </footer>
            </article>
          </div>

          <!-- 卡片跟进面板：悬浮于所点卡片右侧（放不下时弹左侧）；详情/定位/增删记录在子组件 -->
          <!-- :key 保证切换展开卡片时重建组件（onMounted 重新拉详情并定位） -->
          <FollowPanel
            v-if="expandedId !== null"
            :key="expandedId"
            :task-id="expandedId"
            @close="expandedId = null"
            @sync-count="onLogCountSync"
          />

          <div ref="gridSentinel" class="grid-sentinel" aria-hidden="true" />

          <div v-if="!loading && !tasks.length" class="empty-overlay">
            <el-empty description="没有匹配的事项，试试调整筛选条件" :image-size="80" />
          </div>
        </div>

        <div class="load-more">
          <template v-if="!tasks.length">—</template>
          <template v-else-if="loading">加载中…</template>
          <template v-else-if="loadedAll">已加载全部 {{ tasks.length }} 项</template>
          <template v-else>继续下滑加载更多 · 已加载 {{ visibleTasks.length }} / {{ tasks.length }} 项</template>
        </div>
      </section>

      <!-- 新建/编辑 Dialog（台账登记卡，逻辑在子组件 EditorDialog） -->
      <EditorDialog ref="editorRef" @saved="reload" />
      <!-- 导入预览 Dialog（逻辑在子组件 ImportDialog） -->
      <ImportDialog ref="importRef" v-model:importing="importing" :total-count="navAllCount" @done="reload" />
    </section>
    <!-- end board-page (right column) -->
  </div>
  <!-- end board-shell -->
</template>

<style scoped>
/* ---------- 分栏外壳：左菜单栏 + 右看板内容 ---------- */
.board-shell {
  display: flex;
  flex-direction: row;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: var(--c-bg);
}

/* 右侧：看板主内容 */
.board-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  padding: 14px 24px 12px;
  /* 高级筛选悬浮面板的定位包含块（面板作为同级子元素，绝对定位不被 overflow 裁剪） */
  position: relative;
}
.card {
  flex: 0 0 auto;
  background: var(--c-card);
  border: 1px solid var(--c-line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}

/* ---------- 统计条带 ---------- */
.stat-strip {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  overflow: hidden;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 20px;
  border-left: 1px solid var(--c-line);
}
.stat:first-child {
  border-left: none;
}
.stat-num {
  font-size: 23px;
  font-weight: 700;
  line-height: 1.2;
}
.stat-label {
  font-size: 12px;
  color: var(--c-muted);
}
.st-ink { color: var(--c-blue); }
.st-urgent { color: var(--c-st-urgent); }
.st-ongoing { color: var(--c-st-ongoing); }
.st-high { color: var(--c-pri-high); }
.st-follow { color: var(--c-st-follow); }

/* ---------- 单行工具栏：搜索 + 排序 + 高级筛选 —— 弹性 —— 导入/导出/新建/全选/删除 ---------- */
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  flex-wrap: nowrap;
  overflow-x: auto;
  /* 面板现在是 board-page 的同级子元素，不再需要 toolbar 作为定位上下文 */
}
.toolbar-left {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.filter-btn-wrap {
  display: inline-flex;
  align-items: center;
}
.search-box {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.spacer {
  flex: 1;
  min-width: 12px;
}
.toolbar-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

/* 工具栏按钮：图标 + 文字 风格 */
.btn-filter,
.btn-sort,
.btn-reset {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px !important;
  font-weight: 500;
  color: var(--c-ink-soft);
  border-color: var(--c-line-strong) !important;
  transition: all 0.18s ease;
}
.btn-filter svg,
.btn-sort svg,
.btn-reset svg {
  opacity: 0.8;
  flex-shrink: 0;
}
.btn-sort .caret {
  font-style: normal;
  font-size: 11px;
  margin-left: 2px;
  opacity: 0.7;
  line-height: 1;
}
.btn-filter:hover,
.btn-sort:hover,
.btn-reset:hover {
  color: var(--c-blue);
  border-color: var(--c-blue) !important;
}
.btn-filter:hover svg,
.btn-sort:hover svg,
.btn-reset:hover svg {
  opacity: 1;
}

/* 筛选激活状态：徽章变色 + 按钮高亮 + 面板打开时按下感 */
.filter-badge :deep(.el-badge__content) {
  background: var(--c-st-urgent, #ef4444);
  border-color: var(--c-st-urgent, #ef4444);
  font-weight: 600;
}
.btn-filter.active {
  background: color-mix(in srgb, var(--c-blue) 8%, var(--c-card));
  border-color: var(--c-blue) !important;
  color: var(--c-blue);
}
.btn-filter.active svg {
  opacity: 1;
}
.btn-filter.on {
  /* 面板打开时按钮保持按下高亮，视觉上与面板相连 */
  background: color-mix(in srgb, var(--c-blue) 14%, var(--c-card));
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.06);
}

/* 排序下拉菜单项：左侧勾选标记占位对齐 */
.sort-check {
  display: inline-block;
  width: 16px;
  text-align: center;
  color: var(--c-blue);
  font-weight: 700;
  margin-right: 2px;
}

/* ---------- 导出下拉按钮 + 条数提示 ---------- */
.btn-export {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px !important;
  font-weight: 500;
  color: var(--c-ink-soft);
  border-color: var(--c-line-strong) !important;
  transition: all 0.18s ease;
}
.btn-export .caret {
  font-style: normal;
  font-size: 11px;
  margin-left: 2px;
  opacity: 0.7;
  line-height: 1;
}
.btn-export:hover {
  color: var(--c-blue);
  border-color: var(--c-blue) !important;
}
.export-hint {
  font-size: 12px;
  color: var(--c-muted);
  line-height: 1;
  padding: 0 4px;
  flex-shrink: 0;
}

/* ---------- 卡片视图区域（唯一滚动区） ---------- */
.cards-region {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--c-card);
  border: 1px solid var(--c-line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
  position: relative;
}
.cards-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px 18px 8px;
  /* 跟进面板的定位上下文 */
  position: relative;
}
.cards-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px 16px;
}
.grid-sentinel {
  height: 1px;
}

/* 文件夹卡片：左上角身份色标签 + 底部身份色脊线 */
.task-card {
  --spine: transparent;
  --card-shadow: var(--shadow-card);
  position: relative;
  margin-top: 12px;
  padding: 16px 14px 12px;
  background: var(--c-card);
  border: 1px solid var(--c-line);
  border-radius: 10px;
  box-shadow: var(--card-shadow), inset 0 -3px 0 0 var(--spine);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: box-shadow 0.18s ease, transform 0.18s ease, border-color 0.18s ease;
}
.task-card.b-quanfa { --spine: var(--c-quanfa); }
.task-card.b-happy  { --spine: var(--c-happy); }
.task-card.b-temp   { --spine: var(--c-temp); }
.task-card:hover {
  --card-shadow: var(--shadow-card-hover);
  border-color: var(--c-line-strong);
  transform: translateY(-2px);
}

/* 文件夹标签：看板身份色 + 事项ID */
.card-tab {
  position: absolute;
  top: -12px;
  left: 14px;
  height: 24px;
  padding: 0 12px;
  border-radius: 8px 8px 4px 4px;
  display: inline-flex;
  align-items: center;
  font-family: var(--font-mono), serif;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #fff;
  box-shadow: inset 0 -2px 0 rgba(0, 0, 0, 0.16);
  user-select: none;
  white-space: nowrap;
}
.card-tab.b-quanfa { background: var(--c-quanfa); }
.card-tab.b-happy  { background: var(--c-happy); }
.card-tab.b-temp   { background: var(--c-temp); }
/* 深色主题下身份色偏亮，标签文字改用深色保证对比度 */
[data-theme='dark'] .card-tab { color: #0D1426; }

/* 卡片头部：标题 + 风险星 + 勾选 */
.card-head {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}
.card-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--c-ink);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 42px;
}
.card-check {
  flex: 0 0 auto;
  margin: -2px 0 0 2px;
}

/* 模块名 */
.card-module {
  font-size: 12px;
  color: var(--c-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 状态 / 优先级 / 负责人 */
.card-chips {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}
.card-owner {
  margin-left: auto;
  max-width: 42%;
  font-size: 12px;
  color: var(--c-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 卡片底部：更新/截止 / 跟进入口 / 编辑 */
.card-foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  row-gap: 4px;
  gap: 10px;
  padding-top: 9px;
  border-top: 1px dashed var(--c-line);
  font-size: 12px;
  color: var(--c-muted);
  /* 无论卡片中间字段（描述/协作/痛点等）是否缺失，操作区始终贴底 */
  margin-top: auto;
}
.card-field {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}
.card-field svg {
  flex: 0 0 auto;
  opacity: 0.75;
}
.card-open {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: transparent;
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-faint);
  white-space: nowrap;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 5px;
}
.card-open svg {
  flex: 0 0 auto;
}
.card-open:hover {
  background: var(--c-bg);
}
.card-open.has {
  color: var(--c-blue);
}
.card-open.on {
  color: var(--c-blue);
  background: var(--c-blue-soft);
}
.card-edit {
  flex: 0 0 auto;
  border: none;
  background: transparent;
  font-size: 12px;
  color: var(--c-muted);
  cursor: pointer;
  padding: 1px 6px;
  border-radius: 5px;
}
.card-edit:hover {
  color: var(--c-blue);
  background: var(--c-blue-soft);
}

/* 卡片字段行：标签 + 内容（除跟进记录外的全部字段） */
.card-lines {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.card-line {
  display: flex;
  gap: 6px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--c-ink-soft);
}
.card-line dt {
  flex: 0 0 auto;
  color: var(--c-faint);
  padding-top: 1px;
}
.card-line dd {
  flex: 1;
  min-width: 0;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-line dd.risk-star {
  color: var(--c-st-urgent);
  font-weight: 600;
}
.card-line-1 dd {
  -webkit-line-clamp: 1;
}
.card-sub-list {
  display: flex !important;
  flex-wrap: wrap;
  gap: 4px;
}

/* 工具栏：全选 */
.toolbar-select-all {
  margin-left: 4px;
}

/* 状态圆点（只读展示） */
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--c-ink-soft);
  padding: 2px 6px;
  border-radius: 6px;
  white-space: nowrap;
}
.status-chip .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-st-idle);
}
.status-chip.st-ongoing .dot { background: var(--c-st-ongoing); }
.status-chip.st-urgent .dot { background: var(--c-st-urgent); }
.status-chip.st-done .dot { background: var(--c-st-done); }
.status-chip.st-follow .dot { background: var(--c-st-follow); }

/* 优先级 */
.pri-pill {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
  white-space: nowrap;
}
.pri-high { color: var(--c-pri-high); background: var(--c-st-urgent-soft); }
.pri-mid { color: var(--c-pri-mid); background: var(--c-st-follow-soft); }
.pri-low { color: var(--c-pri-low); background: var(--c-st-idle-soft); }

.deadline {
  color: var(--c-muted);
  white-space: nowrap;
  font-size: 13px;
}
.deadline.dl-overdue { color: var(--c-st-urgent); font-weight: 600; }
.deadline.dl-near { color: var(--c-st-follow); font-weight: 600; }

/* 加载更多 / 空态 */
.load-more {
  flex: 0 0 auto;
  text-align: center;
  font-size: 12px;
  color: var(--c-muted);
  padding: 5px 0 7px;
  border-top: 1px solid var(--c-line);
  background: var(--c-card);
}
.empty-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--c-empty-veil);
  z-index: 5;
}

/* 子项标签（卡片子项展示） */
.sub-tag {
  font-size: 12px;
  color: var(--c-ink-soft);
  background: var(--c-bg);
  border: 1px solid var(--c-line);
  border-radius: 6px;
  padding: 1px 8px;
}

/* 中等宽度：4 列偏挤时降为 3 列 */
@media (max-width: 1280px) {
  .cards-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .cards-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px 12px;
  }
  .board-shell {
    flex-direction: column;
  }
  .board-page {
    padding: 12px 14px 10px;
    gap: 10px;
  }
  .stat-strip {
    grid-template-columns: repeat(2, 1fr);
  }
  .stat:nth-child(odd) {
    border-left: none;
  }
  .stat:nth-child(n+3) {
    border-top: 1px solid var(--c-line);
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    transition: none !important;
  }
}
</style>
