<script setup lang="ts">
/**
 * 主题切换按钮（浅色 / 深色）
 * 单击在两主题间切换，状态由 useTheme 持久化。
 * 放置于页头右侧；图标用太阳 / 月亮表达当前可切换到的目标主题。
 */
import { computed } from 'vue'
import { ElTooltip } from 'element-plus'
import { useTheme } from '../composables/useTheme'

const { theme, toggleTheme } = useTheme()

const isDark = computed(() => theme.value === 'dark')
const tip = computed(() => (isDark.value ? '切换到浅色主题' : '切换到深色主题'))
</script>

<template>
  <ElTooltip :content="tip" placement="bottom" :show-after="300">
    <button
      type="button"
      class="theme-toggle"
      :class="{ 'is-dark': isDark }"
      :aria-pressed="isDark"
      :aria-label="tip"
      @click="toggleTheme"
    >
      <span class="tk-track">
        <span class="tk-thumb">
          <!-- 当前为深色 → 显示太阳（点击回浅色）；当前为浅色 → 显示月亮 -->
          <svg
            v-if="isDark"
            class="ico"
            viewBox="0 0 24 24"
            width="14"
            height="14"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
          </svg>
          <svg
            v-else
            class="ico"
            viewBox="0 0 24 24"
            width="14"
            height="14"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
          </svg>
        </span>
      </span>
    </button>
  </ElTooltip>
</template>

<style scoped>
.theme-toggle {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  border-radius: 999px;
}

.tk-track {
  width: 46px;
  height: 24px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.28);
  position: relative;
  transition: background 0.28s ease, border-color 0.28s ease;
}
.theme-toggle.is-dark .tk-track {
  background: rgba(169, 196, 255, 0.22);
  border-color: rgba(169, 196, 255, 0.4);
}

.tk-thumb {
  position: absolute;
  top: 50%;
  left: 2px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  background: linear-gradient(180deg, #ffffff 0%, #dfe9ff 100%);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
  transform: translateY(-50%);
  transition: left 0.28s cubic-bezier(0.4, 0.1, 0.2, 1), background 0.28s ease,
    color 0.28s ease;
}
.theme-toggle.is-dark .tk-thumb {
  left: 24px;
  color: #1e3d80;
  background: linear-gradient(180deg, #a9c4ff 0%, #5b8ae6 100%);
}
.tk-thumb .ico {
  display: block;
}

.theme-toggle:focus-visible {
  outline: 2px solid #ffffff;
  outline-offset: 2px;
}

@media (max-width: 640px) {
  .tk-track {
    width: 42px;
  }
  .theme-toggle.is-dark .tk-thumb {
    left: 20px;
  }
}
</style>
