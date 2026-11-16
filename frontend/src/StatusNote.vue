<script setup lang="ts">
import { ref } from 'vue';
// 同样重要的"操作结果尚未确认"，此前在 9 个面板里分别写成红字 .inline-error、灰字 .scope-note
// 和没有任何样式的裸 <p>，视觉重量差三档。统一到一个组件，按语义选 tone。
// 保留 .scope-note 类名：既有 CSS 与跨仓断言都以它为钩子，这里只在其上叠加 tone。
// warn/danger 是「必须让用户知道且要处理」的信息，用 alert（assertive）而不是 status，
// 否则它只在提交失败时插入，polite 排在其他播报之后容易被吞。
withDefaults(defineProps<{ tone?: 'info' | 'warn' | 'danger' }>(), { tone: 'info' });
// 提交期间点按钮会 disabled，Chrome 立刻把焦点丢到 body；提示出现后由调用方把焦点接过来。
const root = ref<HTMLElement | null>(null);
defineExpose({ focus: () => root.value?.focus() });
</script>

<template>
  <div ref="root" tabindex="-1" class="scope-note status-note" :class="'status-note--' + tone" :role="tone === 'info' ? 'status' : 'alert'" aria-atomic="true">
    <span v-if="tone !== 'info'" class="status-note-dot" aria-hidden="true"></span>
    <span class="status-note-text"><slot></slot></span>
    <span v-if="$slots.actions" class="status-note-actions"><slot name="actions"></slot></span>
  </div>
</template>
