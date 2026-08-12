<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterView } from 'vue-router'
import ThemeToggle from './components/ThemeToggle.vue'
import ClockLabel from './components/ClockLabel.vue'
import OpLogPanel from './components/OpLogPanel.vue'

// ---------- 操作日志悬浮面板（贴 header 入口按钮，fixed 定位） ----------
const opPanelVisible = ref(false)
const opPanelStyle = ref<Record<string, string>>({})
const opBtnRef = ref<HTMLElement | null>(null)
const opPanelRef = ref<{ el: HTMLElement | null } | null>(null)

function toggleOpPanel() {
  if (opPanelVisible.value) {
    opPanelVisible.value = false
    return
  }
  opPanelVisible.value = true
  nextTick(positionOpPanel)
}

function positionOpPanel() {
  const btn = opBtnRef.value
  if (!btn) return
  const rect = btn.getBoundingClientRect()
  const gap = 8
  const top = rect.bottom + gap
  const maxH = Math.max(320, window.innerHeight - top - 16)
  opPanelStyle.value = {
    top: `${top}px`,
    right: `${Math.max(12, window.innerWidth - rect.right)}px`,
    height: `min(60vh, 520px, ${maxH}px)`,
    width: 'min(480px, calc(100vw - 24px))',
  }
}

function onResize() {
  if (opPanelVisible.value) positionOpPanel()
}

// 点击面板外部 + 不在按钮上时关闭；Element Plus 弹层（teleport 到 body）内不关
const EP_POPUP_SELECTORS = [
  '.el-select-dropdown',
  '.el-autocomplete-suggestion',
  '.el-picker-panel',
  '.el-date-picker',
  '.el-time-panel',
  '.el-popper',
  '.el-dropdown-menu',
  '.el-cascader__dropdown',
  '.el-color-picker__panel',
  '.el-transfer-panel',
]
function isInsideEpPopup(tgt: EventTarget | null): boolean {
  if (!tgt) return false
  let el: Element | null = (tgt as any).nodeType === 1 ? (tgt as Element) : (tgt as Element)?.parentElement ?? null
  while (el) {
    for (const sel of EP_POPUP_SELECTORS) {
      try {
        if (el.matches(sel)) return true
      } catch {
        /* IE-like fallback: skip */
      }
    }
    el = el.parentElement
  }
  return false
}
function onDocClick(e: MouseEvent) {
  if (!opPanelVisible.value) return
  const target = e.target as Node | null
  if (!target) return
  // 点在按钮上：由按钮自身 toggle 处理（已 return，避免双关）
  if (opBtnRef.value && opBtnRef.value.contains(target)) return
  // 点在面板内部：不关
  if (opPanelRef.value?.el && opPanelRef.value.el.contains(target)) return
  // 点在 Element Plus teleport 到 body 的弹层上（面板控件触发的）：不关
  if (isInsideEpPopup(target)) return
  opPanelVisible.value = false
}

onMounted(() => {
  document.addEventListener('mousedown', onDocClick)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocClick)
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="header-inner">
        <div class="brand">
          <h1 class="brand-title">工作跟进看板</h1>
        </div>
        <div class="header-actions">
          <button
            ref="opBtnRef"
            type="button"
            class="header-entry"
            :class="{ on: opPanelVisible }"
            title="查看操作日志"
            @click="toggleOpPanel"
          >
            操作日志
          </button>
          <ClockLabel />
          <ThemeToggle />
        </div>
      </div>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
    <OpLogPanel
      v-if="opPanelVisible"
      ref="opPanelRef"
      :panel-style="opPanelStyle"
      @close="opPanelVisible = false"
    />
  </div>
</template>

<style scoped>
/* 整页固定：仅数据区内部滚动 */
.app-shell {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.app-header {
  flex: 0 0 auto;
  background: var(--c-grad-header);
  box-shadow: 0 2px 12px rgba(22, 50, 126, 0.18);
  position: relative;
  z-index: 20;
}
.header-inner {
  width: 100%;
  padding: 12px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.brand {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}
.brand-title {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #ffffff;
  white-space: nowrap;
}
.brand-title::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 17px;
  border-radius: 3px;
  background: linear-gradient(180deg, #ffffff 0 50%, #A9C4FF 50%);
  margin-right: 10px;
  vertical-align: -3px;
}
.header-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 10px;
}
.header-entry {
  flex: 0 0 auto;
  padding: 7px 14px;
  border: 1px solid rgba(255, 255, 255, 0.42);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.16s ease;
  white-space: nowrap;
  font-family: inherit;
}
.header-entry:hover {
  background: rgba(255, 255, 255, 0.24);
  border-color: rgba(255, 255, 255, 0.65);
}
/* 面板打开时按钮高亮（实底白 + 品牌蓝字） */
.header-entry.on {
  background: #ffffff;
  border-color: #ffffff;
  color: #2456C9;
}
.app-main {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: row;
  overflow: hidden;
}
@media (max-width: 640px) {
  .header-inner {
    padding: 10px 16px;
  }
}
</style>
