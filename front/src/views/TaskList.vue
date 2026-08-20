<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteTask, pinTask } from '../api/task'
import type { TaskDetail, TaskListItem } from '../api/task'
import type { SortField, SortOrder } from '../utils/taskShared'
import { errMsg } from '../utils/taskShared'
import { logOp } from '../composables/useOpLog'
import SidebarNav from '../components/SidebarNav.vue'
import FilterPanel from '../components/FilterPanel.vue'
import EditorDialog from '../components/EditorDialog.vue'
import ImportDialog from '../components/ImportDialog.vue'
import FollowPanel from '../components/FollowPanel.vue'
import TaskCard from '../components/TaskCard.vue'
import { useBoardData } from '../composables/useBoardData'
import { useMenuStats } from '../composables/useMenuStats'
import { useFilters } from '../composables/useFilters'
import { useTaskData } from '../composables/useTaskData'
import { useTaskSorting, loadSortState, DEFAULT_SORT } from '../composables/useTaskSorting'
import { useTaskPagination } from '../composables/useTaskPagination'
import { useTaskSelection } from '../composables/useTaskSelection'
import { useDragSort } from '../composables/useDragSort'
import { useCardPanel } from '../composables/useCardPanel'
import { useTaskExport } from '../composables/useTaskExport'
import { useBoardManage } from '../composables/useBoardManage'

// ---------- 左侧菜单栏：收起 / 展开 ----------
const sidebarCollapsed = ref(false)
function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

// ---------- 看板数据（动态来自 /boards） ----------
const { boardMap, fetchBoards, allBoardCodes, filterBoardOptions } = useBoardData()
// 默认看板筛选 = 全部看板（仅在用户尚未手动调整过时生效）
async function refreshBoards() {
  const codes = await fetchBoards()
  if (!filters.boards.length) filters.boards = [...codes]
  if (!draftFilters.boards.length) draftFilters.boards = [...codes]
}

// ---------- 左侧菜单数据：看板分组（来自后端聚合接口，动态） ----------
const {
  navState, menuGroups, navAllCount, expandedMenus,
  toggleMenuExpand, pickNavGroup, pickNavModule, pickAll, fetchMenuStats, applyModuleOrder,
} = useMenuStats()

// ---------- 筛选条件 + 高级筛选悬浮面板 ----------
const {
  filters, draftFilters, keywordInput, ownerOptions,
  filterPanelVisible, filterPanelStyle, filterBtnRef, filterPanelRef, boardPageRef,
  applyKeyword, queryOwners, toggleFilterPanel, closeFilterPanel,
  applyDraftFilters, resetDraftFilters, activeFilterCount, appliedFilterCount,
} = useFilters({ allBoardCodes })

// ---------- 排序状态（数据拉取与前端二次排序共用；初始化读取上次记忆，首次访问用系统默认） ----------
const sortState = reactive<{ field: SortField; order: SortOrder }>(loadSortState())

// ---------- 列表数据：分页拉取 + 排序（全部走后端）+ 统计聚合 ----------
// onLoaded 延迟接线：fetchFiltered/loadMore 完成后触发首屏补载（useTaskPagination 在本文件下方创建）
let paginationApi: { fillViewport: () => void } | null = null
const {
  loading, filteredTasks, loadedAll, stats, resetTick,
  fetchFiltered, loadMore, fetchAllMatching,
} = useTaskData({
  ctx: { navState, filters, sortState, allBoardCodes, menuGroups },
  onLoaded: () => paginationApi?.fillViewport(),
})
const { SORT_OPTIONS, currentSortLabel, isManualSort, changeSort, tasks } = useTaskSorting({
  filteredTasks,
  sortState,
})

// ---------- 无限滚动（哨兵触发 loadMore 拉下一页；数据在 useTaskData 累积） ----------
const { gridSentinel, setupSentinel, teardownSentinel, fillViewport } = useTaskPagination({
  loading,
  loadedAll,
  loadMore,
})
paginationApi = { fillViewport }

// 当前筛选结果总数（统计条带 total，供全选/导出提示）
const totalMatching = computed(() => stats.value.total)

// ---------- 勾选（跨分页全选当前筛选结果：全选时全量拉取 id） ----------
const { selectedIds, selectedCount, someSelected, allSelected, toggleRow, toggleAllRows } = useTaskSelection({
  tasks,
  total: totalMatching,
  fetchAllMatching,
})

// ---------- 手动排序：拖拽（乐观更新：拖完本地即生效，后台持久化，失败才重拉回滚） ----------
const { cardsGridEl, handleDragEnd } = useDragSort({
  tasks,
  isManualSort,
  fetchFiltered,
  applyOrder: (list) => {
    filteredTasks.value = list
  },
})

// ---------- 卡片跟进面板 ----------
const { expandedId, togglePanel, onLogCountSync } = useCardPanel({ filteredTasks })

// 切换工作模块 / 看板分组时关闭卡片跟进面板，避免悬浮框残留到新模块
watch(
  () => [navState.group, navState.module],
  () => {
    expandedId.value = null
  },
)

// ---------- 导出（列表已分页，导出时全量拉取当前筛选；统计条带数字即当前筛选结果数） ----------
const { exportRowsHint, exporting, exportExcel, exportCSV, exportBackupJSON } =
  useTaskExport({ selectedIds, boardMap, navAllCount, totalMatching, fetchAllMatching })

// ---------- 数据/看板变更后，看板映射、菜单统计与筛选结果统一刷新 ----------
// 任务数据（列表 + 统计条带）刷新：编辑保存后按需调用
function refreshTasks() {
  fetchFiltered()
}
// 元数据（看板映射 / 侧栏菜单统计）刷新：看板/模块 CRUD 后按需调用
function refreshMeta() {
  refreshBoards()
  fetchMenuStats()
}
// 两者都变（新建/删除/导入/看板或模块 CRUD）
function reload() {
  refreshMeta()
  refreshTasks()
}

// ---------- 编辑保存：按变更范围决定局部更新 / 重拉列表 / 全量刷新 ----------
function onSaved({ task, isCreate }: { task: TaskDetail; isCreate: boolean }) {
  if (isCreate) {
    reload() // 新建：计数/顺序都可能变
    return
  }
  const arr = filteredTasks.value
  const old = arr.find((x) => x.id === task.id)
  if (!old) {
    reload() // 当前视图无此卡（异常兜底）
    return
  }
  if (old.board !== task.board) {
    reload() // 跨看板移动：菜单统计/看板计数也变
    return
  }
  if (needRefetch(old, task)) {
    refreshTasks() // 影响排序/筛选 → 只重拉列表+统计
    return
  }
  const i = arr.findIndex((x) => x.id === task.id)
  arr[i] = task // 纯内容编辑 → 就地替换，零请求
}

// 排序/筛选相关字段是否变化（变化才需要后端重排/重筛）
function needRefetch(old: TaskListItem, updated: TaskListItem): boolean {
  return old.status !== updated.status
    || old.priority !== updated.priority
    || old.owner !== updated.owner
    || old.module !== updated.module
    || old.deadline !== updated.deadline
    || (sortState.field === 'updateDate' && old.updatedAt !== updated.updatedAt)
    || (!!filters.keyword && old.title !== updated.title)
}

// ---------- 侧栏三点菜单操作：看板 / 工作模块管理（事件由 SidebarNav 发出） ----------
const {
  boardIdByCode, onCreateBoard, onRenameBoard, onRecolorBoard, onDeleteBoard,
  onCreateModule, onRenameModule, onDeleteModule, onReorderModules,
} = useBoardManage({
  boardMap,
  menuGroups,
  reload,
  applyModuleOrder,
  onBoardDeleted: (code) => {
    if (navState.group === code) {
      navState.group = 'all'
      navState.module = null
    }
  },
})

// ---------- 新建/编辑（弹窗逻辑在子组件 EditorDialog，父组件用 ref 调用） ----------
const editorRef = ref<InstanceType<typeof EditorDialog> | null>(null)
// ---------- 导入（弹窗逻辑在子组件 ImportDialog，父组件用 ref 调用） ----------
const importRef = ref<InstanceType<typeof ImportDialog> | null>(null)
const importing = ref(false)

// ---------- 置顶：点击卡片上的 ID 小标签（card-tab），成功后就地更新卡片（保留滚动位置，不整表重拉） ----------
async function togglePin(t: TaskListItem) {
  try {
    const { data: updated } = await pinTask(t.id, !t.pinned)
    logOp({
      action: 'UPDATE',
      targetType: 'task',
      targetId: t.id,
      targetCode: t.taskCode ?? undefined,
      detail: `${t.pinned ? '取消置顶' : '置顶'} ${t.taskCode ?? '#' + t.id}《${t.title}》`,
    })
    patchPinned(updated)
  } catch (err: any) {
    ElMessage.error('置顶操作失败：' + errMsg(err))
  }
}

// 置顶恒最前：移除旧卡片，用服务端返回的新卡片插入「已置顶区之后 / 未置顶区之前」
function patchPinned(updated: TaskListItem) {
  const arr = filteredTasks.value
  const i = arr.findIndex((x) => x.id === updated.id)
  if (i === -1) return
  arr.splice(i, 1)
  let ins = 0
  while (ins < arr.length && arr[ins]?.pinned) ins++
  arr.splice(ins, 0, updated)
}

// ---------- 工具栏「重置」：回到默认状态（全部看板、无任何筛选、默认排序、清关键词搜索、清勾选） ----------
function resetAll() {
  navState.group = 'all'
  navState.module = null
  filters.boards = [...allBoardCodes.value]
  filters.statuses = []
  filters.owners = []
  filters.keyword = ''
  filters.dateRange = []
  keywordInput.value = ''
  draftFilters.boards = [...allBoardCodes.value]
  draftFilters.statuses = []
  draftFilters.owners = []
  draftFilters.dateRange = []
  changeSort({ ...DEFAULT_SORT }) // 回到系统默认排序并同步记忆
  selectedIds.value.clear()
  closeFilterPanel()
}

// ---------- 批量删除 ----------
async function deleteSelected() {
  if (!selectedIds.value.size) return
  try {
    await ElMessageBox.confirm(
      `确认删除选中的 ${selectedIds.value.size} 项事项？删除后不可恢复。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  const ids = [...selectedIds.value]
  try {
    // 分批并发删除（每批 10 条）：避免逐条串行 RTT 过慢，也避免一次性全量并发打爆连接
    const BATCH = 10
    let deleted = 0
    for (let i = 0; i < ids.length; i += BATCH) {
      const chunk = ids.slice(i, i + BATCH)
      await Promise.all(chunk.map((id) => deleteTask(id)))
      deleted += chunk.length
    }
    ElMessage.success(`已删除 ${deleted} 项`)
    logOp({ action: 'DELETE', targetType: 'task', detail: `批量删除 ${ids.length} 项事项` })
    reload()
  } catch (err: any) {
    ElMessage.error('删除失败：' + errMsg(err))
    reload()
  }
}

// 筛选/排序变化（查询重置）→ 清空勾选（追加下一页不清空，避免滚动加载时误清勾选）
watch(resetTick, () => {
  selectedIds.value.clear()
})

onMounted(async () => {
  fetchMenuStats()       // 与 boards 并行，不阻塞
  await refreshBoards()  // 先回填 filters.boards，保证首次 fetchFiltered 就带正确看板
  fetchFiltered()        // 仅一次（watch 由 useTaskData 的 lastSignature 去重兜底）
  nextTick(setupSentinel)
})
onBeforeUnmount(() => {
  teardownSentinel()
})
</script>

<template>
  <div class="board-shell">
    <!-- 左侧：主导航菜单栏（动态看板分组 + 二级模块），逻辑在子组件 SidebarNav -->
    <SidebarNav
      :collapsed="sidebarCollapsed"
      :nav-all-count="navAllCount"
      :menu-groups="menuGroups"
      :expanded-menus="expandedMenus"
      :current-group="navState.group"
      :current-module="navState.module"
      :board-id-map="boardIdByCode"
      @toggle-collapse="toggleSidebar"
      @pick-all="pickAll"
      @pick-group="pickNavGroup"
      @pick-module="pickNavModule"
      @toggle-menu="toggleMenuExpand"
      @create-board="onCreateBoard"
      @rename-board="onRenameBoard"
      @recolor-board="onRecolorBoard"
      @delete-board="onDeleteBoard"
      @create-module="onCreateModule"
      @rename-module="onRenameModule"
      @delete-module="onDeleteModule"
      @reorder-modules="onReorderModules"
    />

    <!-- 右侧：看板主区域（统计 + 筛选 + 表格） -->
    <section class="board-page" :class="{ 'sb-collapsed': sidebarCollapsed }" ref="boardPageRef">
      <!-- 统计条带 -->
      <section class="stat-strip card">
        <div class="stat">
          <span class="stat-num num st-ink">{{ stats.total }}</span>
          <span class="stat-label">总事项</span>
        </div>
        <div class="stat">
          <span class="stat-num num st-urgent">{{ stats.urgent }}</span>
          <span class="stat-label">亟待解决</span>
        </div>
        <div class="stat">
          <span class="stat-num num st-ongoing">{{ stats.ongoing }}</span>
          <span class="stat-label">进行中</span>
        </div>
        <div class="stat">
          <span class="stat-num num st-high">{{ stats.high }}</span>
          <span class="stat-label">高优先级</span>
        </div>
        <div class="stat">
          <span class="stat-num num st-follow">{{ stats.near }}</span>
          <span class="stat-label">7 日内到期</span>
        </div>
      </section>

      <!-- 单行工具栏：搜索 + 排序（左） + 高级筛选（按钮在筛选左侧排序右侧→面板弹在筛选按钮右侧） —— 弹性 —— 导入/导出/新建/全选/删除 -->
      <section class="toolbar card">
        <div class="toolbar-left">
          <div class="search-box">
            <el-input
              v-model="keywordInput"
              placeholder="搜索事项ID/名称/工作模块..."
              clearable
              style="width: 260px"
              @keyup.enter="applyKeyword"
              @clear="applyKeyword"
            />
            <el-button type="primary" @click="applyKeyword">搜索</el-button>
          </div>
          <el-dropdown trigger="click" @command="(cmd: number) => { const o = SORT_OPTIONS[cmd]; if (o) changeSort(o); }">
            <el-button plain class="btn-sort">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h13M3 12h9M3 18h5M17 8l4 4-4 4"/></svg>
              {{ currentSortLabel }}
              <i class="caret">▾</i>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="(o, i) in SORT_OPTIONS"
                  :key="i"
                  :command="i"
                  :disabled="o.field === sortState.field && o.order === sortState.order"
                >
                  <span class="sort-check">
                    {{ o.field === sortState.field && o.order === sortState.order ? '✓' : '' }}
                  </span>
                  {{ o.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span ref="filterBtnRef" class="filter-btn-wrap">
            <el-badge
              :value="appliedFilterCount"
              :hidden="appliedFilterCount === 0"
              :max="9"
              class="filter-badge"
            >
              <el-button
                plain
                class="btn-filter"
                :class="{ active: appliedFilterCount > 0, on: filterPanelVisible }"
                @click.stop="toggleFilterPanel"
              >
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/></svg>
                高级筛选
              </el-button>
            </el-badge>
          </span>
          <el-button plain class="btn-reset" @click="resetAll">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>
            重置
          </el-button>
        </div>
        <div class="spacer" />
        <div class="toolbar-actions">
          <el-button plain :loading="importing" @click="importRef?.open()">导入</el-button>
          <el-dropdown trigger="click" @command="(cmd: string) => { if (cmd === 'xlsx') exportExcel(); else if (cmd === 'csv') exportCSV(); else if (cmd === 'json') exportBackupJSON(); }">
            <el-button plain :loading="exporting" class="btn-export">
              导出
              <i class="caret">▾</i>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="xlsx">
                  <span style="display:inline-flex;align-items:center;gap:6px;">📊 导出 Excel (.xlsx)</span>
                </el-dropdown-item>
                <el-dropdown-item command="csv">
                  <span style="display:inline-flex;align-items:center;gap:6px;">📄 导出 CSV</span>
                </el-dropdown-item>
                <el-dropdown-item command="json">
                  <span style="display:inline-flex;align-items:center;gap:6px;">💾 备份 JSON（全库）</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span class="export-hint">{{ exportRowsHint }}</span>
          <el-button type="primary" @click="editorRef?.open()">＋ 新建事项</el-button>
          <el-checkbox
            class="toolbar-select-all"
            :model-value="allSelected"
            :indeterminate="someSelected"
            :disabled="!tasks.length"
            @change="toggleAllRows"
          >
            全选
          </el-checkbox>
          <el-button type="danger" plain :disabled="!selectedCount" @click="deleteSelected">
            删除{{ selectedCount ? `（${selectedCount}）` : '' }}
          </el-button>
        </div>
      </section>

      <!-- 高级筛选悬浮面板（受控组件：草稿/定位/外点关闭在组合根） -->
      <FilterPanel
        v-if="filterPanelVisible"
        ref="filterPanelRef"
        :draft-filters="draftFilters"
        :boards="filterBoardOptions"
        :owner-options="ownerOptions"
        :panel-style="filterPanelStyle"
        :active-count="activeFilterCount"
        :remote-query="queryOwners"
        @apply="applyDraftFilters"
        @reset="resetDraftFilters"
        @close="closeFilterPanel"
      />

      <!-- 卡片视图：文件夹卡片 + 卡片跟进面板（仅此区域滚动加载） -->
      <section class="cards-region" v-loading="loading">
        <div class="cards-scroll">
          <div ref="cardsGridEl" class="cards-grid">
            <TaskCard
              v-for="t in tasks"
              :key="t.id"
              :task="t"
              :board-map="boardMap"
              :keyword="filters.keyword"
              :is-manual-sort="isManualSort"
              :checked="selectedIds.has(t.id)"
              :log-open="expandedId === t.id"
              @toggle-pin="togglePin(t)"
              @toggle-check="(v: boolean) => toggleRow(t.id, v)"
              @open-follow="togglePanel(t)"
              @edit="editorRef?.open(t)"
            />
          </div>

          <!-- 卡片跟进面板：悬浮于所点卡片右侧（放不下时弹左侧）；详情/定位/增删记录在子组件 -->
          <!-- :key 保证切换展开卡片时重建组件（onMounted 重新拉详情并定位） -->
          <FollowPanel
            v-if="expandedId !== null"
            :key="expandedId"
            :task-id="expandedId"
            @close="expandedId = null"
            @sync-count="onLogCountSync"
          />

          <div ref="gridSentinel" class="grid-sentinel" aria-hidden="true" />

          <div v-if="!loading && !tasks.length" class="empty-overlay">
            <el-empty description="没有匹配的事项，试试调整筛选条件" :image-size="80" />
          </div>
        </div>

        <div class="load-more">
          <template v-if="!tasks.length">—</template>
          <template v-else-if="loading">加载中…</template>
          <template v-else-if="loadedAll">已加载全部 {{ tasks.length }} 项</template>
          <template v-else>滚动加载更多…</template>
        </div>
      </section>

      <!-- 新建/编辑 Dialog（台账登记卡，逻辑在子组件 EditorDialog） -->
      <EditorDialog ref="editorRef" @saved="onSaved" />
      <!-- 导入预览 Dialog（逻辑在子组件 ImportDialog） -->
      <ImportDialog ref="importRef" v-model:importing="importing" :total-count="navAllCount" @done="reload" />
    </section>
    <!-- end board-page (right column) -->
  </div>
  <!-- end board-shell -->
</template>

<style scoped>
/* ---------- 分栏外壳：左菜单栏 + 右看板内容 ---------- */
.board-shell {
  display: flex;
  flex-direction: row;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: var(--c-bg);
}

/* 右侧：看板主内容 */
.board-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  padding: 14px 24px 12px;
  /* 高级筛选悬浮面板的定位包含块（面板作为同级子元素，绝对定位不被 overflow 裁剪） */
  position: relative;
  /* 收起边栏时左侧留白平滑过渡（与侧栏 0.22s 收缩同步） */
  transition: padding-left 0.22s ease;
}
/* 桌面端边栏收起：悬浮「打开边栏」按钮浮在左上角，主区左侧留出按钮宽度避免遮挡统计数字 */
@media (min-width: 861px) {
  .board-page.sb-collapsed {
    padding-left: 64px;
  }
}
.card {
  flex: 0 0 auto;
  background: var(--c-card);
  border: 1px solid var(--c-line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}

/* ---------- 统计条带 ---------- */
.stat-strip {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  overflow: hidden;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 20px;
  border-left: 1px solid var(--c-line);
}
.stat:first-child {
  border-left: none;
}
.stat-num {
  font-size: 23px;
  font-weight: 700;
  line-height: 1.2;
}
.stat-label {
  font-size: 12px;
  color: var(--c-muted);
}
.st-ink { color: var(--c-blue); }
.st-urgent { color: var(--c-st-urgent); }
.st-ongoing { color: var(--c-st-ongoing); }
.st-high { color: var(--c-pri-high); }
.st-follow { color: var(--c-st-follow); }

/* ---------- 单行工具栏：搜索 + 排序 + 高级筛选 —— 弹性 —— 导入/导出/新建/全选/删除 ---------- */
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  flex-wrap: nowrap;
  overflow-x: auto;
  /* 面板现在是 board-page 的同级子元素，不再需要 toolbar 作为定位上下文 */
}
.toolbar-left {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.filter-btn-wrap {
  display: inline-flex;
  align-items: center;
}
.search-box {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.spacer {
  flex: 1;
  min-width: 12px;
}
.toolbar-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

/* 工具栏按钮：图标 + 文字 风格 */
.btn-filter,
.btn-sort,
.btn-reset {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px !important;
  font-weight: 500;
  color: var(--c-ink-soft);
  border-color: var(--c-line-strong) !important;
  transition: all 0.18s ease;
}
.btn-filter svg,
.btn-sort svg,
.btn-reset svg {
  opacity: 0.8;
  flex-shrink: 0;
}
.btn-sort .caret {
  font-style: normal;
  font-size: 11px;
  margin-left: 2px;
  opacity: 0.7;
  line-height: 1;
}
.btn-filter:hover,
.btn-sort:hover,
.btn-reset:hover {
  color: var(--c-blue);
  border-color: var(--c-blue) !important;
}
.btn-filter:hover svg,
.btn-sort:hover svg,
.btn-reset:hover svg {
  opacity: 1;
}

/* 筛选激活状态：徽章变色 + 按钮高亮 + 面板打开时按下感 */
.filter-badge :deep(.el-badge__content) {
  background: var(--c-st-urgent, #ef4444);
  border-color: var(--c-st-urgent, #ef4444);
  font-weight: 600;
}
.btn-filter.active {
  background: color-mix(in srgb, var(--c-blue) 8%, var(--c-card));
  border-color: var(--c-blue) !important;
  color: var(--c-blue);
}
.btn-filter.active svg {
  opacity: 1;
}
.btn-filter.on {
  /* 面板打开时按钮保持按下高亮，视觉上与面板相连 */
  background: color-mix(in srgb, var(--c-blue) 14%, var(--c-card));
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.06);
}

/* 排序下拉菜单项：左侧勾选标记占位对齐 */
.sort-check {
  display: inline-block;
  width: 16px;
  text-align: center;
  color: var(--c-blue);
  font-weight: 700;
  margin-right: 2px;
}

/* ---------- 导出下拉按钮 + 条数提示 ---------- */
.btn-export {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px !important;
  font-weight: 500;
  color: var(--c-ink-soft);
  border-color: var(--c-line-strong) !important;
  transition: all 0.18s ease;
}
.btn-export .caret {
  font-style: normal;
  font-size: 11px;
  margin-left: 2px;
  opacity: 0.7;
  line-height: 1;
}
.btn-export:hover {
  color: var(--c-blue);
  border-color: var(--c-blue) !important;
}
.export-hint {
  font-size: 12px;
  color: var(--c-muted);
  line-height: 1;
  padding: 0 4px;
  flex-shrink: 0;
}

/* ---------- 卡片视图区域（唯一滚动区） ---------- */
.cards-region {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--c-card);
  border: 1px solid var(--c-line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
  position: relative;
}
.cards-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px 18px 8px;
  /* 跟进面板的定位上下文 */
  position: relative;
}
.cards-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px 16px;
}
.grid-sentinel {
  height: 1px;
}

/* 工具栏：全选 */
.toolbar-select-all {
  margin-left: 4px;
}

/* 加载更多 / 空态 */
.load-more {
  flex: 0 0 auto;
  text-align: center;
  font-size: 12px;
  color: var(--c-muted);
  padding: 5px 0 7px;
  border-top: 1px solid var(--c-line);
  background: var(--c-card);
}
.empty-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--c-empty-veil);
  z-index: 5;
}

/* 中等宽度：4 列偏挤时降为 3 列 */
@media (max-width: 1280px) {
  .cards-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .cards-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px 12px;
  }
  .board-shell {
    flex-direction: column;
  }
  .board-page {
    padding: 12px 14px 10px;
    gap: 10px;
  }
  .stat-strip {
    grid-template-columns: repeat(2, 1fr);
  }
  .stat:nth-child(odd) {
    border-left: none;
  }
  .stat:nth-child(n+3) {
    border-top: 1px solid var(--c-line);
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    transition: none !important;
  }
}
</style>
