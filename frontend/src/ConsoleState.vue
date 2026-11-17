<script setup lang="ts">
import { CircleAlert, CircleCheck, Inbox, Info, LoaderCircle } from '@lucide/vue';
import { NSkeleton } from 'naive-ui';

// 只负责展示：请求、权限、重试和旧数据处理仍由各面板决定。
withDefaults(defineProps<{
  kind?: 'loading' | 'empty' | 'error' | 'warn' | 'info' | 'success';
  title?: string;
  compact?: boolean;
}>(), { kind: 'empty', compact: false });
</script>

<template>
  <div
    class="console-state"
    :class="['console-state--' + kind, {
      'is-compact': compact,
      'empty-state': kind === 'empty',
      'loading-state': kind === 'loading',
      feedback: ['error', 'warn', 'success'].includes(kind),
      error: kind === 'error',
    }]"
    :role="kind === 'error' ? 'alert' : 'status'"
    :aria-busy="kind === 'loading' ? true : undefined"
    aria-atomic="true"
  >
    <component
      :is="kind === 'loading' ? LoaderCircle : kind === 'empty' ? Inbox : kind === 'error' || kind === 'warn' ? CircleAlert : kind === 'success' ? CircleCheck : Info"
      class="console-state-icon"
      :class="{ spinning: kind === 'loading' }"
      :size="compact ? 16 : 20"
      aria-hidden="true"
    />
    <div class="console-state-body">
      <h2 v-if="title && kind === 'empty' && !compact">{{ title }}</h2>
      <strong v-else-if="title">{{ title }}</strong>
      <div v-if="$slots.default" class="console-state-description"><slot /></div>
      <div v-if="kind === 'loading' && !compact" class="console-state-skeleton" aria-hidden="true">
        <NSkeleton v-for="line in 3" :key="line" text :width="line === 3 ? '65%' : '100%'" />
      </div>
    </div>
    <div v-if="$slots.actions" class="console-state-actions"><slot name="actions" /></div>
  </div>
</template>
