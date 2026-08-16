<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ImportBatchError, ImportMode, TaskBatchItem, TaskBatchLog, TaskDetail, TaskImportItem, TaskListItem } from '../api/task'
import { importBatch, listBoards, listTasks } from '../api/task'
import { MAIN_COLUMNS, PRIORITIES, STATUSES, LABEL_TO_KEY, boardLabel, cellToPlain, dateTag, downloadBlob, fetchDetailsInBatches, fmtISODate, normalizeBoard } from '../utils/taskShared'
import type { BoardMap } from '../utils/taskShared'
import { logOp } from '../composables/useOpLog'

const props = defineProps<{ totalCount: number }>()
const emit = defineEmits<{ (e: 'done'): void }>()
// 导入进行中状态（v-model 双向同步，供父组件工具栏「导入」按钮显示 loading）
const importing = defineModel<boolean>('importing', { default: false })

// ---------- 看板动态校验：打开导入时拉取 /boards ----------
const boardMap = ref<BoardMap>({})
async function loadBoardMap() {
  try {
    const { data } = await listBoards()
    boardMap.value = Object.fromEntries(data.map((b) => [b.code, b]))
  } catch {
    boardMap.value = {}
  }
}
/** 合法看板名提示文案（如 全发/会幸福/临时专项） */
const boardNamesHint = computed(() => {
  const names = Object.values(boardMap.value).map((b) => b.name)
  return names.length ? names.join(' / ') : '请先配置看板'
})

// ---------- 导入：预览 Dialog 相关状态 ----------
const importInput = ref<HTMLInputElement | null>(null)
const importPreviewVisible = ref(false)
const importFileName = ref('')
const importFileType = ref<'json' | 'xlsx' | 'csv'>('json')
const importParsedItems = ref<TaskBatchItem[]>([])
// xlsx/csv 列映射：原始表头 -> 字段 key（或空=忽略）
const importRawHeaders = ref<string[]>([])
const importColMapping = reactive<Record<string, string>>({})
// 预览：前 5 行
const importPreviewRows = ref<Record<string, unknown>[]>([])
// 模式：overwrite / upsert
const importMode = ref<ImportMode>('overwrite')
// 是否跳过错误行
const importSkipOnError = ref(true)
// overwrite 前是否自动下载备份
const importAutoBackup = ref(true)
// 前端预校验结果
const importPreErrors = ref<ImportBatchError[]>([])

/** 导入字段选项（列映射下拉使用） */
const IMPORT_FIELD_OPTIONS = [
  { value: '', label: '— 忽略此列 —' },
  ...MAIN_COLUMNS.filter((c) => c.key !== 'subItemsText').map((c) => ({ value: c.key, label: `${c.label}${c.required ? '（必填）' : ''}` })),
  { value: 'subItemsText', label: '子项' },
]

/** 缺少的必填列映射（非 JSON 模式才检查） */
const importRequiredMissing = computed(() => {
  if (importFileType.value === 'json') return [] as typeof MAIN_COLUMNS
  const mappedKeys = new Set(Object.values(importColMapping))
  return MAIN_COLUMNS.filter((c) => c.required && !mappedKeys.has(c.key as string))
})

/** 导入预览 Dialog 是否允许确认 */
const importCanConfirm = computed(() => {
  if (!importParsedItems.value.length) return false
  if (importRequiredMissing.value.length > 0) return false
  // 如果有错误但 skipOnError 开启，允许确认
  return true
})

function open() {
  loadBoardMap() // 打开即拉最新看板列表（保证看板校验/中文名映射是最新的）
  importInput.value?.click()
}
defineExpose({ open })

/** 智能匹配中文表头到字段 key */
function autoMatchHeader(label: string): string {
  const t = label.trim()
  if (LABEL_TO_KEY[t]) return LABEL_TO_KEY[t]
  // 模糊匹配
  for (const c of MAIN_COLUMNS) {
    if (t.includes(c.label) || c.label.includes(t)) return c.key as string
  }
  // 英文 key 直接命中
  if (MAIN_COLUMNS.some((c) => c.key === t)) return t
  return ''
}

/** 把一行 xlsx/csv 对象（列 key → 值）转成 TaskBatchItem（按列映射） */
function rowObjectToBatchItem(obj: Record<string, unknown>, rowIdx: number): { item: TaskBatchItem; errs: ImportBatchError[] } {
  const errs: ImportBatchError[] = []
  const raw: Record<string, string> = {}
  for (const [hdr, fieldKey] of Object.entries(importColMapping)) {
    if (!fieldKey) continue
    raw[fieldKey] = cellToPlain(obj[hdr]).trim()
  }
  const board = normalizeBoard(raw.board, boardMap.value)
  const title = raw.title
  if (!board) errs.push({ rowIndex: rowIdx, field: '看板', message: `看板必填（${boardNamesHint.value} 或其 code）`, value: raw.board })
  else if (!boardMap.value[board]) errs.push({ rowIndex: rowIdx, field: '看板', message: `看板值非法，应为 ${boardNamesHint.value} 之一`, value: raw.board })
  if (!title) errs.push({ rowIndex: rowIdx, field: '具体事项', message: '具体事项必填', value: raw.title })
  if (raw.priority && !PRIORITIES.includes(raw.priority))
    errs.push({ rowIndex: rowIdx, field: '优先级', message: `优先级必须是 ${PRIORITIES.join(' / ')}`, value: raw.priority })
  if (raw.status && !STATUSES.includes(raw.status))
    errs.push({ rowIndex: rowIdx, field: '当前状态', message: `状态必须是 ${STATUSES.join(' / ')}`, value: raw.status })
  const dateRe = /^\d{4}-\d{2}-\d{2}$/
  if (raw.deadline && !dateRe.test(raw.deadline))
    errs.push({ rowIndex: rowIdx, field: '计划完成日期', message: '日期格式应为 YYYY-MM-DD', value: raw.deadline })
  if (raw.updateDate && !dateRe.test(raw.updateDate))
    errs.push({ rowIndex: rowIdx, field: '更新日期', message: '日期格式应为 YYYY-MM-DD', value: raw.updateDate })

  const subItems: string[] = []
  if (raw.subItemsText) {
    for (const s of raw.subItemsText.split(/[;；\n]/g)) {
      const t = s.trim()
      if (t) subItems.push(t)
    }
  }
  const item: TaskBatchItem = {
    taskCode: raw.taskCode || undefined,
    board: board ?? 'quanfa',
    module: raw.module || null,
    title: title || '(空)',
    description: raw.description || null,
    status: raw.status || null,
    priority: raw.priority || null,
    owner: raw.owner || null,
    collab: raw.collab || null,
    pain: raw.pain || null,
    nextStep: raw.nextStep || null,
    deadline: raw.deadline || null,
    risk: raw.risk || null,
    subItems: subItems.length ? subItems : undefined,
    updateDate: raw.updateDate || null,
  }
  return { item, errs }
}

/** 前端预校验 JSON 模式的导入 items（简单校验，详细留给后端） */
function preValidateJSON(items: TaskBatchItem[]): ImportBatchError[] {
  const errs: ImportBatchError[] = []
  const validBoards = Object.keys(boardMap.value)
  for (let i = 0; i < items.length; i++) {
    const it = items[i]!
    if (!it.board) errs.push({ rowIndex: i, field: '看板', message: '看板必填', value: it.board })
    else if (!validBoards.includes(it.board))
      errs.push({ rowIndex: i, field: '看板', message: `看板值应为 ${validBoards.join(' / ')}`, value: it.board })
    if (!it.title) errs.push({ rowIndex: i, field: '具体事项', message: '具体事项必填', value: String(it.title ?? '') })
    if (it.priority && !PRIORITIES.includes(it.priority))
      errs.push({ rowIndex: i, field: '优先级', message: `优先级必须是 ${PRIORITIES.join(' / ')}`, value: it.priority })
    if (it.status && !STATUSES.includes(it.status))
      errs.push({ rowIndex: i, field: '当前状态', message: `状态必须是 ${STATUSES.join(' / ')}`, value: it.status })
  }
  return errs
}

async function onImportFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importFileName.value = file.name
  const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
  let parsed: TaskBatchItem[] = []
  let rawHeaders: string[] = []
  let type: 'json' | 'xlsx' | 'csv' = 'json'
  let previewRows: Record<string, unknown>[] = []

  try {
    if (ext === 'json') {
      type = 'json'
      // 先确保看板列表就绪（open() 是 fire-and-forget，文件可能先于看板请求返回）
      if (!Object.keys(boardMap.value).length) await loadBoardMap()
      const json = JSON.parse(await file.text())
      // 动态按键分组：{data:{quanfa:[], happy:[], temp:[], ...}}；旧文件仅 quanfa/happy 两键仍兼容
      const dataObj = json?.data && typeof json.data === 'object' ? json.data : {}
      const entries = Object.entries(dataObj).filter(([, v]) => Array.isArray(v))
      if (!entries.length) {
        ElMessage.error('JSON 结构不符：需包含 data.{看板code} 数组（如 data.quanfa / data.temp）')
        return
      }
      const conv = (arr: TaskImportItem[], board: string): TaskBatchItem[] =>
        arr
          .filter((x) => x && x.item)
          .map((it) => ({
            taskCode: it.id,
            board,
            module: it.module ?? null,
            title: it.item,
            description: null,
            status: it.status ?? null,
            priority: it.priority ?? null,
            owner: it.owner ?? null,
            collab: it.collab ?? null,
            pain: it.pain ?? null,
            nextStep: it.next ?? null,
            deadline: it.deadline ?? null,
            risk: it.risk ?? null,
            subItems: it.subItems,
            logs: it.logs?.map<TaskBatchLog>((l) => ({
              date: l.date,
              person: l.person,
              summary: l.summary,
              next: l.next,
            })),
            updateDate: it.updateDate ?? null,
          }))
      parsed = entries.flatMap(([board, arr]) => conv(arr as TaskImportItem[], board))
      previewRows = parsed.slice(0, 5).map((it) => ({
        事项ID: it.taskCode ?? '', 看板: boardLabel(it.board, boardMap.value), 工作模块: it.module ?? '',
        具体事项: it.title, 当前状态: it.status ?? '', 优先级: it.priority ?? '', 负责人: it.owner ?? '',
        子项: (it.subItems ?? []).join('；'),
      }))
    } else if (ext === 'xlsx' || ext === 'xls' || ext === 'csv') {
      type = ext === 'csv' ? 'csv' : 'xlsx'
      const buf = await file.arrayBuffer()
      if (ext === 'csv') {
        // CSV：FileReader 读文本，按逗号分隔手动解析（exceljs 的 csvReader 太复杂）
        const text = new TextDecoder('utf-8').decode(buf)
        const lines = text.replace(/\r\n/g, '\n').replace(/^\ufeff/, '').split('\n').filter((l) => l.length)
        if (!lines.length) throw new Error('CSV 文件为空')
        const parseCSVLine = (line: string): string[] => {
          const out: string[] = []
          let cur = ''
          let inQuote = false
          for (let i = 0; i < line.length; i++) {
            const ch = line[i]
            if (ch === '"') {
              if (inQuote && line[i + 1] === '"') { cur += '"'; i++ }
              else inQuote = !inQuote
            } else if (ch === ',' && !inQuote) {
              out.push(cur); cur = ''
            } else cur += ch
          }
          out.push(cur)
          return out
        }
        const hdrs = parseCSVLine(lines[0]!).map((s) => (s ?? '').trim())
        rawHeaders = hdrs
        const rawRows = lines.slice(1).map((l) => {
          const cols = parseCSVLine(l)
          const obj: Record<string, string> = {}
          hdrs.forEach((h, i) => (obj[h] = cols[i] ?? ''))
          return obj
        })
        previewRows = rawRows.slice(0, 5)
        parsed = []
        for (let i = 0; i < rawRows.length; i++) {
          // 先用初始映射（空或上次），等 dialog 打开后 user 改映射再重算
          parsed.push({ board: 'quanfa', title: '' })
        }
        importParsedItems.value = parsed
        importRawHeaders.value = rawHeaders
        // 重置列映射：智能匹配
        for (const k of Object.keys(importColMapping)) delete importColMapping[k]
        for (const h of rawHeaders) importColMapping[h] = autoMatchHeader(h)
        // 根据映射重算 parsed 和 errs
        const reParsed: TaskBatchItem[] = []
        const errsList: ImportBatchError[] = []
        for (let i = 0; i < rawRows.length; i++) {
          const { item, errs } = rowObjectToBatchItem(rawRows[i]!, i)
          reParsed.push(item)
          errsList.push(...errs)
        }
        parsed = reParsed
        importPreErrors.value = errsList
        // 存 rawRows 给重算映射用
        ;(importParsedItems as any)._rawRows = rawRows
      } else {
        // exceljs 体积较大，仅解析 xlsx 时懒加载
        const ExcelJS = await import('exceljs')
        const wb = new ExcelJS.Workbook()
        await wb.xlsx.load(buf)
        const ws = wb.worksheets[0]
        if (!ws) throw new Error('Excel 中没有工作表')
        const rowCount = ws.rowCount
        if (rowCount < 2) throw new Error('Excel 至少需要 1 行表头 + 1 行数据')
        // 读第一行表头（用 cell.text）
        const hdrs: string[] = []
        let maxColSeen = 0
        ws.getRow(1).eachCell({ includeEmpty: false }, (cell, colNum) => {
          hdrs[colNum - 1] = cellToPlain(cell.value)
          maxColSeen = Math.max(maxColSeen, colNum)
        })
        // 补全末尾空表头位置，避免 sparse array 导致列号错位
        const maxCol = Math.max(maxColSeen, hdrs.length)
        for (let i = 0; i < maxCol; i++) if (!(i in hdrs)) hdrs[i] = `列${i + 1}`
        rawHeaders = hdrs
        const rawRows: Record<string, unknown>[] = []
        for (let r = 2; r <= rowCount; r++) {
          const row = ws.getRow(r)
          const obj: Record<string, unknown> = {}
          let nonEmpty = 0
          hdrs.forEach((h, i) => {
            const cell = row.getCell(i + 1)
            obj[h] = cell.value
            if (cellToPlain(cell.value) !== '') nonEmpty++
          })
          if (nonEmpty === 0) continue
          rawRows.push(obj)
        }
        previewRows = rawRows.slice(0, 5).map((row) => {
          const p: Record<string, unknown> = {}
          for (const h of hdrs) p[h] = cellToPlain(row[h])
          return p
        })
        parsed = []
        for (let i = 0; i < rawRows.length; i++) parsed.push({ board: 'quanfa', title: '' })
        importParsedItems.value = parsed
        importRawHeaders.value = rawHeaders
        for (const k of Object.keys(importColMapping)) delete importColMapping[k]
        for (const h of rawHeaders) importColMapping[h] = autoMatchHeader(h)
        const reParsed: TaskBatchItem[] = []
        const errsList: ImportBatchError[] = []
        for (let i = 0; i < rawRows.length; i++) {
          const { item, errs } = rowObjectToBatchItem(rawRows[i]!, i)
          reParsed.push(item)
          errsList.push(...errs)
        }
        parsed = reParsed
        importPreErrors.value = errsList
        // 存 rawRows 给重算映射用
        ;(importParsedItems as any)._rawRows = rawRows
      }
    } else {
      ElMessage.error(`不支持的文件类型：.${ext}，请选择 .json / .xlsx / .csv 文件`)
      return
    }
  } catch (err: any) {
    ElMessage.error('文件解析失败：' + (err?.message || String(err)))
    return
  }

  importFileType.value = type
  importParsedItems.value = parsed
  importPreviewRows.value = previewRows
  if (type === 'json') {
    importPreErrors.value = preValidateJSON(parsed)
  }
  // 默认模式：JSON 默认 overwrite（备份语义）；xlsx/csv 默认 upsert（增量编辑语义）
  importMode.value = type === 'json' ? 'overwrite' : 'upsert'
  importSkipOnError.value = type !== 'json'
  importPreviewVisible.value = true
}

/** 重算 xlsx/csv 的 parsed 和 preview（列映射变化时调用） */
function recomputeFromMapping() {
  const rawRows = (importParsedItems as any)._rawRows as Record<string, unknown>[] | undefined
  if (!rawRows || importFileType.value === 'json') return
  const reParsed: TaskBatchItem[] = []
  const errsList: ImportBatchError[] = []
  for (let i = 0; i < rawRows.length; i++) {
    const { item, errs } = rowObjectToBatchItem(rawRows[i]!, i)
    reParsed.push(item)
    errsList.push(...errs)
  }
  importParsedItems.value = reParsed
  importPreErrors.value = errsList
  // 重算预览
  importPreviewRows.value = rawRows.slice(0, 5).map((row) => {
    const p: Record<string, unknown> = {}
    for (const h of importRawHeaders.value) p[h] = cellToPlain(row[h])
    return p
  })
}

/** 把当前 DB 全量导出备份 JSON（overwrite 前自动触发） */
async function autoBackupBeforeOverwrite(): Promise<boolean> {
  if (!importAutoBackup.value) return true
  try {
    const { data } = await listTasks({ all: true })
    const all = data.items
    if (!all.length) return true
    // 只拉有 logs 的（分批并发，避免一次性 Promise.all 打爆连接）
    const needLogs = all.filter((t) => (t.logCount ?? 0) > 0)
    const detailMap = new Map<number, TaskDetail>()
    if (needLogs.length) {
      const ds = await fetchDetailsInBatches(needLogs.map((t) => t.id))
      for (const d of ds) if (d) detailMap.set(d.id, d)
    }
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
          ? d.logs.map((l) => ({ date: l.logDate ? fmtISODate(l.logDate) : null, person: l.person ?? undefined, summary: l.summary ?? '', next: l.nextStep ?? undefined }))
          : undefined,
        updateDate: t.updateDate ? fmtISODate(t.updateDate) : undefined,
      }
    }
    // 看板按键动态构造（与导出备份格式一致）；boardMap 缺失时按数据实际出现过的 board 兜底
    const boards = Object.keys(boardMap.value).length
      ? Object.keys(boardMap.value)
      : [...new Set(all.map((t) => t.board))]
    const payload: Record<string, TaskImportItem[]> = {}
    for (const board of boards) {
      payload[board] = all.filter((t) => t.board === board).map(toImportItem)
    }
    const blob = new Blob([JSON.stringify({ data: payload }, null, 2)], { type: 'application/json;charset=utf-8;' })
    downloadBlob(blob, `导入前_自动备份_${dateTag()}.json`)
    return true
  } catch (err: any) {
    try {
      await ElMessageBox.confirm(
        '自动备份失败：' + (err?.message || '未知错误') + '，是否继续导入（不推荐）？',
        '备份失败',
        { type: 'warning', confirmButtonText: '继续（不备份）', cancelButtonText: '取消导入' },
      )
      return true
    } catch {
      return false
    }
  }
}

async function confirmImport() {
  if (!importCanConfirm.value) return
  const mode = importMode.value
  if (mode === 'overwrite') {
    const n = props.totalCount
    try {
      await ElMessageBox.confirm(
        `将<strong>全量覆盖</strong>当前 ${n} 条数据，删除后不可恢复。是否继续？`,
        '全量覆盖导入',
        { type: 'warning', confirmButtonText: '确认覆盖', cancelButtonText: '取消', dangerouslyUseHTMLString: true },
      )
    } catch {
      return
    }
    if (!(await autoBackupBeforeOverwrite())) return
  }
  importing.value = true
  try {
    const { data } = await importBatch({
      mode,
      skipOnError: importSkipOnError.value,
      items: importParsedItems.value,
    })
    const parts: string[] = []
    if (data.imported) parts.push(`新增 ${data.imported} 条`)
    if (data.updated) parts.push(`更新 ${data.updated} 条`)
    if (data.skipped) parts.push(`跳过 ${data.skipped} 条`)
    const msg = `导入完成：共 ${data.total} 条 → ${parts.join('，') || '无变更'}`
    logOp({
      action: 'IMPORT',
      detail: `导入 ${data.total} 条（${parts.join('，') || '无变更'}）· 模式：${mode === 'overwrite' ? '全量覆盖' : '增量合并'} · 错误 ${data.errors.length} 条`,
    })
    if (data.errors?.length) {
      const errs = data.errors.slice(0, 5)
      const detail = errs.map((e) => `第${e.rowIndex + 1}行[${e.field}]：${e.message}`).join('\n')
        + (data.errors.length > 5 ? `\n…还有 ${data.errors.length - 5} 条错误` : '')
      await ElMessageBox.alert(detail, msg + `（错误 ${data.errors.length} 条）`, {type: data.imported + data.updated > 0 ? 'warning' : 'error'})
    } else {
      ElMessage.success(msg)
    }
    importPreviewVisible.value = false
    emit('done')
  } catch (err: any) {
    ElMessage.error('导入失败：' + (err?.response?.data?.message || err?.message || '未知错误'))
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <!-- 全量覆盖导入文件选择（隐藏 input，由「导入」按钮触发） -->
  <input
    ref="importInput"
    type="file"
    accept=".json,.xlsx,.xls,.csv,application/json,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
    style="display: none"
    @change="onImportFile"
  />

  <!-- 导入预览 Dialog -->
  <el-dialog
    v-model="importPreviewVisible"
    title="导入预览"
    width="min(960px, 92vw)"
    top="6vh"
    class="import-preview-dlg"
    :close-on-click-modal="false"
  >
    <div class="import-meta">
      <div class="import-meta-left">
        <div class="file-name">📂 {{ importFileName }}</div>
        <div class="file-type">
          类型：
          <el-tag size="small" :type="importFileType === 'json' ? 'info' : importFileType === 'xlsx' ? 'success' : 'warning'">
            {{ importFileType.toUpperCase() }}
          </el-tag>
          <span style="margin-left:12px;">共 {{ importParsedItems.length }} 条</span>
          <span v-if="importPreErrors.length" style="margin-left:12px;color:var(--c-st-urgent);">
            预校验错误 {{ importPreErrors.length }} 处
          </span>
        </div>
      </div>
      <div class="import-mode">
        <el-radio-group v-model="importMode" size="default">
          <el-radio-button value="overwrite">全量覆盖</el-radio-button>
          <el-radio-button value="upsert">增量合并</el-radio-button>
        </el-radio-group>
        <el-tooltip v-if="importMode === 'overwrite'" content="清空当前所有事项后再导入（JSON 的默认模式）" placement="top">
          <i class="mode-hint">ⓘ</i>
        </el-tooltip>
        <el-tooltip v-else content="按「事项ID」匹配：存在则更新，不存在则新增（Excel/CSV 的默认模式）" placement="top">
          <i class="mode-hint">ⓘ</i>
        </el-tooltip>
      </div>
    </div>

    <!-- overwrite 专用选项 -->
    <div v-if="importMode === 'overwrite'" class="import-options-row">
      <el-checkbox v-model="importAutoBackup">
        覆盖前自动下载备份 JSON（推荐，防止误操作）
      </el-checkbox>
    </div>
    <div class="import-options-row">
      <el-checkbox v-model="importSkipOnError">
        跳过错误行继续导入（否则遇到第一条错误中断）
      </el-checkbox>
    </div>

    <!-- 列映射（仅 xlsx/csv） -->
    <div v-if="importFileType !== 'json' && importRawHeaders.length" class="col-mapping">
      <div class="col-mapping-title">
        📋 列映射（自动匹配中文表头，不对应请手动选择）
        <span v-if="importRequiredMissing.length" style="color:var(--c-st-urgent);margin-left:12px;">
          缺少必填列：{{ importRequiredMissing.map((c) => c.label).join('、') }}
        </span>
      </div>
      <div class="col-mapping-grid">
        <div v-for="hdr in importRawHeaders" :key="hdr" class="col-map-item">
          <div class="col-map-hdr" :title="hdr">{{ hdr || '(空列名)' }}</div>
          <el-select
            :model-value="importColMapping[hdr] || ''"
            size="small"
            placeholder="— 忽略 —"
            style="width:100%;"
            @update:model-value="(v: string) => { importColMapping[hdr] = v; recomputeFromMapping(); }"
          >
            <el-option
              v-for="opt in IMPORT_FIELD_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </div>
      </div>
    </div>

    <!-- 预览表格 -->
    <div class="preview-title">🔍 前 {{ importPreviewRows.length }} 行预览</div>
    <div class="preview-wrap">
      <el-table :data="importPreviewRows" size="small" stripe border :header-cell-style="{ background: 'var(--c-row-zebra)', fontWeight: 600 }">
        <el-table-column label="#" width="48" type="index" align="center" />
        <template v-if="importFileType === 'json'">
          <el-table-column v-for="k in ['事项ID','看板','工作模块','具体事项','当前状态','优先级','负责人','子项']" :key="k" :prop="k" :label="k" min-width="100" show-overflow-tooltip />
        </template>
        <template v-else>
          <el-table-column
            v-for="h in importRawHeaders"
            :key="h"
            :prop="h"
            :label="h"
            min-width="110"
            show-overflow-tooltip
          >
            <template #default="{ $index }">
              <span
                :class="{
                  'has-err':
                    importPreErrors.some(
                      (e) =>
                        e.rowIndex === $index &&
                        (e.field === h ||
                          (MAIN_COLUMNS.find((c) => c.label === h && c.key === importColMapping[h])?.label === e.field)),
                    ),
                }"
              >
                {{ cellToPlain(importPreviewRows[$index]?.[h]) }}
              </span>
            </template>
          </el-table-column>
        </template>
      </el-table>
    </div>

    <!-- 预校验错误列表 -->
    <div v-if="importPreErrors.length" class="pre-errors">
      <div class="pre-errors-title">⚠️ 预校验错误（前 10 条）</div>
      <ul>
        <li v-for="(e, i) in importPreErrors.slice(0, 10)" :key="i">
          <span class="err-row">第 {{ e.rowIndex + 1 }} 行</span>
          <span class="err-field">[{{ e.field }}]</span>
          <span class="err-msg">{{ e.message }}</span>
          <span v-if="e.value != null" class="err-val">（原值：{{ e.value }}）</span>
        </li>
      </ul>
      <div v-if="importPreErrors.length > 10" class="more-errs">…还有 {{ importPreErrors.length - 10 }} 条未显示</div>
    </div>

    <template #footer>
      <div class="dlg-footer-stats">
        <span>共 <b>{{ importParsedItems.length }}</b> 条待导入</span>
      </div>
      <el-button @click="importPreviewVisible = false">取消</el-button>
      <el-button type="primary" :disabled="!importCanConfirm" :loading="importing" @click="confirmImport">
        确认导入
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* ---------- 导入预览 Dialog ---------- */
.import-preview-dlg :deep(.el-dialog__body) {
  padding: 12px 20px 4px;
}
.import-meta {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.import-meta-left {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.file-name {
  font-weight: 600;
  color: var(--c-ink);
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 520px;
}
.file-type {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  color: var(--c-ink-soft);
}
.import-mode {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.mode-hint {
  font-style: normal;
  font-size: 15px;
  color: var(--c-muted);
  cursor: help;
}
.import-options-row {
  padding: 4px 0;
  font-size: 13px;
  color: var(--c-ink-soft);
}

/* 列映射 */
.col-mapping {
  margin: 10px 0 8px;
  padding: 10px 12px;
  background: var(--c-row-zebra);
  border-radius: 8px;
  border: 1px solid var(--c-line);
}
.col-mapping-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-ink);
  margin-bottom: 10px;
}
.col-mapping-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px 14px;
}
.col-map-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.col-map-hdr {
  font-size: 12px;
  color: var(--c-muted);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 预览表格 */
.preview-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-ink);
  margin: 8px 0 6px;
}
.preview-wrap {
  border: 1px solid var(--c-line);
  border-radius: 8px;
  overflow: hidden;
  max-height: 260px;
  overflow-y: auto;
}
.has-err {
  color: var(--c-st-urgent);
  background: color-mix(in srgb, var(--c-st-urgent) 10%, transparent);
  padding: 1px 3px;
  border-radius: 3px;
}

/* 预校验错误列表 */
.pre-errors {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--c-st-urgent) 30%, var(--c-line));
  background: color-mix(in srgb, var(--c-st-urgent) 6%, var(--c-card));
  border-radius: 8px;
}
.pre-errors-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-st-urgent);
  margin-bottom: 6px;
}
.pre-errors ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--c-ink-soft);
  line-height: 1.75;
}
.err-row {
  font-weight: 600;
  color: var(--c-ink);
}
.err-field {
  color: var(--c-blue);
  margin-right: 2px;
}
.err-msg {
  color: var(--c-st-urgent);
}
.err-val {
  color: var(--c-muted);
}
.more-errs {
  margin-top: 4px;
  font-size: 12px;
  color: var(--c-muted);
}
.dlg-footer-stats {
  display: inline-flex;
  align-items: center;
  margin-right: auto;
  font-size: 13px;
  color: var(--c-ink-soft);
}
.dlg-footer-stats b {
  color: var(--c-blue);
  font-size: 15px;
  margin: 0 2px;
}
.import-preview-dlg :deep(.el-dialog__footer) {
  display: flex;
  align-items: center;
}
</style>
