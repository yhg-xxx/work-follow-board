// 卡片展示辅助：纯函数，显式传参（不依赖组件状态闭包），供 TaskCard.vue 与相关组件使用
import type { BoardMap } from './taskShared'
import { boardLabel } from './taskShared'

export const escHtml = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')

// 正则特殊字符转义（使输入按字面文本参与匹配）
const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

// 搜索关键词高亮（先转义防注入，再包 <mark>）；keyword 为空时仅转义
// 与后端一致：按空白拆词，逐词各自高亮（AND 语义下各词可命中不同字段）
export function hl(text: string | null | undefined, keyword: string): string {
  if (!text) return '—'
  const terms = keyword.trim().split(/\s+/).filter(Boolean)
  if (!terms.length) return escHtml(text)
  const re = new RegExp(`(${terms.map(escapeRe).join('|')})`, 'gi')
  return text
    .split(re)
    .map((p, i) => (i % 2 === 1 ? `<mark class="hl">${escHtml(p)}</mark>` : escHtml(p)))
    .join('')
}

export const statusDot = (s: string) => {
  switch (s) {
    case '已完成': return 'st-done'
    case '进行中': return 'st-ongoing'
    case '亟待解决': return 'st-urgent'
    case '持续跟进': return 'st-follow'
    default: return 'st-idle'
  }
}

export const priClass = (p: string) => (p === '高' ? 'pri-high' : p === '中' ? 'pri-mid' : 'pri-low')

// 卡片身份色：完全由看板 accent 决定（文件夹标签 / 底部脊线 / 标签背景）
export const cardAccent = (t: { board: string }, boardMap: BoardMap): string =>
  boardMap[t.board]?.accent ?? '#2B59C3'

export const cardTabTitle = (b: string, boardMap: BoardMap): string => boardLabel(b, boardMap)

export const riskIsStar = (r: string | null): boolean => !!r && r.includes('★')

export const fmtShort = (d: string | null): string => {
  if (!d) return '—'
  const parts = d.split('-')
  return parts.length === 3 ? `${parts[1]}-${parts[2]}` : d
}

export const fmtDate = (d: string | null): string => d || '—'

export function deadlineState(d: string | null): string {
  if (!d) return ''
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const dd = new Date(d + 'T00:00:00')
  const diff = Math.round((dd.getTime() - today.getTime()) / 86400000)
  if (diff < 0) return 'dl-overdue'
  if (diff <= 7) return 'dl-near'
  return ''
}
