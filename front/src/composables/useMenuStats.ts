// 当前导航选择 + 左侧菜单统计（看板分组/模块，来自后端聚合接口）
import { computed, reactive, ref } from 'vue'
import type { MenuStats } from '../api/task'
import { menuStats as fetchMenuStatsApi } from '../api/task'
import type { MenuGroup, NavGroupId } from '../utils/taskShared'

// 当前选择：navGroupId 决定 boards；navModule 决定模块筛选（null=全部）
export interface NavState {
  group: NavGroupId
  module: string | null
}

export function useMenuStats() {
  const navState = reactive<NavState>({
    group: 'all',
    module: null,
  })

  const menuStatsData = ref<MenuStats | null>(null)
  const menuGroups = computed<MenuGroup[]>(() => {
    const data = menuStatsData.value
    if (!data) return []
    return data.groups.map((g) => ({
      id: g.id,
      label: g.label,
      accent: g.accent,
      prefix: g.prefix,
      boardFilters: [g.id],
      count: g.count,
      modules: g.modules ?? [],
    }))
  })
  const navAllCount = computed(() => menuStatsData.value?.allCount ?? 0)
  const expandedMenus = ref<Set<string>>(new Set(['quanfa', 'happy']))

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

  async function fetchMenuStats() {
    try {
      menuStatsData.value = (await fetchMenuStatsApi()).data
    } catch {
      /* 忽略：菜单计数失败不影响表格使用 */
    }
  }

  return {
    navState,
    menuGroups,
    navAllCount,
    expandedMenus,
    toggleMenuExpand,
    pickNavGroup,
    pickNavModule,
    pickAll,
    fetchMenuStats,
  }
}
