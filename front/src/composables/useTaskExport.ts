// 导出：Excel (多 sheet + 样式) / CSV（全字段）/ 备份 JSON（全库，可直接回导）
// 列表已服务端分页，导出始终全量拉取当前筛选结果（不受已加载页数限制）
import { computed, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { TaskDetail, TaskImportItem, TaskListItem } from '../api/task'
import { listTasks } from '../api/task'
import { MAIN_COLUMNS, boardLabel, dateTag, downloadBlob, errMsg, fetchDetailsInBatches, fmtISODate } from '../utils/taskShared'
import type { BoardMap } from '../utils/taskShared'
import { logOp } from './useOpLog'

export function useTaskExport(params: {
  selectedIds: Ref<Set<number>>
  boardMap: Ref<BoardMap>
  navAllCount: ComputedRef<number>
  /** 当前筛选结果总数（来自后端统计聚合，用于提示） */
  totalMatching: Ref<number>
  fetchAllMatching: () => Promise<TaskListItem[]>
}) {
  const { selectedIds, boardMap, navAllCount, totalMatching, fetchAllMatching } = params

  const exportRowsHint = computed(() => {
    if (selectedIds.value.size > 0) return `将导出（勾选 ${selectedIds.value.size} 条）`
    const n = totalMatching.value
    if (n === navAllCount.value || !navAllCount.value) return `将导出 ${n} 条（当前筛选）`
    return `将导出 ${n} 条（当前筛选，全库 ${navAllCount.value} 条）`
  })

  const exporting = ref(false)

  /** 解析要导出的行：勾选优先（全量拉取后过滤），否则当前筛选全量 */
  async function resolveRows(): Promise<TaskListItem[]> {
    const all = await fetchAllMatching()
    return selectedIds.value.size > 0 ? all.filter((t) => selectedIds.value.has(t.id)) : all
  }

  async function exportExcel() {
    let rows: TaskListItem[]
    try {
      rows = await resolveRows()
    } catch (err: any) {
      ElMessage.error('导出失败：' + errMsg(err))
      return
    }
    if (!rows.length) {
      ElMessage.warning('没有可导出的数据')
      return
    }
    exporting.value = true
    try {
      // exceljs 体积较大，按需懒加载（首次导出时才下载该 chunk）
      const ExcelJS = await import('exceljs')
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
          board: boardLabel(t.board, boardMap.value),
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
        // 截止日期：逾期红，7日内黄（与卡片 deadlineState 一致：按本地时区零点解析）
        if (t.deadline && deadlineColIdx > 0) {
          const d = new Date(t.deadline + 'T00:00:00')
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

      // 批量拉详情：只对有 logCount > 0 的事项拉详情，省请求（分批并发，避免打爆连接）
      const needLogs = rows.filter((t) => (t.logCount ?? 0) > 0)
      if (needLogs.length) {
        const details = await fetchDetailsInBatches(needLogs.map((t) => t.id))
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

  async function exportCSV() {
    let rows: TaskListItem[]
    try {
      rows = await resolveRows()
    } catch (err: any) {
      ElMessage.error('导出失败：' + errMsg(err))
      return
    }
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
            case 'board': v = boardLabel(t.board, boardMap.value); break
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

  // 备份 JSON（全库，格式对称于 {data:{quanfa[],happy[]}}，可直接回导）
  async function exportBackupJSON() {
    exporting.value = true
    try {
      // 1. 拉全量列表
      const { data: page } = await listTasks({ all: true })
      const all = page.items
      if (!all.length) {
        ElMessage.warning('当前没有任何数据可备份')
        return
      }
      // 2. 分批并发拉每条详情拿 logs（有 logCount 才拉）
      const needLogs = all.filter((t) => (t.logCount ?? 0) > 0)
      const detailMap = new Map<number, TaskDetail>()
      if (needLogs.length) {
        const ds = await fetchDetailsInBatches(needLogs.map((t) => t.id))
        for (const d of ds) if (d) detailMap.set(d.id, d)
      }
      // 3. 按看板 code 动态按键分组（{quanfa, happy, temp, ...}）→ 对齐 TaskImportItem
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
      // boardMap 缺失时按数据实际出现过的 board 兜底
      const boards = Object.keys(boardMap.value).length
        ? Object.keys(boardMap.value)
        : [...new Set(all.map((t) => t.board))]
      const payload: Record<string, TaskImportItem[]> = {}
      for (const board of boards) {
        payload[board] = all.filter((t) => t.board === board).map(toImportItem)
      }
      const boardSummary = boards.map((b) => `${boardLabel(b, boardMap.value)} ${payload[b]!.length}`).join(' + ')
      const blob = new Blob([JSON.stringify({ data: payload }, null, 2)], { type: 'application/json;charset=utf-8;' })
      downloadBlob(blob, `工作跟进看板_备份_${dateTag()}.json`)
      ElMessage.success(`已备份 JSON，共 ${all.length} 条（${boardSummary}）`)
      logOp({ action: 'EXPORT', detail: `导出备份 JSON，共 ${all.length} 条（${boardSummary}）` })
    } catch (err: any) {
      ElMessage.error('备份 JSON 失败：' + (err?.message || '未知错误'))
    } finally {
      exporting.value = false
    }
  }

  return { exportRowsHint, exporting, exportExcel, exportCSV, exportBackupJSON }
}
