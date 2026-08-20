// 侧栏三点菜单操作：看板 / 工作模块管理（事件由 SidebarNav 发出，确认与提示在这里）
import { computed } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createBoard, createModule, deleteBoard, deleteModule, renameModule, reorderModules, updateBoard } from '../api/task'
import type { BoardMap, MenuGroup } from '../utils/taskShared'
import { errMsg } from '../utils/taskShared'
import { logOp } from './useOpLog'

export function useBoardManage(params: {
  boardMap: Ref<BoardMap>
  menuGroups: ComputedRef<MenuGroup[]>
  reload: () => void
  /** 删除看板后若当前导航指向该看板，由组合根复位导航 */
  onBoardDeleted: (code: string) => void
  /** 模块拖拽重排后的乐观本地更新（失败回滚走 reload） */
  applyModuleOrder: (board: string, names: string[]) => void
}) {
  const { boardMap, menuGroups, reload, onBoardDeleted, applyModuleOrder } = params

  // 看板 code → 看板 id（重命名/改色/删除按 id 调后端）
  const boardIdByCode = computed(() =>
    Object.fromEntries(Object.values(boardMap.value).map((b) => [b.code, b.id])),
  )

  async function onCreateBoard(form: { code: string; name: string; prefix: string; accent: string }) {
    try {
      await createBoard(form)
      ElMessage.success('看板已创建')
      logOp({ action: 'CREATE', targetType: 'board', targetCode: form.code, detail: `新建看板 ${form.name}（${form.code}）` })
      reload()
    } catch (err: any) {
      ElMessage.error('创建看板失败：' + errMsg(err))
    }
  }

  async function onRenameBoard({ id, name }: { id: number; name: string }) {
    try {
      await updateBoard(id, { name })
      ElMessage.success('看板已重命名')
      logOp({ action: 'UPDATE', targetType: 'board', targetId: id, detail: `看板重命名为 ${name}` })
      reload()
    } catch (err: any) {
      ElMessage.error('重命名失败：' + errMsg(err))
    }
  }

  async function onRecolorBoard({ id, accent }: { id: number; accent: string }) {
    try {
      await updateBoard(id, { accent })
      logOp({ action: 'UPDATE', targetType: 'board', targetId: id, detail: `看板配色改为 ${accent}` })
      reload()
    } catch (err: any) {
      ElMessage.error('配色修改失败：' + errMsg(err))
    }
  }

  async function onDeleteBoard(id: number) {
    const b = Object.values(boardMap.value).find((x) => x.id === id)
    if (!b) return
    if (b.systemFlag) {
      ElMessage.warning('系统看板不可删除')
      return
    }
    if (b.taskCount > 0) {
      ElMessage.warning(`该看板下有 ${b.taskCount} 项事项，先移动或删除事项后再删除看板`)
      return
    }
    try {
      await ElMessageBox.confirm(`确认删除看板「${b.name}」？删除后不可恢复。`, '删除看板', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      })
    } catch {
      return // 用户取消
    }
    try {
      await deleteBoard(id)
      ElMessage.success('看板已删除')
      logOp({ action: 'DELETE', targetType: 'board', targetCode: b.code, detail: `删除看板 ${b.name}` })
      onBoardDeleted(b.code)
      reload()
    } catch (err: any) {
      ElMessage.error('删除失败：' + errMsg(err))
    }
  }

  async function onCreateModule({ board, name }: { board: string; name: string }) {
    try {
      await createModule({ board, name })
      ElMessage.success('工作模块已创建')
      logOp({ action: 'CREATE', targetType: 'module', targetCode: board, detail: `在 ${board} 下新建工作模块 ${name}` })
      reload()
    } catch (err: any) {
      ElMessage.error('创建工作模块失败：' + errMsg(err))
    }
  }

  async function onRenameModule({ board, from, to }: { board: string; from: string; to: string }) {
    try {
      await renameModule({ board, from, to })
      ElMessage.success('模块已重命名')
      logOp({ action: 'UPDATE', targetType: 'module', targetCode: board, detail: `模块 ${from} 重命名为 ${to}` })
      reload()
    } catch (err: any) {
      ElMessage.error('重命名失败：' + errMsg(err))
    }
  }

  async function onDeleteModule({ board, name }: { board: string; name: string }) {
    const g = menuGroups.value.find((x) => x.id === board)
    const m = g?.modules.find((x) => x.name === name)
    const cnt = m?.count ?? 0
    try {
      await ElMessageBox.confirm(
        cnt > 0
          ? `删除模块「${name}」？该模块下 ${cnt} 项事项的模块字段将被清空。`
          : `确认删除模块「${name}」？`,
        '删除工作模块',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
      )
    } catch {
      return // 用户取消
    }
    try {
      await deleteModule(board, name)
      ElMessage.success('模块已删除')
      logOp({ action: 'DELETE', targetType: 'module', targetCode: board, detail: `删除工作模块 ${name}` })
      reload()
    } catch (err: any) {
      ElMessage.error('删除失败：' + errMsg(err))
    }
  }

  /** 拖拽重排：先乐观更新本地菜单，再持久化；失败回滚（Sortable 已挪好 DOM，靠 reload 拉回真实顺序） */
  async function onReorderModules({ board, names }: { board: string; names: string[] }) {
    applyModuleOrder(board, names)
    try {
      await reorderModules(board, names)
      logOp({ action: 'UPDATE', targetType: 'module', targetCode: board, detail: `调整 ${board} 工作模块顺序` })
    } catch (err: any) {
      ElMessage.error('排序失败：' + errMsg(err))
      reload()
    }
  }

  return { boardIdByCode, onCreateBoard, onRenameBoard, onRecolorBoard, onDeleteBoard, onCreateModule, onRenameModule, onDeleteModule, onReorderModules }
}
