// 筛选条件（生效 + 草稿）、关键词搜索、负责人远程候选，以及高级筛选悬浮面板
// （受控模式：面板 UI 在子组件 FilterPanel，草稿/定位/外点关闭等逻辑留在本 composable）
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type { ComputedRef } from 'vue'
import { suggestOwners } from '../api/task'
import type { Filters } from '../utils/taskShared'
import { isInsideElementPlusPopup } from '../utils/dom'

const FILTER_PANEL_W = 480

export function useFilters(params: { allBoardCodes: ComputedRef<string[]> }) {
  const allBoardCodes = params.allBoardCodes

  // ---------- 筛选条件（看板/状态支持多选；boards 空数组=全部看板，加载后回填全部 code） ----------
  const filters = reactive<{
    boards: string[]
    statuses: string[]
    owners: string[]
    keyword: string
    dateRange: string[]
  }>({
    boards: [],
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
  const ownerOptions = ref<string[]>([])
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

  // ---------- 高级筛选悬浮面板（和跟进面板同风格，480px宽，贴高级筛选按钮右侧，放不下弹左侧） ----------
  const filterPanelVisible = ref(false)
  const filterPanelStyle = ref<Record<string, string>>({})
  const filterBtnRef = ref<HTMLElement | null>(null)
  const filterPanelRef = ref<{ el: HTMLElement | null } | null>(null)
  const boardPageRef = ref<HTMLElement | null>(null)
  const draftFilters = reactive<Filters>({
    boards: [],
    statuses: [],
    owners: [],
    dateRange: [],
  })

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
    draftFilters.boards = [...allBoardCodes.value]
    draftFilters.statuses = []
    draftFilters.owners = []
    draftFilters.dateRange = []
  }

  // 点击面板外部 + 不在按钮上时关闭
  // Element Plus 的下拉/日期/自动补全弹层默认 teleport 到 body，这些弹层内的交互也算面板内部，不能关
  function onDocClickForFilter(e: MouseEvent) {
    if (!filterPanelVisible.value) return
    const target = e.target as Node | null
    if (!target) return
    // 点在按钮上：由按钮自身 toggle 处理（已 return，避免双关）
    if (filterBtnRef.value && filterBtnRef.value.contains(target)) return
    // 点在面板内部：不关
    if (filterPanelRef.value?.el && filterPanelRef.value.el.contains(target)) return
    // —— 关键修复：点在 Element Plus teleport 到 body 的弹层上（本面板控件触发的），也不关 ——
    if (isInsideElementPlusPopup(target)) return
    closeFilterPanel()
  }
  onMounted(() => document.addEventListener('mousedown', onDocClickForFilter))
  onBeforeUnmount(() => document.removeEventListener('mousedown', onDocClickForFilter))

  // 当前激活的筛选条件数量（用于按钮徽章）：4项，不含关键词（关键词走外部搜索框
  const activeFilterCount = computed(() => {
    let n = 0
    const sameBoards =
      draftFilters.boards.length === allBoardCodes.value.length &&
      draftFilters.boards.every((b) => allBoardCodes.value.includes(b))
    if (!sameBoards) n++
    if (draftFilters.statuses.length) n++
    if (draftFilters.owners.length) n++
    if (draftFilters.dateRange.length === 2 && draftFilters.dateRange[0] && draftFilters.dateRange[1]) n++
    return n
  })
  const appliedFilterCount = computed(() => {
    let n = 0
    const sameBoards =
      filters.boards.length === allBoardCodes.value.length &&
      filters.boards.every((b) => allBoardCodes.value.includes(b))
    if (!sameBoards) n++
    if (filters.statuses.length) n++
    if (filters.owners.length) n++
    if (filters.dateRange.length === 2 && filters.dateRange[0] && filters.dateRange[1]) n++
    return n
  })

  return {
    filters,
    draftFilters,
    keywordInput,
    ownerOptions,
    filterPanelVisible,
    filterPanelStyle,
    filterBtnRef,
    filterPanelRef,
    boardPageRef,
    applyKeyword,
    queryOwners,
    toggleFilterPanel,
    closeFilterPanel,
    applyDraftFilters,
    resetDraftFilters,
    activeFilterCount,
    appliedFilterCount,
  }
}
