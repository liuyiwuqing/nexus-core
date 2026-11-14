<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ListSearch } from './types';
const props = defineProps<{ filters: ListSearch; kind: 'messages' | 'deliveries'; disabled: boolean; failed: boolean; total: number; exporting: boolean; statusActive: boolean }>();
const emit = defineEmits<{ apply: [filters: ListSearch]; clear: []; export: [] }>();
const draft = ref({ ...props.filters }), error = ref('');
watch(() => props.filters, value => { draft.value = { ...value }; error.value = ''; });
const dirty = computed(() => Object.keys(draft.value).some(key => draft.value[key as keyof ListSearch] !== props.filters[key as keyof ListSearch]));
const active = computed(() => props.statusActive || Object.values(props.filters).some(Boolean));
function apply() {
  error.value = '';
  if (draft.value.receivedFrom && draft.value.receivedBefore && new Date(draft.value.receivedFrom) >= new Date(draft.value.receivedBefore)) {
    error.value = '收件开始必须早于截止时间。'; return;
  }
  emit('apply', { ...draft.value, identity: draft.value.identity.trim() });
}
</script>
<template>
  <form class="list-filters" @submit.prevent="apply">
    <slot />
    <label>{{ kind === 'messages' ? '来源标识' : '设备 ID' }}<input v-model="draft.identity" :aria-label="kind === 'messages' ? '来源标识' : '设备 ID'"
      :maxlength="kind === 'messages' ? 32 : 40" :pattern="kind === 'messages' ? '[a-zA-Z0-9_.\\-]{1,32}' : '[a-zA-Z0-9_\\-]{1,40}'"
      :placeholder="kind === 'messages' ? '如 halo、manual' : '精确设备 ID'" :disabled="disabled" spellcheck="false" /></label>
    <label>收件开始（本地）<input v-model="draft.receivedFrom" aria-label="收件开始" type="datetime-local" min="0001-01-01T00:00" max="9999-12-31T23:59" :disabled="disabled" /></label>
    <label>收件截止（不含）<input v-model="draft.receivedBefore" aria-label="收件截止" type="datetime-local" min="0001-01-01T00:00" max="9999-12-31T23:59" :disabled="disabled" /></label>
    <div class="filter-actions">
      <button class="secondary" :disabled="disabled">应用筛选</button>
      <button class="text-button" type="button" :disabled="disabled || (!active && !dirty)" @click="emit('clear')">清空筛选</button>
      <button class="secondary" type="button" :disabled="disabled || failed || total === 0 || dirty" title="导出当前筛选结果，最多 5000 条；仅含标识、状态和时间，不含正文。" @click="emit('export')">{{ exporting ? '正在导出…' : '导出元数据' }}</button>
    </div>
    <p v-if="error" class="inline-error filter-feedback" role="alert">{{ error }}</p>
    <p v-else-if="dirty" class="muted filter-feedback" role="status">筛选尚未应用。</p>
  </form>
</template>
