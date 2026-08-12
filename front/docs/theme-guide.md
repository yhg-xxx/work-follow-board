# 主题系统接入指南 · 工作跟进看板

> 双主题（浅色 / 深色）实现说明与接入指南。
> 相关文件：`src/assets/base.css`、`src/assets/main.css`、`src/composables/useTheme.ts`、`src/components/ThemeToggle.vue`、`index.html`。

---

## 一、实现概览

| 关注点 | 方案 |
|---|---|
| 主题载体 | `<html data-theme="light\|dark">` 驱动语义令牌；`.dark` 类驱动 Element Plus 官方深色变量 |
| 样式管理 | CSS Variables（`base.css` 的 `:root` 与 `[data-theme="dark"]`） |
| 状态管理 | `useTheme()` 组合式函数（单例 `ref`） |
| 持久化 | `localStorage`（key: `tmo-theme`） |
| 默认值 | 首次访问跟随系统 `prefers-color-scheme` |
| 无闪烁 | `index.html` 内联脚本在首帧前落 `data-theme`（FOUC 防护） |
| 切换动画 | 临时挂 `.theme-anim`，仅过渡颜色属性 ~280ms，尊重 `prefers-reduced-motion` |
| 组件库 | Element Plus 官方 `dark/css-vars.css` + 品牌色覆盖 |

---

## 二、快速使用

### 1. 切换主题（任意组件）

```vue
<script setup lang="ts">
import { useTheme } from '@/composables/useTheme'
const { theme, toggleTheme, setTheme } = useTheme()
</script>

<template>
  <button @click="toggleTheme">切换</button>
  <button @click="setTheme('dark')">深色</button>
  <span>当前：{{ theme }}</span>
</template>
```

> `useTheme()` 返回的是**单例**状态，任意组件调用都拿到同一份 `theme`，切换即全局生效。

### 2. 直接用现成的切换按钮

把 `ThemeToggle` 放进任意位置（已在页头右侧集成）：

```vue
<script setup lang="ts">
import ThemeToggle from '@/components/ThemeToggle.vue'
</script>
<template>
  <ThemeToggle />
</template>
```

---

## 三、给组件上色（核心规则）

**只引用语义令牌，不写裸色值**，组件就自动适配双主题：

```css
/* ✅ 正确：随主题切换 */
.card {
  background: var(--c-card);
  color: var(--c-ink);
  border: 1px solid var(--c-line);
}
.card:hover { box-shadow: var(--shadow-card-hover); }

/* ❌ 错误：深色下不会变 */
.bad { background: #ffffff; color: #16223e; }
```

可用令牌清单见 [`color-system.md` 第五节](./color-system.md)。Element Plus 组件**无需**单独设色——桥接令牌（`--el-*`）已随主题更新。

---

## 四、主题是如何生效的

1. **首帧前**：`index.html` 内联脚本读 `localStorage['tmo-theme']`（无则用系统偏好），给 `<html>` 设 `data-theme` 与 `.dark`。
2. **SPA 启动**：`main.ts` 引入 `useTheme`（模块级单例初始化），并再 `applyTheme()` 一次保证一致。
3. **切换时**：`setTheme/toggleTheme` → `withTransition` 临时加 `.theme-anim` → 改 `data-theme` + `.dark` + 写 `localStorage` → 280ms 后移除 `.theme-anim`。
4. **系统变化**：若用户未显式选过主题，监听 `prefers-color-scheme` 自动跟随。

`data-theme` 与 `.dark` 两套标记的关系：
- `data-theme` → 我们的语义令牌（`[data-theme="dark"]` 选择器）。
- `.dark` → Element Plus 官方深色变量（其内部选择器为 `.dark`）。
- 两者由 `applyTheme()` **同步**切换，务必成对出现。

---

## 五、新增 / 修改令牌

在 `src/assets/base.css` 同时维护两个主题块：

```css
:root {
  /* 浅色值 */
  --c-my-token: #EEF3FC;
}

[data-theme="dark"] {
  /* 深色值 */
  --c-my-token: #1E2B48;
}
```

随后在组件里 `var(--c-my-token)` 即可。颜色选型与对比度要求见 [`color-system.md`](./color-system.md)。

---

## 六、注意事项

- **不要在组件里写死 `--blue-500` 这类原始色阶**，请走语义令牌；原始色阶仅供色板一致性。
- **侧栏在双主题下都是最深一层**（刻意保留「深色框 + 内容区」的层级），侧栏内的 `rgba(255,255,255,*)` 叠层在两个主题下都成立，无需改动。
- `main.css` 中业务令牌必须在 Element Plus 样式**之后**引入（已在 `main.ts` 调整顺序），否则品牌蓝会被官方默认蓝覆盖。
- 如需禁用切换动画，浏览器开启「减少动态效果」即可（已通过 `prefers-reduced-motion` 适配）。

---

## 七、文件清单

| 文件 | 职责 |
|---|---|
| `src/assets/base.css` | 原始色阶 + 语义令牌（浅/深）+ Element Plus 桥接 |
| `src/assets/main.css` | 主题过渡、滚动条、Element Plus 全局深色覆盖 |
| `src/composables/useTheme.ts` | 单例主题状态、持久化、系统偏好、切换动画 |
| `src/components/ThemeToggle.vue` | 切换按钮组件（太阳/月亮） |
| `index.html` | FOUC 防护内联脚本 |
| `src/main.ts` | 样式引入顺序、启动时 `applyTheme` |
