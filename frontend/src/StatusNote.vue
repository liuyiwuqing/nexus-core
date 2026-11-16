<script setup lang="ts">
// 同样重要的"操作结果尚未确认"，此前在 9 个面板里分别写成红字 .inline-error、灰字 .scope-note
// 和没有任何样式的裸 <p>，视觉重量差三档。统一到一个组件，按语义选 tone。
// 保留 .scope-note 类名：既有 CSS 与跨仓断言都以它为钩子，这里只在其上叠加 tone。
withDefaults(defineProps<{ tone?: 'info' | 'warn' | 'danger' }>(), { tone: 'info' });
</script>

<template>
  <div class="scope-note status-note" :class="'status-note--' + tone" role="status">
    <span v-if="tone !== 'info'" class="status-note-dot" aria-hidden="true"></span>
    <span class="status-note-text"><slot></slot></span>
    <span v-if="$slots.actions" class="status-note-actions"><slot name="actions"></slot></span>
  </div>
</template>
