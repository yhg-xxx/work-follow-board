<script setup lang="ts">
import type { MenuGroup, NavGroupId } from '../utils/taskShared'

defineProps<{
  collapsed: boolean
  navAllCount: number
  menuGroups: MenuGroup[]
  expandedMenus: Set<NavGroupId>
  currentGroup: NavGroupId
  currentModule: string | null
}>()
const emit = defineEmits<{
  (e: 'toggle-collapse'): void
  (e: 'pick-all'): void
  (e: 'pick-group', gid: NavGroupId): void
  (e: 'pick-module', gid: NavGroupId, mod: string | null): void
  (e: 'toggle-menu', gid: NavGroupId): void
}>()
</script>

<template>
  <!-- 左侧：主导航菜单栏（三个一级分组 + 二级模块） -->
  <aside class="sidebar" :class="{ collapsed }" aria-label="主导航">
    <!-- 侧栏顶部：收起 / 打开按钮 -->
    <div class="sb-rail">
      <button
        type="button"
        class="sb-collapse"
        :title="collapsed ? '打开边栏' : '收起边栏'"
        :aria-label="collapsed ? '打开边栏' : '收起边栏'"
        :aria-expanded="!collapsed"
        @click="emit('toggle-collapse')"
      >
        <svg
          class="sb-chevron"
          :class="{ open: collapsed }"
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        ><path d="M15 6l-6 6 6 6" /></svg>
      </button>
    </div>

    <nav class="sb-nav" aria-label="看板分组">
      <!-- 全部看板（无二级菜单） -->
      <button
        type="button"
        class="sb-item sb-all"
        :class="{ on: currentGroup === 'all' }"
        @click="emit('pick-all')"
      >
        <span class="sb-icon" style="background: #4B6FB8">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/></svg>
        </span>
        <span class="sb-label">全部看板</span>
        <span class="sb-count num">{{ navAllCount }}</span>
      </button>

      <!-- 三个一级分组：全发 / 会幸福 / 临时事项 -->
      <div
        v-for="g in menuGroups"
        :key="g.id"
        class="sb-group"
      >
        <div
          class="sb-item sb-group-head"
          :class="{ on: currentGroup === g.id && !currentModule }"
          :style="{ '--accent': g.accent }"
        >
          <button
            type="button"
            class="sb-item-click"
            @click="emit('pick-group', g.id)"
          >
            <span class="sb-icon" :style="{ background: g.accent }">
              <svg v-if="g.id === 'quanfa'" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h10"/></svg>
              <svg v-else-if="g.id === 'happy'" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 11s3.5-6 9-6 9 6 9 6v5a2 2 0 0 1-2 2h-2v-6h-2v6h-2v-6h-2v6h-2v-6h-2v6H7v-6H5v6H3v-5z"/></svg>
              <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 2l2.4 5.6L20 8.5l-4.2 3.9L16.8 18 12 15.5 7.2 18l1-5.6L4 8.5l5.6-.9L12 2z"/></svg>
            </span>
            <span class="sb-label">{{ g.label }}</span>
            <span class="sb-count num">{{ g.count }}</span>
          </button>
          <button
            type="button"
            class="sb-toggle"
            :aria-expanded="expandedMenus.has(g.id)"
            :aria-label="`展开${g.label}的模块`"
            @click.stop="emit('toggle-menu', g.id)"
          >
            <i class="caret" :class="{ open: expandedMenus.has(g.id) }"/>
          </button>
        </div>
        <!-- 二级菜单：工作模块（点击展开/收起） -->
        <div v-if="expandedMenus.has(g.id) && g.modules.length" class="sb-sub-list">
          <button
            v-for="m in g.modules"
            :key="g.id + ':' + m.name"
            type="button"
            class="sb-sub-item"
            :class="{ on: currentGroup === g.id && currentModule === m.name }"
            :style="{ '--accent': g.accent }"
            @click="emit('pick-module', g.id, m.name)"
          >
            <i class="sb-sub-bar"/>
            <span class="sb-sub-label" :title="m.name">{{ m.name }}</span>
            <span class="sb-sub-count num">{{ m.count }}</span>
          </button>
        </div>
        <div v-if="expandedMenus.has(g.id) && !g.modules.length" class="sb-empty">
          暂无工作模块
        </div>
      </div>
    </nav>

    <div class="sb-foot">
      <div class="sb-foot-line"><span class="dot qf"/>全发 <b class="num">{{ menuGroups[0]?.count ?? 0 }}</b></div>
      <div class="sb-foot-line"><span class="dot hp"/>会幸福 <b class="num">{{ menuGroups[1]?.count ?? 0 }}</b></div>
      <div class="sb-foot-line"><span class="dot tp"/>临时 <b class="num">{{ menuGroups[2]?.count ?? 0 }}</b></div>
    </div>
  </aside>
</template>

<style scoped>
/* 左侧：侧栏（随主题：浅色为柔和浅面，深色为最深一层） */
.sidebar {
  flex: 0 0 240px;
  width: 240px;
  background: linear-gradient(180deg, var(--c-sidebar-bg) 0%, var(--c-sidebar-bg-deep) 100%);
  color: var(--c-sidebar-ink);
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--c-sidebar-border);
  position: relative;
  overflow: hidden;
  transition: width 0.22s ease, flex-basis 0.22s ease;
}
/* 收起：仅保留一条窄栏（含「打开边栏」按钮） */
.sidebar.collapsed {
  flex-basis: 52px;
  width: 52px;
}
.sidebar::after {
  /* 右侧高光装饰条 */
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 1px;
  background: var(--c-scrollbar-sb);
  pointer-events: none;
  opacity: 0.6;
}

/* 侧栏顶部：收起 / 打开按钮条 */
.sb-rail {
  flex: 0 0 auto;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 10px;
  border-bottom: 1px solid var(--c-sidebar-border);
  transition: padding 0.22s ease;
}
.sidebar.collapsed .sb-rail {
  justify-content: center;
  padding: 0;
  border-bottom: none;
}
.sb-collapse {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--c-sidebar-muted);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.sb-collapse:hover {
  background: var(--c-sidebar-hover);
  color: var(--c-sidebar-ink);
}
.sb-collapse:focus-visible {
  outline: 2px solid var(--c-blue);
  outline-offset: 1px;
}
/* 箭头随收起/展开平滑旋转 */
.sb-chevron {
  display: block;
  transition: transform 0.22s ease;
}
.sb-chevron.open {
  transform: rotate(180deg);
}

/* 收起时导航与底部统计淡出（配合侧栏宽度收缩，而非瞬间消失） */
.sb-nav,
.sb-foot {
  opacity: 1;
  visibility: visible;
  transition: opacity 0.16s ease;
}
.sidebar.collapsed .sb-nav,
.sidebar.collapsed .sb-foot {
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
  transition: opacity 0.16s ease, visibility 0s linear 0.16s;
}

.sb-nav {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sb-nav::-webkit-scrollbar { width: 6px; }
.sb-nav::-webkit-scrollbar-thumb { background: var(--c-scrollbar-sb); border-radius: 3px; }
.sb-nav::-webkit-scrollbar-thumb:hover { background: var(--c-scrollbar-sb-hover); }

/* 一级菜单项（全部看板） */
.sb-item {
  display: flex;
  align-items: center;
  gap: 10px;
  border-radius: 8px;
  color: var(--c-sidebar-ink);
  user-select: none;
  transition: background 0.15s, color 0.15s;
}
.sb-item.sb-all {
  padding: 9px 12px;
  border: none;
  background: transparent;
  width: 100%;
  cursor: pointer;
  text-align: left;
  font: inherit;
  margin-bottom: 4px;
}
.sb-item.sb-all:hover { background: var(--c-sidebar-hover); }
.sb-item.sb-all.on {
  background: var(--c-sidebar-active-bg);
  color: var(--c-sidebar-on-ink);
  box-shadow: inset 0 0 0 1px var(--c-sidebar-active-bar);
}

/* 一级：分组（含展开箭头） */
.sb-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sb-item.sb-group-head {
  padding: 0;
  background: transparent;
  position: relative;
}
.sb-item.sb-group-head.on {
  background: var(--c-sidebar-active-bg);
  color: var(--c-sidebar-on-ink);
}
.sb-item.sb-group-head::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 0;
  border-radius: 2px;
  background: var(--accent, var(--c-sidebar-active-bar));
  transition: height 0.15s;
}
.sb-item.sb-group-head.on::before { height: 20px; }
.sb-item-click {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 6px 9px 12px;
  background: transparent;
  border: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
  text-align: left;
  border-radius: 8px;
}
.sb-item-click:hover { background: var(--c-sidebar-hover); }
.sb-item.sb-group-head.on .sb-item-click:hover { background: transparent; }

.sb-toggle {
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  margin-right: 6px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--c-sidebar-muted);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.sb-toggle:hover { background: var(--c-sidebar-hover); color: var(--c-sidebar-ink); }
.sb-toggle .caret {
  width: 0;
  height: 0;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid currentColor;
  transition: transform 0.18s ease;
  transform: rotate(-90deg);
}
.sb-toggle .caret.open { transform: rotate(0deg); }

.sb-icon {
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  border-radius: 7px;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.12);
}
.sb-label {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sb-count {
  flex: 0 0 auto;
  min-width: 22px;
  padding: 1px 7px;
  border-radius: 10px;
  background: var(--c-sidebar-count-bg);
  color: var(--c-sidebar-muted);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  text-align: center;
}
.sb-item.on .sb-count { background: var(--c-sidebar-count-on-bg); color: var(--c-sidebar-on-ink); }

/* 二级：模块子菜单 */
.sb-sub-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 4px 0 6px 16px;
}
.sb-sub-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px 7px 18px;
  background: transparent;
  border: none;
  border-radius: 7px;
  color: var(--c-sidebar-muted);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  width: 100%;
  transition: background 0.15s, color 0.15s;
}
.sb-sub-item:hover { background: var(--c-sidebar-hover); color: var(--c-sidebar-ink); }
.sb-sub-item.on {
  background: var(--c-sidebar-sub-on-bg);
  color: var(--c-sidebar-on-ink);
}
.sb-sub-bar {
  position: absolute;
  left: 6px;
  top: 50%;
  transform: translateY(-50%);
  width: 2px;
  height: 0;
  border-radius: 2px;
  background: var(--accent, var(--c-sidebar-active-bar));
  transition: height 0.15s;
}
.sb-sub-item.on .sb-sub-bar { height: 14px; }
.sb-sub-label {
  flex: 1 1 auto;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sb-sub-count {
  flex: 0 0 auto;
  min-width: 18px;
  padding: 0 6px;
  border-radius: 9px;
  font-size: 11px;
  line-height: 1.4;
  text-align: center;
  color: var(--c-sidebar-muted);
  background: var(--c-sidebar-count-bg);
}
.sb-sub-item.on .sb-sub-count { color: var(--c-sidebar-on-ink); background: var(--c-sidebar-count-on-bg); }

.sb-empty {
  padding: 6px 16px 10px 30px;
  font-size: 12px;
  color: var(--c-sidebar-muted);
  font-style: italic;
}

.sb-foot {
  flex: 0 0 auto;
  padding: 12px 16px 14px;
  border-top: 1px solid var(--c-sidebar-border);
  background: var(--c-sidebar-foot-bg);
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.sb-foot-line {
  font-size: 12px;
  color: var(--c-sidebar-muted);
  display: flex;
  align-items: center;
  gap: 8px;
}
.sb-foot-line b {
  color: var(--c-sidebar-ink);
  font-weight: 700;
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.sb-foot-line .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.sb-foot-line .dot.qf { background: var(--c-quanfa); }
.sb-foot-line .dot.hp { background: var(--c-happy); }
.sb-foot-line .dot.tp { background: var(--c-temp); }

/* 窄屏：侧栏改顶部横条，收起仅隐藏导航内容、保持整行宽 */
@media (max-width: 860px) {
  .sidebar {
    flex: 0 0 auto;
    width: 100%;
    max-height: 42vh;
    border-right: none;
    border-bottom: 1px solid var(--c-sidebar-border);
  }
  .sidebar.collapsed {
    flex-basis: auto;
    width: 100%;
  }
  .sidebar.collapsed .sb-rail {
    justify-content: flex-start;
    border-bottom: 1px solid var(--c-sidebar-border);
  }
  .sidebar::after { display: none; }
  .sb-foot {
    flex-direction: row;
    flex-wrap: wrap;
    gap: 10px 18px;
  }
}
</style>
