// 无限滚动：底部哨兵进入视口即请求下一页（数据由 useTaskData 分页累积，这里只管触发）
import { nextTick, onBeforeUnmount, ref } from 'vue'
import type { Ref } from 'vue'

export function useTaskPagination(params: {
  loading: Ref<boolean>
  loadedAll: Ref<boolean>
  loadMore: () => Promise<void>
}) {
  const { loading, loadedAll, loadMore } = params

  // 卡片网格底部哨兵：进入视口即加载下一页（无限滚动）
  const gridSentinel = ref<HTMLElement | null>(null)
  let sentinelObserver: IntersectionObserver | null = null
  function setupSentinel() {
    const el = gridSentinel.value
    if (!el) return
    sentinelObserver?.disconnect()
    sentinelObserver = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) loadMore()
      },
      { rootMargin: '240px 0px' },
    )
    sentinelObserver.observe(el)
  }
  function teardownSentinel() {
    sentinelObserver?.disconnect()
    sentinelObserver = null
  }

  // 首屏不足一屏时自动补载下一页，直到撑满可视区（每次补载一页）
  let filling = false
  async function fillViewport() {
    if (filling || loading.value || loadedAll.value) return
    filling = true
    try {
      for (let i = 0; i < 5; i++) {
        if (loading.value || loadedAll.value) break
        await nextTick()
        const scrollEl = document.querySelector('.cards-scroll') as HTMLElement | null
        if (!scrollEl) break
        if (scrollEl.scrollHeight <= scrollEl.clientHeight + 1) {
          await loadMore()
        } else {
          break
        }
      }
    } finally {
      filling = false
    }
  }

  return { gridSentinel, setupSentinel, teardownSentinel, fillViewport }
}
