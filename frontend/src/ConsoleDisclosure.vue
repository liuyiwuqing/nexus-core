<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue';
import { NButton } from 'naive-ui';
import { ChevronDown } from '@lucide/vue';

const props = withDefaults(defineProps<{ label: string; summary?: string; forceOpen?: boolean; level?: 2 | 3 }>(), { level: 2 });
const opened = ref(false), id = useId();
const expanded = computed(() => opened.value || props.forceOpen);
// 待确认/异常不能藏起来；恢复正常后也不突然收起用户正在查看的内容。
watch(() => props.forceOpen, value => { if (value) opened.value = true; }, { immediate: true });
async function toggle() {
  opened.value = !expanded.value;
  await nextTick();
  document.getElementById(`${id}-toggle`)?.focus();
}
</script>

<template>
  <section class="console-disclosure" @console-reveal="opened = true">
    <div class="disclosure-heading">
      <component :is="`h${level}`">
        <NButton attr-type="button" :id="`${id}-toggle`" class="disclosure-toggle"
          :aria-expanded="!!expanded" :aria-controls="`${id}-body`" :aria-describedby="summary ? `${id}-summary` : undefined"
          :disabled="!!forceOpen" @click="toggle">
          <template #icon><ChevronDown :size="15" :class="{ 'disclosure-open': expanded }" aria-hidden="true" /></template>
          {{ label }}
        </NButton>
      </component>
      <span v-if="summary" :id="`${id}-summary`" class="disclosure-summary">{{ summary }}</span>
    </div>
    <div v-show="expanded" :id="`${id}-body`" class="disclosure-body"><slot /></div>
  </section>
</template>
