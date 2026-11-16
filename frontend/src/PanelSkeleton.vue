<script setup lang="ts">
// 只在 chunk 加载慢于 200ms 时出现；占位高度贴近真实页面，避免切换时页面跳动。
</script>

<template>
  <div class="panel-skeleton" aria-busy="true">
    <span class="sr-only">正在加载页面…</span>
    <div class="skeleton-heading" aria-hidden="true"></div>
    <div class="skeleton-card" aria-hidden="true"></div>
    <div class="skeleton-card skeleton-card--short" aria-hidden="true"></div>
  </div>
</template>

<style scoped>
.panel-skeleton { display: grid; gap: 16px; padding-block: 4px; }
.skeleton-heading { width: 180px; height: 26px; border-radius: var(--radius-sm); }
.skeleton-card { height: 140px; border: 1px solid var(--border); border-radius: var(--radius-lg); }
.skeleton-card--short { height: 88px; }
.skeleton-heading,
.skeleton-card { background: var(--surface-subtle); animation: skeleton-pulse 1.6s ease-in-out infinite; }
@keyframes skeleton-pulse { 0%, 100% { opacity: 1; } 50% { opacity: .5; } }
@media (prefers-reduced-motion: reduce) { .skeleton-heading, .skeleton-card { animation: none; } }
</style>
