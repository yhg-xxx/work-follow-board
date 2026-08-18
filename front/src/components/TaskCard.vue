<script setup lang="ts">
import type { TaskListItem } from '../api/task'
import type { BoardMap } from '../utils/taskShared'
import {
  cardAccent,
  cardTabTitle,
  deadlineState,
  fmtDate,
  fmtShort,
  hl,
  priClass,
  riskIsStar,
  statusDot,
} from '../utils/cardFormat'
import TruncTip from './TruncTip.vue'

defineProps<{
  task: TaskListItem
  boardMap: BoardMap
  /** 搜索关键词（传给 hl 高亮） */
  keyword: string
  isManualSort: boolean
  /** 是否被勾选（selectedIds.has(task.id)） */
  checked: boolean
  /** 跟进面板是否展开（expandedId === task.id） */
  logOpen: boolean
}>()
const emit = defineEmits<{
  (e: 'toggle-pin'): void
  (e: 'toggle-check', checked: boolean): void
  (e: 'open-follow'): void
  (e: 'edit'): void
}>()
</script>

<template>
  <article
    class="task-card"
    :class="{ draggable: isManualSort }"
    :style="{ '--spine': cardAccent(task, boardMap), '--tab-bg': cardAccent(task, boardMap) }"
    :data-id="task.id"
  >
    <span
      class="card-tab"
      :class="{ pinned: task.pinned }"
      :style="{ background: 'var(--tab-bg)' }"
      :title="(task.pinned ? '取消置顶：' : '置顶：') + cardTabTitle(task.board, boardMap) + '（' + (task.taskCode || '——') + '）'"
      role="button"
      @click.stop="emit('toggle-pin')"
    >
      <svg v-if="task.pinned" class="tab-pin" viewBox="0 0 24 24" aria-hidden="true"><path d="M14 3l7 7-3.5 1.5L12 18l-4-4 6.5-5.5L16 5l-2-2zM4 20l4-4"/></svg>
      {{ task.taskCode || '——' }}
    </span>

    <header class="card-head">
      <span v-if="isManualSort" class="drag-handle" title="拖拽调整顺序" aria-hidden="true">⋮⋮</span>
      <div class="card-title-wrap">
        <TruncTip tag="h3" class="card-title" :html="hl(task.title, keyword)" :lines="1" />
      </div>
      <el-checkbox
        class="card-check"
        :model-value="checked"
        @click.stop
        @change="(v: boolean | string | number) => emit('toggle-check', !!v)"
      />
    </header>

    <div class="card-chips">
      <!-- 状态：只读展示，修改需进编辑弹窗 -->
      <span class="status-chip" :class="statusDot(task.status)">
        <i class="dot" />{{ task.status }}
      </span>
      <span class="pri-pill" :class="priClass(task.priority)">{{ task.priority }}</span>
      <TruncTip tag="span" class="card-owner" :text="task.owner || '未指派'" :lines="1" />
    </div>

    <TruncTip v-if="task.module" tag="p" class="card-module" :html="hl(task.module, keyword)" :lines="1" />

    <!-- 除跟进记录外的其余字段 -->
    <dl class="card-lines">
      <div v-if="task.description" class="card-line">
        <dt>描述</dt>
        <dd><TruncTip :html="hl(task.description, keyword)" :lines="2" /></dd>
      </div>
      <div v-if="task.collab" class="card-line">
        <dt>协作</dt>
        <dd><TruncTip :text="task.collab" :lines="1" /></dd>
      </div>
      <div v-if="task.pain" class="card-line">
        <dt>痛点</dt>
        <dd><TruncTip :html="hl(task.pain, keyword)" :lines="2" /></dd>
      </div>
      <div v-if="task.nextStep" class="card-line">
        <dt>下一步</dt>
        <dd><TruncTip :html="hl(task.nextStep, keyword)" :lines="2" /></dd>
      </div>
      <div v-if="task.risk" class="card-line">
        <dt>风险</dt>
        <dd :class="{ 'risk-star': riskIsStar(task.risk) }"><TruncTip :html="hl(task.risk, keyword)" :lines="2" /></dd>
      </div>
      <div v-if="task.subItems?.length" class="card-line">
        <dt>子项</dt>
        <dd class="card-sub-list">
          <span v-for="(s, i) in task.subItems" :key="i" class="sub-tag">{{ s }}</span>
        </dd>
      </div>
    </dl>

    <footer class="card-foot">
      <span v-if="task.updateDate" class="card-field" :title="'更新日期 ' + fmtDate(task.updateDate)">
        <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
        更新 {{ fmtShort(task.updateDate) }}
      </span>
      <span class="card-field" :title="'截止日期 ' + fmtDate(task.deadline)">
        <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>
        <span class="deadline num" :class="deadlineState(task.deadline)">{{ fmtShort(task.deadline) }}</span>
      </span>
      <button
        type="button"
        class="card-open"
        :class="[{ has: (task.logCount ?? 0) > 0 }, { on: logOpen }]"
        :aria-expanded="logOpen"
        @click.stop="emit('open-follow')"
      >
        <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-5 4V5z"/><path d="M8 9h8M8 13h5"/></svg>
        跟进 {{ task.logCount ?? 0 }} 条 ›
      </button>
      <button class="card-edit" @click.stop="emit('edit')">编辑</button>
    </footer>
  </article>
</template>

<style scoped>
/* 文件夹卡片：左上角身份色标签 + 底部身份色脊线（--spine / --tab-bg 由看板 accent 内联驱动） */
.task-card {
  --spine: transparent;
  --tab-bg: var(--c-quanfa);
  --card-shadow: var(--shadow-card);
  position: relative;
  margin-top: 12px;
  padding: 16px 14px 12px;
  background: var(--c-card);
  border: 1px solid var(--c-line);
  border-radius: 10px;
  box-shadow: var(--card-shadow), inset 0 -3px 0 0 var(--spine);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: box-shadow 0.18s ease, transform 0.18s ease, border-color 0.18s ease;
}
.task-card:hover {
  --card-shadow: var(--shadow-card-hover);
  border-color: var(--c-line-strong);
  transform: translateY(-2px);
}

/* 文件夹标签：看板身份色 + 事项ID（点击即置顶/取消置顶） */
.card-tab {
  position: absolute;
  top: -12px;
  left: 14px;
  height: 24px;
  padding: 0 12px;
  border-radius: 8px 8px 4px 4px;
  display: inline-flex;
  align-items: center;
  font-family: var(--font-mono), serif;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #fff;
  box-shadow: inset 0 -2px 0 rgba(0, 0, 0, 0.16);
  user-select: none;
  white-space: nowrap;
  cursor: pointer;
  transition: filter 0.15s ease, box-shadow 0.15s ease;
}
.card-tab:hover {
  filter: brightness(1.08);
  box-shadow: inset 0 -2px 0 rgba(0, 0, 0, 0.16), 0 2px 8px rgba(0, 0, 0, 0.18);
}
/* 置顶卡片：tab 内小图钉 */
.card-tab .tab-pin {
  width: 10px;
  height: 10px;
  margin-right: 5px;
  fill: currentColor;
  flex: 0 0 auto;
}
/* 深色主题下身份色偏亮，标签文字改用深色保证对比度 */
[data-theme='dark'] .card-tab { color: #0D1426; }

/* 卡片头部：标题 + 拖拽手柄 + 勾选 */
.card-head {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}
/* 标题容器：负责占位（气泡定位已下沉到 TruncTip 组件根） */
.card-title-wrap {
  flex: 1;
  min-width: 0;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--c-ink);
}
.card-check {
  flex: 0 0 auto;
  margin: -2px 0 0 2px;
}

/* 拖拽手柄：仅手动排序模式可见（v-if），点击拖动卡片调整顺序 */
.drag-handle {
  flex: 0 0 auto;
  margin-top: -1px;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 16px;
  line-height: 1;
  letter-spacing: 0.06em;
  color: var(--c-faint);
  cursor: grab;
  user-select: none;
  touch-action: none;
}
.drag-handle:hover {
  color: var(--c-blue);
  background: var(--c-blue-soft);
}
.task-card.draggable .drag-handle:active {
  cursor: grabbing;
}

/* 模块名（截断由 TruncTip 内层承担） */
.card-module {
  font-size: 12px;
  color: var(--c-muted);
}

/* 状态 / 优先级 / 负责人 */
.card-chips {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}
.card-owner {
  margin-left: auto;
  max-width: 42%;
  font-size: 12px;
  color: var(--c-muted);
}

/* 卡片底部：更新/截止 / 跟进入口 / 编辑 */
.card-foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  row-gap: 4px;
  gap: 10px;
  padding-top: 9px;
  border-top: 1px dashed var(--c-line);
  font-size: 12px;
  color: var(--c-muted);
  /* 无论卡片中间字段（描述/协作/痛点等）是否缺失，操作区始终贴底 */
  margin-top: auto;
}
.card-field {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}
.card-field svg {
  flex: 0 0 auto;
  opacity: 0.75;
}
.card-open {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: transparent;
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-faint);
  white-space: nowrap;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 5px;
}
.card-open svg {
  flex: 0 0 auto;
}
.card-open:hover {
  background: var(--c-bg);
}
.card-open.has {
  color: var(--c-blue);
}
.card-open.on {
  color: var(--c-blue);
  background: var(--c-blue-soft);
}
.card-edit {
  flex: 0 0 auto;
  border: none;
  background: transparent;
  font-size: 12px;
  color: var(--c-muted);
  cursor: pointer;
  padding: 1px 6px;
  border-radius: 5px;
}
.card-edit:hover {
  color: var(--c-blue);
  background: var(--c-blue-soft);
}

/* 卡片字段行：标签 + 内容（除跟进记录外的全部字段） */
.card-lines {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.card-line {
  display: flex;
  gap: 6px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--c-ink-soft);
}
.card-line dt {
  flex: 0 0 auto;
  color: var(--c-faint);
  padding-top: 1px;
}
.card-line dd {
  flex: 1;
  min-width: 0;
}
.card-line dd.risk-star {
  color: var(--c-st-urgent);
  font-weight: 600;
}
.card-sub-list {
  display: flex !important;
  flex-wrap: wrap;
  gap: 4px;
}

/* 状态圆点（只读展示） */
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--c-ink-soft);
  padding: 2px 6px;
  border-radius: 6px;
  white-space: nowrap;
}
.status-chip .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-st-idle);
}
.status-chip.st-ongoing .dot { background: var(--c-st-ongoing); }
.status-chip.st-urgent .dot { background: var(--c-st-urgent); }
.status-chip.st-done .dot { background: var(--c-st-done); }
.status-chip.st-follow .dot { background: var(--c-st-follow); }

/* 优先级 */
.pri-pill {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
  white-space: nowrap;
}
.pri-high { color: var(--c-pri-high); background: var(--c-st-urgent-soft); }
.pri-mid { color: var(--c-pri-mid); background: var(--c-st-follow-soft); }
.pri-low { color: var(--c-pri-low); background: var(--c-st-idle-soft); }

.deadline {
  color: var(--c-muted);
  white-space: nowrap;
  font-size: 13px;
}
.deadline.dl-overdue { color: var(--c-st-urgent); font-weight: 600; }
.deadline.dl-near { color: var(--c-st-follow); font-weight: 600; }

/* 子项标签（卡片子项展示） */
.sub-tag {
  font-size: 12px;
  color: var(--c-ink-soft);
  background: var(--c-bg);
  border: 1px solid var(--c-line);
  border-radius: 6px;
  padding: 1px 8px;
}
</style>
