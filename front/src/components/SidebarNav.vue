<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import logoIcon from '../assets/logo-icon.png'
import type { MenuGroup, NavGroupId } from '../utils/taskShared'
import { PRESET_COLORS } from '../utils/taskShared'

const props = defineProps<{
  collapsed: boolean
  navAllCount: number
  menuGroups: MenuGroup[]
  expandedMenus: Set<NavGroupId>
  currentGroup: NavGroupId
  currentModule: string | null
  /** 看板 code → 看板 id（重命名/改色/删除按 id 调后端） */
  boardIdMap: Record<string, number>
}>()
const emit = defineEmits<{
  (e: 'toggle-collapse'): void
  (e: 'pick-all'): void
  (e: 'pick-group', gid: NavGroupId): void
  (e: 'pick-module', gid: NavGroupId, mod: string | null): void
  (e: 'toggle-menu', gid: NavGroupId): void
  (e: 'create-board', form: { code: string; name: string; prefix: string; accent: string }): void
  (e: 'rename-board', payload: { id: number; name: string }): void
  (e: 'recolor-board', payload: { id: number; accent: string }): void
  (e: 'delete-board', id: number): void
  (e: 'create-module', payload: { board: string; name: string }): void
  (e: 'rename-module', payload: { board: string; from: string; to: string }): void
  (e: 'delete-module', payload: { board: string; name: string }): void
}>()

// ---------- 三点菜单浮层（fixed 定位，参照 DeepSeek 对话列表：悬停出 ⋯ → 弹菜单） ----------
type MenuKind = 'board' | 'module'
type MenuState =
  | { kind: 'board'; code: string; label: string; accent: string }
  | { kind: 'module'; board: string; name: string }
  | null
const menu = ref<MenuState>(null)
const menuPos = ref({ x: 0, y: 0 })
const menuRef = ref<HTMLElement | null>(null)
// 「改配色」展开 6 色圆点行
const recolorOpen = ref(false)

function anchorMenu(ev: MouseEvent, w: number, h: number) {
  const rect = (ev.currentTarget as HTMLElement).getBoundingClientRect()
  // 悬浮层左对齐触发按钮、出现在按钮右下；贴近屏幕右/下边缘时自动回退到可见区域
  const x = Math.max(8, Math.min(rect.left, window.innerWidth - w - 8))
  const y = Math.max(8, Math.min(rect.bottom + 4, window.innerHeight - h - 8))
  return { x, y }
}
function openMenu(state: MenuState, ev: MouseEvent, w = 172, h = 168) {
  menu.value = state
  recolorOpen.value = false
  menuPos.value = anchorMenu(ev, w, h)
}
function isMenuOpen(kind: MenuKind, key?: string): boolean {
  const m = menu.value
  if (!m || m.kind !== kind) return false
  if (key === undefined) return true
  if ('code' in m) return m.code === key
  if ('name' in m) return m.name === key
  return false
}

function openBoardMenu(ev: MouseEvent, g: MenuGroup) {
  openMenu({ kind: 'board', code: g.id, label: g.label, accent: g.accent }, ev, 172, 168)
}
function openModuleMenu(ev: MouseEvent, g: MenuGroup, m: { name: string; count: number }) {
  openMenu({ kind: 'module', board: g.id, name: m.name }, ev, 172, 108)
}
function closeMenu() {
  menu.value = null
}

// 点击浮层外部 / 滚动 / Esc → 关闭
function onDocMousedown(e: MouseEvent) {
  if (menu.value && menuRef.value && !menuRef.value.contains(e.target as Node)) closeMenu()
  if (boardFormVisible.value && boardFormRef.value && !boardFormRef.value.contains(e.target as Node)) closeBoardForm()
}
function onDocKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    closeMenu()
    closeBoardForm()
    cancelEditing()
    cancelAdding()
  }
}
function onDocScroll() {
  if (menu.value || boardFormVisible.value) {
    closeMenu()
    closeBoardForm()
  }
}
onMounted(() => {
  document.addEventListener('mousedown', onDocMousedown)
  document.addEventListener('keydown', onDocKeydown)
  document.addEventListener('scroll', onDocScroll, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocMousedown)
  document.removeEventListener('keydown', onDocKeydown)
  document.removeEventListener('scroll', onDocScroll, true)
})

// ---------- 行内重命名 / 新建模块（DeepSeek 式：label 原位变输入框） ----------
const editing = ref<{ kind: 'board' | 'module'; board: string; name: string; value: string } | null>(null)
const addingModule = ref<{ board: string; value: string } | null>(null)
const editInputRef = ref<HTMLInputElement | null>(null)
// 模板 v-model 用的读写代理（editing/addingModule 本身为 null 时返回空串）
const editingValue = computed({
  get: () => editing.value?.value ?? '',
  set: (v: string) => {
    if (editing.value) editing.value.value = v
  },
})
const addingValue = computed({
  get: () => addingModule.value?.value ?? '',
  set: (v: string) => {
    if (addingModule.value) addingModule.value.value = v
  },
})

function startEdit(kind: 'board' | 'module', board: string, name: string) {
  closeMenu()
  editing.value = { kind, board, name, value: name }
}
const isEditingBoard = (code: string) => editing.value?.kind === 'board' && editing.value.board === code
const isEditingModule = (board: string, name: string) =>
  editing.value?.kind === 'module' && editing.value.board === board && editing.value.name === name
function confirmEdit() {
  const ed = editing.value
  if (!ed) return
  const val = ed.value.trim()
  if (val && val !== ed.name) {
    if (ed.kind === 'board') {
      const id = props.boardIdMap[ed.board]
      if (id != null) emit('rename-board', { id, name: val })
    } else {
      emit('rename-module', { board: ed.board, from: ed.name, to: val })
    }
  }
  editing.value = null
}
function cancelEditing() {
  editing.value = null
}
watch([editing, addingModule], (v) => {
  if (v[0] || v[1]) nextTick(() => editInputRef.value?.focus())
})

function startAddModule(g: MenuGroup | undefined) {
  if (!g) return
  closeMenu()
  if (!expandedMenusProp.has(g.id)) emit('toggle-menu', g.id)
  addingModule.value = { board: g.id, value: '' }
}
const isAddingModule = (board: string) => addingModule.value?.board === board
function confirmAddModule() {
  const am = addingModule.value
  if (!am) return
  const val = am.value.trim()
  if (val) emit('create-module', { board: am.board, name: val })
  addingModule.value = null
}
function cancelAdding() {
  addingModule.value = null
}

// ---------- 菜单内容（按类型取对应变体，模板内可直接访问，避免 TS 无法收窄） ----------
const menuBoard = computed(() => (menu.value?.kind === 'board' ? menu.value : null))
const menuModule = computed(() => (menu.value?.kind === 'module' ? menu.value : null))
const isModuleMenuOpen = (board: string, name: string) =>
  menu.value?.kind === 'module' && menu.value.board === board && menu.value.name === name
/** 改配色：点选 6 色即提交并关闭 */
function onRecolor(accent: string) {
  const code = menu.value?.kind === 'board' ? menu.value.code : ''
  const id = props.boardIdMap[code]
  if (id != null) emit('recolor-board', { id, accent })
  closeMenu()
}
/** 删除看板：由菜单入口触发（确认逻辑在父组件） */
function onDeleteBoardFromMenu() {
  const code = menu.value?.kind === 'board' ? menu.value.code : ''
  const id = props.boardIdMap[code]
  if (id != null) emit('delete-board', id)
  closeMenu()
}
/** 删除模块：由菜单入口触发（确认逻辑在父组件） */
function onDeleteModuleFromMenu() {
  const m = menu.value?.kind === 'module' ? menu.value : null
  if (m) emit('delete-module', { board: m.board, name: m.name })
  closeMenu()
}

// ---------- 新建看板（弹出悬浮框：名称 / 前缀 / 6 色；代码由事项前缀派生，不再单独填写） ----------
const boardFormVisible = ref(false)
const boardFormPos = ref({ x: 0, y: 0 })
const boardFormRef = ref<HTMLElement | null>(null)
const boardForm = reactive({ name: '', prefix: '', accent: PRESET_COLORS[0] ?? '#2B59C3' })
function openBoardForm(ev: MouseEvent) {
  closeMenu()
  Object.assign(boardForm, { name: '', prefix: '', accent: PRESET_COLORS[0] ?? '#2B59C3' })
  boardFormVisible.value = true
  boardFormPos.value = anchorMenu(ev, 236, 214)
  nextTick(() => boardFormRef.value?.querySelector('input')?.focus())
}
function closeBoardForm() {
  boardFormVisible.value = false
}
function submitBoardForm() {
  const name = boardForm.name.trim()
  if (!name) {
    ElMessage.warning('请填写看板名称')
    return
  }
  const prefix = boardForm.prefix.trim().toUpperCase()
  if (!/^[A-Z]{1,2}$/.test(prefix)) {
    ElMessage.warning('事项前缀需为 1-2 位大写字母，如 LS')
    return
  }
  // 看板代码与事项前缀保持一致（后端要求小写），如前缀 LS → 代码 ls
  const code = prefix.toLowerCase()
  emit('create-board', { code, name, prefix, accent: boardForm.accent })
  closeBoardForm()
}

// ---------- 工具：props 别名 + 按 code 回查分组 ----------
const expandedMenusProp = props.expandedMenus
function menuGroupOf(code: string): MenuGroup | undefined {
  return props.menuGroups.find((g) => g.id === code)
}
// 点击看板行：选中该看板 + 展开/收起其模块（合并原展开箭头功能）
// 注意：pick-group 在父组件中会同步自动展开未展开的分组，因此必须在 emit 前缓存展开状态
function onGroupHeadClick(gid: NavGroupId) {
  const wasExpanded = expandedMenusProp.has(gid)
  emit('pick-group', gid)
  if (wasExpanded) emit('toggle-menu', gid)
}
</script>

<template>
  <!-- 左侧：主导航菜单栏（三个一级分组 + 二级模块） -->
  <aside class="sidebar" :class="{ collapsed }" aria-label="主导航">
    <!-- 侧栏顶部：品牌标识 + 收起 / 打开按钮 -->
    <div class="sb-rail">
      <span class="sb-brand" title="看板导航">
        <img :src="logoIcon" class="sb-brand-logo" alt="看板" />
      </span>
      <button
        type="button"
        class="sb-collapse"
        :title="collapsed ? '打开边栏' : '收起边栏'"
        :aria-label="collapsed ? '打开边栏' : '收起边栏'"
        :aria-expanded="!collapsed"
        @click="emit('toggle-collapse')"
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
          <path
            fill-rule="evenodd"
            clip-rule="evenodd"
            d="M9.67272 0.522841C10.8339 0.522841 11.76 0.522714 12.4963 0.602493C13.2453 0.683657 13.8789 0.854248 14.4264 1.25197C14.7504 1.48739 15.0355 1.77247 15.2709 2.0965C15.6686 2.64394 15.8392 3.27758 15.9204 4.02655C16.0002 4.7629 16 5.68895 16 6.85014V9.14986C16 10.3111 16.0002 11.2371 15.9204 11.9735C15.8392 12.7224 15.6686 13.3561 15.2709 13.9035C15.0355 14.2275 14.7504 14.5126 14.4264 14.748C13.8789 15.1458 13.2453 15.3163 12.4963 15.3975C11.76 15.4773 10.8339 15.4772 9.67272 15.4772H6.3273C5.16611 15.4772 4.24006 15.4773 3.50371 15.3975C2.75474 15.3163 2.1211 15.1458 1.57366 14.748C1.24963 14.5126 0.964549 14.2275 0.729131 13.9035C0.331407 13.3561 0.160817 12.7224 0.0796529 11.9735C-0.000126137 11.2371 1.25338e-09 10.3111 1.25338e-09 9.14986V6.85014C1.25329e-09 5.68895 -0.000126137 4.7629 0.0796529 4.02655C0.160817 3.27758 0.331407 2.64394 0.729131 2.0965C0.964549 1.77247 1.24963 1.48739 1.57366 1.25197C2.1211 0.854248 2.75474 0.683657 3.50371 0.602493C4.24006 0.522714 5.16611 0.522841 6.3273 0.522841H9.67272ZM5.54303 1.88715V14.1118C5.78636 14.1128 6.04709 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C6.04709 1.88307 5.78636 1.8862 5.54303 1.88715ZM4.1828 1.91166C3.99125 1.9216 3.8148 1.93577 3.65076 1.95353C3.01764 2.02212 2.65034 2.1506 2.37325 2.35179C2.16463 2.50337 1.98052 2.68747 1.82895 2.89609C1.62776 3.17318 1.49928 3.54048 1.43069 4.1736C1.36074 4.81967 1.36023 5.65896 1.36023 6.85014V9.14986C1.36023 10.341 1.36074 11.1803 1.43069 11.8264C1.49928 12.4595 1.62776 12.8268 1.82895 13.1039C1.98052 13.3125 2.16463 13.4966 2.37325 13.6482C2.65034 13.8494 3.01764 13.9779 3.65076 14.0465C3.81478 14.0642 3.99127 14.0774 4.1828 14.0873V1.91166Z"
            fill="currentColor"
          />
        </svg>
      </button>
    </div>

    <nav class="sb-nav" aria-label="看板分组">
      <!-- 全部看板（无二级菜单） -->
      <div class="sb-all-wrap">
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
        <button
          type="button"
          class="sb-dots"
          :class="{ on: boardFormVisible }"
          title="新建看板"
          aria-label="新建看板"
          @click.stop="openBoardForm($event)"
        >⋯</button>
      </div>

      <!-- 看板分组（全部来自 /boards，动态渲染） -->
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
            :aria-expanded="expandedMenus.has(g.id)"
            :aria-label="`${expandedMenus.has(g.id) ? '收起' : '展开'}${g.label} 的模块`"
            @click="onGroupHeadClick(g.id)"
          >
            <span class="sb-icon" :style="{ background: g.accent }">
              <svg v-if="g.id === 'quanfa'" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h10"/></svg>
              <svg v-else-if="g.id === 'happy'" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 11s3.5-6 9-6 9 6 9 6v5a2 2 0 0 1-2 2h-2v-6h-2v6h-2v-6h-2v6h-2v-6H7v-6H5v6H3v-5z"/></svg>
              <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 2l2.4 5.6L20 8.5l-4.2 3.9L16.8 18 12 15.5 7.2 18l1-5.6L4 8.5l5.6-.9L12 2z"/></svg>
            </span>
            <template v-if="isEditingBoard(g.id)">
              <input
                ref="editInputRef"
                class="sb-inline-input"
                v-model="editingValue"
                :placeholder="g.label"
                maxlength="64"
                @click.stop
                @keydown.enter.prevent="confirmEdit"
                @keydown.esc="cancelEditing"
                @blur="confirmEdit"
              />
            </template>
            <template v-else>
              <span class="sb-label" :title="g.label">{{ g.label }}</span>
              <span class="sb-count num">{{ g.count }}</span>
            </template>
          </button>
          <button
            type="button"
            class="sb-dots"
            :class="{ on: isMenuOpen('board', g.id) }"
            :title="`${g.label} 菜单`"
            :aria-label="`${g.label} 菜单`"
            @click.stop="openBoardMenu($event, g)"
          >⋯</button>
        </div>
        <!-- 二级菜单：工作模块（点击展开/收起） -->
        <div v-if="expandedMenus.has(g.id) && (g.modules.length || isAddingModule(g.id))" class="sb-sub-list">
          <div
            v-for="m in g.modules"
            :key="g.id + ':' + m.name"
            class="sb-sub-row"
          >
            <button
              type="button"
              class="sb-sub-item"
              :class="{ on: currentGroup === g.id && currentModule === m.name }"
              :style="{ '--accent': g.accent }"
              @click="emit('pick-module', g.id, m.name)"
            >
              <i class="sb-sub-bar"/>
              <template v-if="isEditingModule(g.id, m.name)">
                <input
                  ref="editInputRef"
                  class="sb-inline-input"
                  v-model="editingValue"
                  :placeholder="m.name"
                  maxlength="64"
                  @click.stop
                  @keydown.enter.prevent="confirmEdit"
                  @keydown.esc="cancelEditing"
                  @blur="confirmEdit"
                />
              </template>
              <template v-else>
                <span class="sb-sub-label" :title="m.name">{{ m.name }}</span>
                <span class="sb-sub-count num">{{ m.count }}</span>
              </template>
            </button>
            <button
              type="button"
              class="sb-dots"
              :class="{ on: isModuleMenuOpen(g.id, m.name) }"
              :title="`${m.name} 菜单`"
              :aria-label="`${m.name} 菜单`"
              @click.stop="openModuleMenu($event, g, m)"
            >⋯</button>
          </div>
          <!-- 新建工作模块：行内输入（组未展开时自动展开） -->
          <div v-if="isAddingModule(g.id)" class="sb-sub-row sb-add-row">
            <input
              ref="editInputRef"
              class="sb-inline-input"
              v-model="addingValue"
              placeholder="新模块名称"
              maxlength="64"
              @keydown.enter.prevent="confirmAddModule"
              @keydown.esc="cancelAdding"
              @blur="confirmAddModule"
            />
          </div>
        </div>
        <div v-if="expandedMenus.has(g.id) && !g.modules.length && !isAddingModule(g.id)" class="sb-empty">
          暂无工作模块
        </div>
      </div>
    </nav>

    <!-- 三点菜单浮层（fixed 定位，点击外部关闭） -->
    <div
      v-if="menu"
      ref="menuRef"
      class="sb-menu"
      :style="{ left: menuPos.x + 'px', top: menuPos.y + 'px' }"
      role="menu"
    >
      <!-- 看板：重命名 / 改配色 / 新建工作模块 / 删除 -->
      <template v-if="menuBoard">
        <button type="button" class="sb-menu-item" role="menuitem" @click="startEdit('board', menuBoard.code, menuBoard.label)">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>
          重命名
        </button>
        <button type="button" class="sb-menu-item" role="menuitem" @click="recolorOpen = !recolorOpen">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="13.5" cy="6.5" r=".5" fill="currentColor"/><circle cx="17.5" cy="10.5" r=".5" fill="currentColor"/><circle cx="8.5" cy="7.5" r=".5" fill="currentColor"/><circle cx="6.5" cy="12.5" r=".5" fill="currentColor"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z"/></svg>
          改配色
        </button>
        <div v-if="recolorOpen" class="sb-menu-colors" role="group" aria-label="配色">
          <button
            v-for="c in PRESET_COLORS"
            :key="c"
            type="button"
            class="sb-swatch"
            :class="{ on: c === menuBoard.accent }"
            :style="{ background: c }"
            :title="c"
            :aria-label="`设为 ${c}`"
            @click="onRecolor(c)"
          />
        </div>
        <button type="button" class="sb-menu-item" role="menuitem" @click="startAddModule(menuGroupOf(menuBoard.code))">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
          新建工作模块
        </button>
        <button type="button" class="sb-menu-item sb-menu-danger" role="menuitem" @click="onDeleteBoardFromMenu">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14z"/></svg>
          删除
        </button>
      </template>

      <!-- 模块：重命名 / 删除 -->
      <template v-else-if="menuModule">
        <button type="button" class="sb-menu-item" role="menuitem" @click="startEdit('module', menuModule.board, menuModule.name)">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>
          重命名
        </button>
        <button type="button" class="sb-menu-item sb-menu-danger" role="menuitem" @click="onDeleteModuleFromMenu">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14z"/></svg>
          删除
        </button>
      </template>
    </div>

    <!-- 新建看板浮层（弹出悬浮框：名称 / 前缀 / 6 色） -->
    <div
      v-if="boardFormVisible"
      ref="boardFormRef"
      class="sb-board-form"
      :style="{ left: boardFormPos.x + 'px', top: boardFormPos.y + 'px' }"
      role="dialog"
      aria-label="新建看板"
    >
      <div class="sbf-title">新建看板</div>
      <label class="sbf-field">
        <span>名称</span>
        <input v-model="boardForm.name" placeholder="如 临时专项" maxlength="64" @keydown.esc="closeBoardForm" />
      </label>
      <label class="sbf-field">
        <span>事项前缀</span>
        <input v-model="boardForm.prefix" placeholder="1-2 位大写字母，如 LS" maxlength="2" @keydown.esc="closeBoardForm" />
      </label>
      <div class="sbf-field">
        <span>配色</span>
        <div class="sbf-colors">
          <button
            v-for="c in PRESET_COLORS"
            :key="c"
            type="button"
            class="sb-swatch"
            :class="{ on: boardForm.accent === c }"
            :style="{ background: c }"
            :title="c"
            :aria-label="`选择 ${c}`"
            @click="boardForm.accent = c"
          />
        </div>
      </div>
      <div class="sbf-actions">
        <button type="button" class="sbf-cancel" @click="closeBoardForm">取消</button>
        <button type="button" class="sbf-submit" @click="submitBoardForm">创建看板</button>
      </div>
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
/* 收起（桌面端）：侧栏完全消失，按钮变为悬浮于页面左上角（DeepSeek 式） */
.sidebar.collapsed {
  flex-basis: 0;
  width: 0;
  border-right: none;
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

/* 侧栏顶部：品牌标识 + 收起/打开按钮条 */
.sb-rail {
  flex: 0 0 auto;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px;
  border-bottom: 1px solid var(--c-sidebar-border);
  transition: padding 0.22s ease;
}
.sidebar.collapsed .sb-rail {
  justify-content: center;
  padding: 0;
  border-bottom: none;
}
/* 品牌标识：与页头同款竖条，缩小为导航区的「小标题」 */
.sb-brand {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding-left: 4px;
  color: var(--c-sidebar-muted);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  white-space: nowrap;
}
.sb-brand-logo {
  width: 60px;
  height: 60px;
  object-fit: contain;
  flex: 0 0 auto;
  border-radius: 5px;
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
.sb-collapse svg {
  display: block;
}

/* 桌面端：收起后按钮悬浮于页面左上角（header 下方约 9px），卡片式浮层 */
@media (min-width: 861px) {
  .sidebar.collapsed .sb-collapse {
    position: fixed;
    top: 66px;
    left: 12px;
    width: 38px;
    height: 38px;
    border-radius: 11px;
    background: var(--c-card);
    border: 1px solid var(--c-line-strong);
    box-shadow: var(--shadow-card-hover);
    z-index: 60;
  }
  .sidebar.collapsed .sb-collapse:hover {
    background: var(--c-sidebar-hover);
    color: var(--c-sidebar-ink);
  }
}

/* 收起时导航淡出（配合侧栏宽度收缩，而非瞬间消失） */
.sb-nav {
  opacity: 1;
  visibility: visible;
  transition: opacity 0.16s ease;
}
.sidebar.collapsed .sb-nav {
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
  position: relative;
}
/* 全部看板行 = 主按钮 + ⋯ */
.sb-all-wrap {
  position: relative;
  display: flex;
  align-items: center;
  border-radius: 8px;
  /* 与下方看板分组隔开：留白 + 细分隔线（台账式） */
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--c-sidebar-border);
}
/* 「全部看板」选中态与分组统一：左侧色条（无身份色，用主题蓝） */
.sb-item.sb-all::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 0;
  border-radius: 2px;
  background: var(--c-sidebar-active-bar);
  transition: height 0.15s;
}
.sb-item.sb-all.on::before { height: 20px; }
.sb-item.sb-all:hover { background: var(--c-sidebar-hover); }
.sb-item.sb-all.on {
  background: var(--c-sidebar-active-bg);
  color: var(--c-sidebar-on-ink);
}

/* 一级：分组（含展开箭头 + ⋯） */
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

/* 三点按钮（横向 ⋯）：仅当悬浮所在行 / 该项选中 / 该菜单打开时浮现 */
.sb-dots {
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  margin-right: 4px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--c-sidebar-muted);
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s, background 0.15s, color 0.15s;
}
/* 仅所在行悬浮 / 所在选项选中 / 菜单打开时可见，其余时刻隐藏 */
.sb-all-wrap:hover .sb-dots,
.sb-group-head:hover .sb-dots,
.sb-sub-row:hover .sb-dots,
.sb-all-wrap:has(.sb-all.on) .sb-dots,
.sb-group-head.on .sb-dots,
.sb-sub-row:has(.sb-sub-item.on) .sb-dots,
.sb-dots.on {
  opacity: 1;
  pointer-events: auto;
}
.sb-dots:hover {
  background: var(--c-sidebar-hover);
  color: var(--c-sidebar-ink);
}

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

/* 行内输入（重命名 / 新建模块） */
.sb-inline-input {
  flex: 1 1 auto;
  min-width: 0;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--c-blue);
  border-radius: 6px;
  background: var(--c-card);
  color: var(--c-ink);
  font: inherit;
  font-size: 13px;
  outline: none;
  box-shadow: 0 0 0 2px var(--c-blue-soft);
}

/* 二级：模块子菜单 */
.sb-sub-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 4px 0 6px 16px;
}
.sb-sub-row {
  display: flex;
  align-items: center;
  border-radius: 7px;
}
.sb-sub-item {
  flex: 1 1 auto;
  min-width: 0;
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 4px 7px 18px;
  background: transparent;
  border: none;
  border-radius: 7px;
  color: var(--c-sidebar-muted);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
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
.sb-add-row {
  padding: 6px 0 2px;
}

.sb-empty {
  padding: 6px 16px 10px 30px;
  font-size: 12px;
  color: var(--c-sidebar-muted);
  font-style: italic;
}

/* ---------- 三点菜单浮层（fixed 定位，卡片风） ---------- */
.sb-menu {
  position: fixed;
  z-index: 3000;
  min-width: 160px;
  padding: 5px;
  border-radius: 10px;
  background: var(--c-card);
  border: 1px solid var(--c-line-strong);
  box-shadow: var(--shadow-card-hover);
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.sb-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--c-ink-soft);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
}
.sb-menu-item svg {
  flex: 0 0 auto;
  opacity: 0.8;
}
.sb-menu-item:hover {
  background: var(--c-blue-soft);
  color: var(--c-blue);
}
.sb-menu-item.sb-menu-danger { color: var(--c-st-urgent); }
.sb-menu-item.sb-menu-danger:hover {
  background: var(--c-st-urgent-soft);
  color: var(--c-st-urgent);
}
.sb-menu-colors {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
}
.sb-swatch {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  padding: 0;
  flex: 0 0 auto;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.12);
  transition: transform 0.12s ease;
}
.sb-swatch:hover { transform: scale(1.12); }
.sb-swatch.on {
  border-color: var(--c-ink);
  box-shadow: 0 0 0 2px var(--c-card), inset 0 0 0 1px rgba(0, 0, 0, 0.12);
}

/* ---------- 新建看板浮层（弹出悬浮框） ---------- */
.sb-board-form {
  position: fixed;
  z-index: 3000;
  width: 228px;
  padding: 14px;
  border-radius: 12px;
  background: var(--c-card);
  border: 1px solid var(--c-line-strong);
  box-shadow: var(--shadow-card-hover);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.sbf-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--c-ink);
}
.sbf-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--c-muted);
}
.sbf-field input {
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--c-line-strong);
  border-radius: 7px;
  background: var(--c-bg);
  color: var(--c-ink);
  font: inherit;
  font-size: 13px;
  outline: none;
}
.sbf-field input:focus {
  border-color: var(--c-blue);
  box-shadow: 0 0 0 2px var(--c-blue-soft);
}
.sbf-colors {
  display: flex;
  align-items: center;
  gap: 7px;
}
.sbf-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 2px;
}
.sbf-cancel,
.sbf-submit {
  padding: 6px 12px;
  border-radius: 7px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.sbf-cancel {
  border: 1px solid var(--c-line-strong);
  background: transparent;
  color: var(--c-muted);
}
.sbf-cancel:hover { color: var(--c-ink); background: var(--c-bg); }
.sbf-submit {
  border: none;
  background: var(--c-blue);
  color: #fff;
  font-weight: 600;
}
.sbf-submit:hover { filter: brightness(1.06); }

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
}
</style>
