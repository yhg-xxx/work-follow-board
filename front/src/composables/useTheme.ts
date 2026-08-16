/**
 * 主题状态管理（单例）
 * ------------------------------------------------------------
 * · 浅 / 深双主题，状态持久化到 localStorage
 * · 首次访问跟随系统 prefers-color-scheme
 * · 通过 <html data-theme="dark|light"> 切换语义令牌
 * · 深色同步挂 .dark 类，驱动 Element Plus 官方深色变量
 *
 * 无闪烁说明：index.html 内联脚本会在首帧前完成首次 data-theme 落盘；
 * 本模块在导入时再次 apply，保证 SPA 接管后状态一致。
 */
import { ref } from 'vue'

export type ThemeName = 'light' | 'dark'

const STORAGE_KEY = 'tmo-theme'
const TRANSITION_MS = 420

function resolveInitial(): ThemeName {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'light' || saved === 'dark') return saved
  } catch {
    /* localStorage 不可用时降级到系统偏好 */
  }
  return prefersDark() ? 'dark' : 'light'
}

function prefersDark(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}

/** 把主题写到 <html>：语义令牌靠 data-theme，Element Plus 靠 .dark */
export function applyTheme(theme: ThemeName): void {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.setAttribute('data-theme', theme)
  root.classList.toggle('dark', theme === 'dark')
  root.style.colorScheme = theme
}

/**
 * 主题切换过渡。
 * 优先使用 View Transitions API：对整页做新旧快照交叉淡入，
 * 可平滑过渡 linear-gradient 等 CSS transition 无法插值的属性。
 * 不支持时降级为临时挂 .theme-anim 做属性级过渡。
 */
function withTransition(fn: () => void): void {
  if (typeof document === 'undefined') {
    fn()
    return
  }
  const reduce =
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  if (reduce) {
    fn()
    return
  }
  const root = document.documentElement
  const vt = (document as unknown as { startViewTransition?: (cb: () => void) => { finished: Promise<void> } }).startViewTransition
  if (typeof vt === 'function') {
    // 现代浏览器：整页交叉淡入，连渐变也丝滑
    try {
      const transition = vt.call(document, fn)
      transition.finished.catch(() => {
        /* 用户切换标签等导致中断时忽略 */
      })
      return
    } catch {
      /* 落降级 */
    }
  }
  // 降级：临时挂 .theme-anim 做颜色属性过渡
  root.classList.add('theme-anim')
  fn()
  window.setTimeout(() => root.classList.remove('theme-anim'), TRANSITION_MS)
}

// 模块级单例：所有调用 useTheme() 共享同一份状态
const theme = ref<ThemeName>(resolveInitial())
applyTheme(theme.value)

// 监听系统主题变化：仅当用户未显式选择时跟随
if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
  const mql = window.matchMedia('(prefers-color-scheme: dark)')
  mql.addEventListener?.('change', (e) => {
    // 存储不可用时同样降级跟随系统（与 resolveInitial 的兜底一致）
    try {
      if (!localStorage.getItem(STORAGE_KEY)) {
        theme.value = e.matches ? 'dark' : 'light'
      }
    } catch {
      theme.value = e.matches ? 'dark' : 'light'
    }
  })
}

/**
 * 在任意组件中使用：
 *   const { theme, toggleTheme, setTheme } = useTheme()
 */
export function useTheme() {
  function setTheme(next: ThemeName) {
    if (next === theme.value) return
    withTransition(() => {
      theme.value = next
      applyTheme(next)
      try {
        localStorage.setItem(STORAGE_KEY, next)
      } catch {
        /* 存储失败时忽略，当前会话仍可用 */
      }
    })
  }

  function toggleTheme() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  return { theme, setTheme, toggleTheme }
}
