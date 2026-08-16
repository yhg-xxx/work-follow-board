// 卡片跟进面板：展开 id（一次只开一张）；条数同步回写卡片
// 面板详情/定位/增删记录逻辑在子组件 FollowPanel
import { ref } from 'vue'
import type { Ref } from 'vue'
import type { TaskListItem } from '../api/task'

export function useCardPanel(params: { filteredTasks: Ref<TaskListItem[]> }) {
  const { filteredTasks } = params

  const expandedId = ref<number | null>(null)
  function togglePanel(row: TaskListItem) {
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

  return { expandedId, togglePanel, onLogCountSync }
}
