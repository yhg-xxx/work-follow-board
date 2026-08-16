// Element Plus 弹层判定：下拉/日期/自动补全等 teleport 到 body 的弹层内交互
// 应视为面板/弹窗内部，外点关闭逻辑不能把它们当“外部”关闭。
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

/** 目标节点（或其祖先）是否落在 Element Plus 的 teleport 弹层内 */
export function isInsideElementPlusPopup(tgt: EventTarget | null): boolean {
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
