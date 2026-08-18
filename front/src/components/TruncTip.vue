<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 纯文本内容（负责人 / 协作等不使用关键词高亮的字段） */
    text?: string
    /** hl() 输出的转义/高亮 HTML（标题 / 模块 / 描述等使用 v-html 的字段） */
    html?: string
    /** 截断行数：1 = 单行省略号，2 = 两行省略 */
    lines?: 1 | 2
    /** 根元素标签（标题传 h3、模块传 p，保留语义） */
    tag?: string
  }>(),
  { lines: 2, tag: 'span' },
)

const root = ref<HTMLElement | null>(null)
const inner = ref<HTMLElement | null>(null)
const overflowed = ref(false)

// 气泡展示的完整原文（纯文本，剥掉 <mark> 等标签）
const plain = computed(() => {
  if (props.text !== undefined) return props.text
  if (!props.html) return ''
  const d = document.createElement('div')
  d.innerHTML = props.html
  return d.textContent ?? ''
})

function isOverflowed(): boolean {
  const el = inner.value
  if (!el || !el.textContent) return false
  if (props.lines === 1) {
    return el.scrollWidth - el.clientWidth > 1
  }
  // 两行省略：line-clamp 下 scrollHeight 不可靠，改用 Range 测量完整文本高度
  // （overflow: hidden 只裁剪不改变布局盒，Range 高度仍覆盖全文）
  const range = document.createRange()
  range.selectNodeContents(el)
  return range.getBoundingClientRect().height - el.getBoundingClientRect().height > 1
}

function check() {
  overflowed.value = isOverflowed()
}

let ro: ResizeObserver | null = null
onMounted(() => {
  check()
  // 覆盖窗口缩放 / 网格列数随断点变化 / 侧栏收起等宽度变化
  if (root.value) {
    ro = new ResizeObserver(check)
    ro.observe(root.value)
  }
})
// 关键词高亮 / 内容变化（v-html 更新）后重测
watch(() => [props.text, props.html], check)
onBeforeUnmount(() => ro?.disconnect())
</script>

<template>
  <component :is="tag" ref="root" class="tt">
    <span
      ref="inner"
      class="tt-inner"
      :class="`tt-l${lines}`"
      v-html="html ?? ''"
      v-if="html !== undefined"
    />
    <span ref="inner" class="tt-inner" :class="`tt-l${lines}`" v-else>{{ text }}</span>
    <span v-if="overflowed && plain" class="tt-tip" role="tooltip">{{ plain }}</span>
  </component>
</template>

<style scoped>
/* 根：气泡定位包含块，自身不裁剪（裁剪只发生在内层 .tt-inner） */
.tt {
  position: relative;
  display: block;
  min-width: 0;
}
.tt-inner {
  display: block;
  min-width: 0;
}
/* 截断样式只作用内层：根元素不能有 overflow: hidden（气泡 absolute 定位会被裁掉） */
.tt-inner.tt-l1 {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tt-inner.tt-l2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
/* 悬停气泡：仅在溢出时渲染（v-if），hover 才可见（视觉沿用原 title-tip） */
.tt-tip {
  display: none;
  position: absolute;
  left: 0;
  top: calc(100% + 6px);
  z-index: 12;
  max-width: 340px;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--c-card);
  border: 1px solid var(--c-line-strong);
  box-shadow: var(--shadow-card-hover);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.5;
  white-space: normal;
  word-break: break-word;
  color: var(--c-ink);
  pointer-events: none;
}
.tt:hover .tt-tip {
  display: block;
}
</style>
