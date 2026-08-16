<script setup lang="ts">
import { computed, ref } from 'vue'
import { STATUSES } from '../utils/taskShared'
import type { Filters } from '../utils/taskShared'

// 受控组件：草稿筛选 / 负责人候选 / 定位样式 / 生效计数均由父组件持有并下发，本组件只负责 UI 与交互事件
const props = defineProps<{
  draftFilters: Filters
  boards: { label: string; value: string }[]   // 看板选项（由父组件按 /boards 动态构造）
  ownerOptions: string[]
  panelStyle: Record<string, string>
  activeCount: number
  remoteQuery: (query: string) => void
}>()
const emit = defineEmits<{
  (e: 'apply'): void
  (e: 'reset'): void
  (e: 'close'): void
}>()

// 看板行高亮：选中的看板集合 ≠ 全部看板时为「已生效」
const boardRowOn = computed(() => {
  const all = props.boards.map((b) => b.value)
  const cur = props.draftFilters.boards
  return !(cur.length === all.length && all.every((v) => cur.includes(v)))
})

// 供父组件做「点击面板外部关闭」判定
const rootEl = ref<HTMLElement | null>(null)
defineExpose({ el: rootEl })
</script>

<template>
  <!-- 高级筛选悬浮面板（移到 toolbar 同级，board-page 作为定位包含块，避免被 toolbar overflow-x / cards-region overflow 裁剪） -->
  <div
    ref="rootEl"
    class="filter-panel"
    :style="panelStyle"
  >
    <header class="fp-head">
      <span class="fp-title">高级筛选 · <span class="num">{{ activeCount }} 项生效</span></span>
      <button type="button" class="fp-close" title="收起" aria-label="收起" @click.stop="emit('close')">✕</button>
    </header>
    <div class="fp-body">
      <div class="filter-body">
        <div class="filter-row" :class="{ on: boardRowOn }">
          <div class="filter-icon">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/></svg>
          </div>
          <div class="filter-label">看板分组</div>
          <el-select v-model="draftFilters.boards" multiple collapse-tags placeholder="全选 / 可多选" style="width: 100%">
            <el-option v-for="b in boards" :key="b.value" :label="b.label" :value="b.value" />
          </el-select>
        </div>
        <div class="filter-row" :class="{ on: draftFilters.statuses.length > 0 }">
          <div class="filter-icon">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
          </div>
          <div class="filter-label">事项状态</div>
          <el-select v-model="draftFilters.statuses" multiple collapse-tags placeholder="全选 / 可多选" style="width: 100%">
            <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
          </el-select>
        </div>
        <div class="filter-row" :class="{ on: draftFilters.owners.length > 0 }">
          <div class="filter-icon">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
          </div>
          <div class="filter-label">负责人</div>
          <el-select
            v-model="draftFilters.owners"
            multiple
            filterable
            remote
            collapse-tags
            collapse-tags-tooltip
            :remote-method="remoteQuery"
            placeholder="输入姓名搜索 / 可多选"
            style="width: 100%"
          >
            <el-option v-for="o in ownerOptions" :key="o" :label="o" :value="o" />
          </el-select>
        </div>
        <div class="filter-row" :class="{ on: draftFilters.dateRange.length === 2 && draftFilters.dateRange[0] && draftFilters.dateRange[1] }">
          <div class="filter-icon">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>
          </div>
          <div class="filter-label">截止日期</div>
          <el-date-picker
            v-model="draftFilters.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="起始日期"
            end-placeholder="结束日期"
            style="width: 100%"
          />
        </div>
      </div>
    </div>
    <div class="fp-foot">
      <el-button plain size="small" @click.stop="emit('reset')">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>
        重置
      </el-button>
      <el-button type="primary" size="small" @click.stop="emit('apply')">
        应用筛选
      </el-button>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 高级筛选悬浮面板（跟进面板同视觉风格） ---------- */
.filter-panel {
  position: absolute;
  z-index: 7;
  background: var(--c-card);
  border: 1px solid var(--c-line-strong);
  border-radius: 10px;
  box-shadow: 0 10px 30px rgba(15, 27, 61, 0.22); /* 浅色：蓝雾台账风投影 */
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: fp-in 0.16s ease;
}
/* 深色：加重投影 + 外层细蓝描边，提升「浮起感」与对比度 */
[data-theme='dark'] .filter-panel {
  box-shadow:
    0 12px 36px rgba(0, 0, 0, 0.65),
    0 2px 6px rgba(0, 0, 0, 0.4),
    0 0 0 1px rgba(91, 138, 230, 0.22) inset;
  border-color: #31426A;
}
@keyframes fp-in {
  from { opacity: 0; transform: translateX(-8px); }
  to   { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .filter-panel { animation: none; }
}
.fp-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--c-line);
  background: var(--c-bg);
}
.fp-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-ink);
}
.fp-close {
  flex: 0 0 auto;
  border: none;
  background: transparent;
  color: var(--c-faint);
  font-size: 14px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 5px;
  cursor: pointer;
}
.fp-close:hover {
  color: var(--c-ink);
  background: var(--c-row-hover);
}
.fp-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 14px 4px;
}
.fp-foot {
  flex: 0 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px 12px;
  border-top: 1px solid var(--c-line);
  background: var(--c-bg);
  gap: 10px;
}
.fp-foot .el-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 卡片式筛选项（面板 body 内部复用） */
.filter-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: var(--c-row-zebra);
  border: 1px solid var(--c-line);
  border-radius: 10px;
  padding: 10px 14px;
}
.filter-row {
  display: grid;
  grid-template-columns: 28px 84px 1fr;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border-radius: 8px;
  transition: background 0.15s ease;
}
.filter-row:hover {
  background: color-mix(in srgb, var(--c-blue) 4%, transparent);
}
.filter-row.on {
  background: color-mix(in srgb, var(--c-blue) 10%, var(--c-card));
  box-shadow: inset 2px 0 0 var(--c-blue);
}
.filter-icon {
  width: 28px;
  height: 28px;
  border-radius: 7px;
  background: color-mix(in srgb, var(--c-blue) 12%, var(--c-card));
  color: var(--c-blue);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.filter-row.on .filter-icon {
  background: var(--c-blue);
  color: #fff;
}
.filter-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-ink);
  white-space: nowrap;
}
.filter-row.on .filter-label {
  color: var(--c-blue);
}
</style>
