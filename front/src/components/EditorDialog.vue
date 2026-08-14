<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { TaskListItem, TaskRequest } from '../api/task'
import { createTask, getTask, nextTaskCode as fetchNextCodeApi, suggestModules, suggestOwners, updateTask } from '../api/task'
import { BOARDS, PRIORITIES, STATUSES, errMsg } from '../utils/taskShared'
import { logOp } from '../composables/useOpLog'

const emit = defineEmits<{ (e: 'saved'): void }>()

// ---------- 新建/编辑 ----------
const editorVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<TaskRequest>(emptyForm())

function emptyForm(): TaskRequest {
  return {
    taskCode: '', board: 'quanfa', module: '', title: '', description: '',
    status: '未启动', priority: '中', owner: '', ownerUserid: '', collab: '',
    pain: '', nextStep: '', deadline: null, risk: '', subItems: [],
  }
}

// ---------- 事项ID 自动生成（走后端：{看板前缀}-{模块字母}{序号}，如 QF-B03 / HF-C02） ----------
const boardPrefix = (b?: string) => (b === 'happy' ? 'HF' : 'QF')

async function fetchNextCode(board?: string, module?: string | null): Promise<string> {
  try {
    const { data } = await fetchNextCodeApi(board, module)
    return data.code
  } catch {
    return ''
  }
}

// 新建时：看板 / 模块变化即重新登记 ID；编辑时保持原 ID 不变
watch(
  () => [form.board, form.module],
  async () => {
    if (!editingId.value) form.taskCode = await fetchNextCode(form.board, form.module)
  },
)

// 工作模块自动补全：仅推荐当前看板下已用过的模块（走后端），保证 ID 字母延续
async function queryModuleSearch(queryString: string, cb: (results: { value: string }[]) => void) {
  try {
    const { data } = await suggestModules(form.board, queryString || undefined)
    cb(data.slice(0, 20).map((value) => ({ value })))
  } catch {
    cb([])
  }
}

// ---------- 负责人候选（打开弹窗时全量拉取，本地过滤；allow-create 支持自填新增） ----------
const ownerOptions = ref<string[]>([])
async function loadOwners() {
  try {
    const { data } = await suggestOwners()
    ownerOptions.value = data
  } catch {
    ownerOptions.value = []
  }
}
function mergeOwnerIntoOptions(owner: string | null | undefined) {
  const v = (owner ?? '').trim()
  if (v && !ownerOptions.value.includes(v)) ownerOptions.value.push(v)
}

async function open(task?: TaskListItem) {
  if (task) {
    // 编辑
    editingId.value = task.id
    try {
      const d = (await getTask(task.id)).data
      Object.assign(form, {
        taskCode: d.taskCode ?? '', board: d.board, module: d.module ?? '', title: d.title,
        description: d.description ?? '', status: d.status, priority: d.priority,
        owner: d.owner ?? '', ownerUserid: d.ownerUserid ?? '', collab: d.collab ?? '',
        pain: d.pain ?? '', nextStep: d.nextStep ?? '', deadline: d.deadline, risk: d.risk ?? '',
        subItems: [...(d.subItems ?? [])],
      })
      editorVisible.value = true
      // 候选加载后，把当前负责人并入列表（防历史数据不在派生列表时无法显示/选中）
      await loadOwners()
      mergeOwnerIntoOptions(form.owner)
    } catch (err: any) {
      editingId.value = null
      ElMessage.error('加载事项详情失败：' + errMsg(err))
    }
  } else {
    // 新建
    editingId.value = null
    Object.assign(form, emptyForm())
    form.taskCode = await fetchNextCode(form.board, form.module)
    editorVisible.value = true
    await loadOwners() // 不阻塞弹窗打开
  }
}
defineExpose({ open })

function addSubItem() {
  form.subItems?.push('')
}
function removeSubItem(i: number) {
  form.subItems?.splice(i, 1)
}

async function save() {
  if (!form.title?.trim()) {
    ElMessage.warning('请填写事项标题')
    return
  }
  // 工作模块必填：模块决定事项 ID 的字母段，留空会按新模块顺延分配字母，导致编号不延续
  if (!form.module?.trim()) {
    ElMessage.warning('请填写工作模块')
    return
  }
  saving.value = true
  try {
    const payload: TaskRequest = {
      ...form,
      title: form.title.trim(),
      module: form.module.trim(),
      subItems: (form.subItems ?? []).map((s) => s.trim()).filter(Boolean),
    }
    if (editingId.value) {
      await updateTask(editingId.value, payload)
      logOp({
        action: 'UPDATE',
        targetType: 'task',
        targetId: editingId.value,
        targetCode: form.taskCode ?? undefined,
        detail: `编辑事项 ${form.taskCode ?? '#' + editingId.value}《${form.title}》`,
      })
    } else {
      const created = (await createTask(payload)).data
      logOp({
        action: 'CREATE',
        targetType: 'task',
        targetId: created.id,
        targetCode: created.taskCode ?? undefined,
        detail: `新建事项 ${created.taskCode ?? '#' + created.id}《${created.title}》`,
      })
    }
    ElMessage.success('保存成功')
    editorVisible.value = false
    emit('saved')
  } catch (err: any) {
    ElMessage.error('保存失败：' + errMsg(err))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="editorVisible"
    width="720px"
    destroy-on-close
    class="editor-dialog"
    :class="form.board === 'happy' ? 'dlg-happy' : 'dlg-quanfa'"
  >
    <template #header>
      <div class="dlg-head">
        <div class="dlg-head-text">
          <div class="dlg-kicker">{{ editingId ? '编辑登记' : '新建登记' }} · 工作跟进台账</div>
          <div class="dlg-title">{{ editingId ? '编辑事项' : '新建事项' }}</div>
        </div>
        <div class="id-stamp" :class="form.board === 'happy' ? 'st-happy' : 'st-quanfa'" :title="'系统按 ' + boardPrefix(form.board) + '-字母+序号 自动登记'">
          <span class="id-code num">{{ form.taskCode || '——' }}</span>
          <span class="id-hint">自动登记 · 不可修改</span>
        </div>
      </div>
    </template>

    <el-form :model="form" label-position="top" class="editor-form">
      <!-- 基本信息 -->
      <div class="fg">
        <div class="fg-eyebrow"><i />基本信息</div>
        <div class="fg-grid">
          <el-form-item label="看板分组" class="fg-1">
            <div class="board-pick">
              <button
                v-for="b in BOARDS"
                :key="b.value"
                type="button"
                class="board-card"
                :class="[b.value, { on: form.board === b.value }]"
                @click="form.board = b.value"
              >
                <span class="bc-name">{{ b.label }}</span>
                <span class="bc-prefix num">{{ boardPrefix(b.value) }}</span>
              </button>
            </div>
          </el-form-item>
          <el-form-item label="工作模块" class="fg-1" required>
            <el-autocomplete
              v-model="form.module"
              :fetch-suggestions="queryModuleSearch"
              placeholder="如 数字组·配套智能手段"
              clearable
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="具体事项" class="fg-2" required>
            <el-input v-model="form.title" placeholder="一句话说清要做什么" />
          </el-form-item>
          <el-form-item label="详细描述" class="fg-2">
            <el-input v-model="form.description" type="textarea" :rows="2" placeholder="背景、范围、期望结果（可选）" />
          </el-form-item>
        </div>
      </div>

      <!-- 状态与安排 -->
      <div class="fg">
        <div class="fg-eyebrow"><i />状态与安排</div>
        <div class="fg-grid">
          <el-form-item label="当前状态" class="fg-1">
            <el-select v-model="form.status" style="width: 100%">
              <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级" class="fg-1">
            <el-select v-model="form.priority" style="width: 100%">
              <el-option v-for="p in PRIORITIES" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
          <el-form-item label="计划完成日期" class="fg-1">
            <el-date-picker v-model="form.deadline" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
          </el-form-item>
          <el-form-item label="负责人" class="fg-1">
            <el-select
              v-model="form.owner"
              filterable
              allow-create
              default-first-option
              clearable
              placeholder="选择或输入新负责人"
              style="width: 100%"
            >
              <el-option v-for="o in ownerOptions" :key="o" :label="o" :value="o" />
            </el-select>
          </el-form-item>
          <el-form-item label="协作方 · 对接人" class="fg-1">
            <el-input v-model="form.collab" placeholder="如 法务 / 于总" />
          </el-form-item>
        </div>
      </div>

      <!-- 推进信息 -->
      <div class="fg">
        <div class="fg-eyebrow"><i />推进信息</div>
        <div class="fg-grid">
          <el-form-item label="亟待解决问题 · 痛点" class="fg-2">
            <el-input v-model="form.pain" type="textarea" :rows="2" placeholder="卡点是什么、影响是什么" />
          </el-form-item>
          <el-form-item label="下一步行动" class="fg-2">
            <el-input v-model="form.nextStep" type="textarea" :rows="2" placeholder="接下来要推进的事" />
          </el-form-item>
          <el-form-item label="风险提示 · 备注" class="fg-2">
            <el-input v-model="form.risk" type="textarea" :rows="2" placeholder="风险、依赖、关联事项（如 ★QF-B02）" />
          </el-form-item>
          <el-form-item label="子项" class="fg-2">
            <div class="sub-editor">
              <div v-for="(s, i) in form.subItems" :key="i" class="sub-row">
                <span class="sub-idx num">{{ String(i + 1).padStart(2, '0') }}</span>
                <el-input v-model="form.subItems![i]" placeholder="子项名称" />
                <button type="button" class="sub-del" title="删除该子项" @click="removeSubItem(i)">✕</button>
              </div>
              <button type="button" class="sub-add" @click="addSubItem">＋ 添加子项</button>
            </div>
          </el-form-item>
        </div>
      </div>
    </el-form>

    <template #footer>
      <el-button @click="editorVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存登记</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* ---------- 新建/编辑 Dialog：台账登记卡 ----------
   el-dialog 内部结构（__header/__body/__footer 等）由 teleport 渲染，
   scoped 样式无法命中，统一放到文件末尾的非 scoped <style> 块中处理。 */

/* 头：kicker + 标题 + ID 印章 */
.dlg-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.dlg-kicker {
  font-size: 11px;
  color: var(--c-faint);
  letter-spacing: 0.14em;
}
.dlg-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--c-ink);
  margin-top: 2px;
}
.id-stamp {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 1px;
  padding: 6px 14px 5px;
  border: 1px dashed var(--c-line-strong);
  border-radius: 8px;
  background: var(--c-bg);
  min-width: 128px;
}
.id-stamp.st-quanfa {
  border-color: var(--c-quanfa-stamp-border);
  background: var(--c-quanfa-stamp-bg);
}
.id-stamp.st-happy {
  border-color: var(--c-happy-stamp-border);
  background: var(--c-happy-stamp-bg);
}
.id-stamp .id-code {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.05em;
  line-height: 1.2;
  color: var(--c-blue);
}
.id-stamp.st-happy .id-code {
  color: var(--c-happy);
}
.id-stamp .id-hint {
  font-size: 11px;
  color: var(--c-muted);
}

/* 表单分组 */
.editor-form .fg {
  border-top: 1px solid var(--c-line);
  padding-top: 12px;
  margin-top: 4px;
}
.editor-form .fg:first-child {
  border-top: none;
  padding-top: 0;
  margin-top: 0;
}
.fg-eyebrow {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-muted);
  letter-spacing: 0.08em;
  margin-bottom: 12px;
}
.fg-eyebrow i {
  width: 3px;
  height: 12px;
  border-radius: 2px;
  background: var(--c-grad-th);
}
.fg-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 18px;
}
.fg-grid .fg-1 {
  grid-column: span 1;
}
.fg-grid .fg-2 {
  grid-column: 1 / -1;
}

/* 看板双卡选择（颜色即身份：蓝=全发 / 橙=会幸福） */
.board-pick {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  width: 100%;
}
.board-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 14px;
  border: 1px solid var(--c-line);
  border-radius: 8px;
  background: var(--c-card);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}
.board-card:hover {
  border-color: var(--c-line-strong);
}
.board-card .bc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-ink-soft);
}
.board-card .bc-prefix {
  font-size: 12px;
  color: var(--c-faint);
  letter-spacing: 0.04em;
}
.board-card.quanfa.on {
  border-color: var(--c-quanfa);
  background: var(--c-quanfa-pick-bg);
  box-shadow: inset 0 0 0 1px var(--c-quanfa);
}
.board-card.quanfa.on .bc-name {
  color: var(--c-quanfa);
}
.board-card.quanfa.on .bc-prefix {
  color: var(--c-quanfa);
}
.board-card.happy.on {
  border-color: var(--c-happy);
  background: var(--c-happy-pick-bg);
  box-shadow: inset 0 0 0 1px var(--c-happy);
}
.board-card.happy.on .bc-name {
  color: var(--c-happy);
}
.board-card.happy.on .bc-prefix {
  color: var(--c-happy);
}

/* 子项编辑器 */
.sub-editor {
  width: 100%;
}
.sub-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.sub-idx {
  flex: 0 0 22px;
  text-align: center;
  font-size: 12px;
  color: var(--c-faint);
}
.sub-del {
  border: none;
  background: transparent;
  color: var(--c-faint);
  font-size: 13px;
  line-height: 1;
  padding: 4px 6px;
  border-radius: 4px;
  cursor: pointer;
}
.sub-del:hover {
  color: var(--c-st-urgent);
  background: var(--c-st-urgent-soft);
}
.sub-add {
  border: 1px dashed var(--c-line-strong);
  background: transparent;
  color: var(--c-blue);
  border-radius: 6px;
  padding: 6px 16px;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.sub-add:hover {
  background: var(--c-blue-soft);
  border-color: var(--c-blue);
}
</style>

<!-- 非 scoped：el-dialog 内部结构由 teleport 渲染到 body，
     用类名后代选择器覆盖（class 无论落在 .el-dialog 还是 .el-overlay 均生效） -->
<style>
.editor-dialog .el-dialog__header {
  padding: 16px 22px 12px;
}
.editor-dialog .el-dialog__body {
  padding: 0 22px;
  max-height: calc(100vh - 260px);
  overflow-y: auto;
}
.editor-dialog .el-dialog__footer {
  padding: 14px 22px 18px;
}
.editor-dialog .el-form-item {
  margin-bottom: 14px;
}
.editor-dialog .el-form-item__label {
  font-size: 12px;
  color: var(--c-muted);
  line-height: 1.4;
  padding-bottom: 4px;
}

/* 顶部身份色条：蓝=全发 / 橙=会幸福；同时收小默认 margin-top，保证对话框整体入屏 */
.editor-dialog.el-dialog,
.el-overlay.editor-dialog .el-dialog {
  margin-top: 4vh;
  border-top: 3px solid var(--c-quanfa);
}
.editor-dialog.dlg-happy.el-dialog,
.el-overlay.editor-dialog.dlg-happy .el-dialog {
  border-top-color: var(--c-happy);
}
</style>
