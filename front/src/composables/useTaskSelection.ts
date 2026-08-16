// 勾选（跨分页全选当前筛选结果：全选时经 fetchAllMatching 拉全量 id）
import { computed, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import type { TaskListItem } from '../api/task'

export function useTaskSelection(params: {
  tasks: ComputedRef<TaskListItem[]>
  /** 当前筛选结果总数（来自后端统计聚合） */
  total: Ref<number>
  /** 全量拉取当前筛选结果（全选跨页用） */
  fetchAllMatching: () => Promise<TaskListItem[]>
}) {
  const { tasks, total, fetchAllMatching } = params

  const selectedIds = ref(new Set<number>())
  const selectedCount = computed(() => selectedIds.value.size)
  const someSelected = computed(() => selectedIds.value.size > 0 && selectedIds.value.size < total.value)
  const allSelected = computed(() => total.value > 0 && selectedIds.value.size >= total.value)
  const selectingAll = ref(false)

  function toggleRow(id: number, checked: boolean) {
    if (checked) selectedIds.value.add(id)
    else selectedIds.value.delete(id)
  }

  /** 全选/取消：全选需拉取当前筛选全部 id（跨分页）；失败时退回已加载范围 */
  async function toggleAllRows(checked: boolean) {
    if (!checked) {
      selectedIds.value = new Set()
      return
    }
    if (selectingAll.value) return
    selectingAll.value = true
    try {
      const all = await fetchAllMatching()
      selectedIds.value = new Set(all.map((t) => t.id))
    } catch {
      selectedIds.value = new Set(tasks.value.map((t) => t.id))
    } finally {
      selectingAll.value = false
    }
  }

  return { selectedIds, selectedCount, someSelected, allSelected, selectingAll, toggleRow, toggleAllRows }
}
