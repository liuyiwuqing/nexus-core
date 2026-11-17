<script setup lang="ts">
import { Moon, Sun } from '@lucide/vue';
import { NButton } from 'naive-ui';
import { isDark, toggleColorMode } from './colorMode';
const label = () => (isDark.value ? '切换到浅色主题' : '切换到深色主题');
</script>

<template>
  <!-- 只用原生 title：浮层类组件会在 head 里注入不带 nonce 的独立样式，触发 CSP 拦截。 -->
  <NButton square :aria-label="label()" :title="label()" @click="toggleColorMode">
    <template #icon><Sun v-if="isDark" :size="16" /><Moon v-else :size="16" /></template>
  </NButton>
</template>
