// 看板数据（动态来自 /boards）：映射、全部 code、筛选面板选项
import { computed, ref } from 'vue'
import type { BoardItem } from '../api/task'
import { listBoards } from '../api/task'
import type { BoardMap } from '../utils/taskShared'

export function useBoardData() {
  const boardMap = ref<BoardMap>({})

  /** 拉取看板列表；返回全部看板 code（失败返回空数组） */
  async function fetchBoards(): Promise<string[]> {
    try {
      const { data } = await listBoards()
      boardMap.value = Object.fromEntries(data.map((b: BoardItem) => [b.code, b]))
      return data.map((b: BoardItem) => b.code)
    } catch {
      boardMap.value = {}
      return []
    }
  }

  const allBoardCodes = computed(() => Object.keys(boardMap.value))

  /** 高级筛选面板的看板选项（label=看板名，value=code） */
  const filterBoardOptions = computed(() =>
    Object.values(boardMap.value).map((b) => ({ label: b.name, value: b.code })),
  )

  return { boardMap, fetchBoards, allBoardCodes, filterBoardOptions }
}
