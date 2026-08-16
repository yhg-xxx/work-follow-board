// 手动排序：拖拽（仅手动排序模式启用，退出即销毁）
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { ComputedRef } from 'vue'
import { ElMessage } from 'element-plus'
import Sortable from 'sortablejs'
import type { TaskListItem } from '../api/task'
import { reorderTasks } from '../api/task'
import { errMsg } from '../utils/taskShared'

export function useDragSort(params: {
  tasks: ComputedRef<TaskListItem[]>
  isManualSort: ComputedRef<boolean>
  fetchFiltered: () => Promise<void>
}) {
  const { tasks, isManualSort, fetchFiltered } = params

  const cardsGridEl = ref<HTMLElement | null>(null)
  let sortable: Sortable | null = null

  function setupSortable() {
    if (!cardsGridEl.value || sortable) return
    sortable = new Sortable(cardsGridEl.value, {
      handle: '.drag-handle',
      filter: 'button, input, .card-check',
      preventOnFilter: true,
      animation: 150,
      onEnd: handleDragEnd,
    })
  }
  function teardownSortable() {
    sortable?.destroy()
    sortable = null
  }
  // immediate：排序偏好会记忆上次选择，页面加载时可能直接就是手动排序，需立即初始化拖拽
  watch(isManualSort, (on) => {
    if (on) nextTick(setupSortable)
    else teardownSortable()
  }, { immediate: true })
  onBeforeUnmount(teardownSortable)

  /** 拖拽结束：在 tasks.value 全序数组中按可见区移动计算新顺序 → 持久化（只提交被移动的块 + 两端锚点） → 重新拉取 */
  function handleDragEnd(evt: Sortable.SortableEvent) {
    const oldIndex = evt.oldIndex
    const newIndex = evt.newIndex
    if (oldIndex == null || newIndex == null || oldIndex === newIndex) return
    const arr = [...tasks.value]
    if (oldIndex < 0 || oldIndex >= arr.length || newIndex < 0 || newIndex > arr.length) return
    const [moved] = arr.splice(oldIndex, 1)
    arr.splice(newIndex, 0, moved!)
    // 最小变化区间：仅提交被移动的连续块（含跨越的相邻项）与目标位置两端锚点，
    // 避免把当前筛选结果的全部 id 一次性发给后端
    const lo = Math.min(oldIndex, newIndex)
    const hi = Math.max(oldIndex, newIndex)
    const block = arr.slice(lo, hi + 1)
    reorderTasks({
      ids: block.map((t) => t.id),
      afterId: arr[lo - 1]?.id ?? null,
      beforeId: arr[hi + 1]?.id ?? null,
    })
      .then(() => fetchFiltered())
      .catch((err) => {
        ElMessage.error('排序保存失败：' + errMsg(err))
        fetchFiltered()
      })
  }

  return { cardsGridEl, handleDragEnd }
}
