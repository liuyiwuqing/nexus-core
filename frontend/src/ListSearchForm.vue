<script setup lang="ts">
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NDatePicker, NInput } from 'naive-ui';
import { Download } from '@lucide/vue';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import type { ListSearch } from './types';
const props = defineProps<{ filters: ListSearch; kind: 'messages' | 'deliveries'; disabled: boolean; failed: boolean; total: number; exporting: boolean; statusActive: boolean }>();
const emit = defineEmits<{ apply: [filters: ListSearch]; clear: []; export: [] }>();
const draft = ref({ ...props.filters }), error = ref('');
watch(() => props.filters, value => { draft.value = { ...value }; error.value = ''; });
const dirty = computed(() => Object.keys(draft.value).some(key => draft.value[key as keyof ListSearch] !== props.filters[key as keyof ListSearch]));
const active = computed(() => props.statusActive || Object.values(props.filters).some(Boolean));
const exportMenu = ref<HTMLDetailsElement | null>(null);
function closeExport(event: PointerEvent) {
  if (event.target instanceof Node && !exportMenu.value?.contains(event.target) && exportMenu.value) exportMenu.value.open = false;
}
function escapeExport() {
  if (!exportMenu.value) return;
  exportMenu.value.open = false; exportMenu.value.querySelector('summary')?.focus();
}
onMounted(() => document.addEventListener('pointerdown', closeExport));
onUnmounted(() => document.removeEventListener('pointerdown', closeExport));
function apply() {
  error.value = '';
  if (draft.value.receivedFrom && draft.value.receivedBefore && new Date(draft.value.receivedFrom) >= new Date(draft.value.receivedBefore)) {
    error.value = '收件开始必须早于截止时间。'; return;
  }
  emit('apply', { ...draft.value, identity: draft.value.identity.trim() });
}
</script>
<template>
  <ConsoleForm class="list-filters" @submit.prevent="apply">
    <slot />
    <ConsoleField
      path="field-1"
      :rule="
        fieldRule(() => draft.identity, {
          pattern: kind === 'messages' ? '[a-zA-Z0-9_.\\-]{1,32}' : '[a-zA-Z0-9_\\-]{1,40}',
          maxlength: kind === 'messages' ? 32 : 40,
          disabled: () => disabled,
        })
      "
      :label-props="{ id: 'listsearchform-field-1' + '-label', for: 'listsearchform-field-1' }"
    >
      <template #label>{{ kind === 'messages' ? '来源标识' : '设备 ID' }}</template>
      <NInput
        :placeholder="kind === 'messages' ? '如 halo、manual' : '精确设备 ID'"
        :disabled="disabled"
        v-model:value="draft.identity"
        :maxlength="kind === 'messages' ? 32 : 40"
        :input-props="{
          pattern: kind === 'messages' ? '[a-zA-Z0-9_.\\-]{1,32}' : '[a-zA-Z0-9_\\-]{1,40}',
          spellcheck: 'false',
          'aria-label': kind === 'messages' ? '来源标识' : '设备 ID',
          id: 'listsearchform-field-1',
        }"
      />
    </ConsoleField>
    <ConsoleField
      path="field-2"
      :rule="fieldRule(() => draft.receivedFrom, { min: '0001-01-01T00:00', max: '9999-12-31T23:59', disabled: () => disabled })"
      :label-props="{ id: 'listsearchform-field-2' + '-label', for: 'listsearchform-field-2' }"
    >
      <template #label>收件开始（本地）</template>
      <NDatePicker
        @keydown.esc.stop
        aria-label="收件开始"
        :disabled="disabled"
        :formatted-value="draft.receivedFrom || null"
        @update:formatted-value="draft.receivedFrom = $event || ''"
        type="datetime"
        format="yyyy-MM-dd HH:mm"
        value-format="yyyy-MM-dd'T'HH:mm"
        clearable
      />
    </ConsoleField>
    <ConsoleField
      path="field-3"
      :rule="fieldRule(() => draft.receivedBefore, { min: '0001-01-01T00:00', max: '9999-12-31T23:59', disabled: () => disabled })"
      :label-props="{ id: 'listsearchform-field-3' + '-label', for: 'listsearchform-field-3' }"
    >
      <template #label>收件截止（不含）</template>
      <NDatePicker
        @keydown.esc.stop
        aria-label="收件截止"
        :disabled="disabled"
        :formatted-value="draft.receivedBefore || null"
        @update:formatted-value="draft.receivedBefore = $event || ''"
        type="datetime"
        format="yyyy-MM-dd HH:mm"
        value-format="yyyy-MM-dd'T'HH:mm"
        clearable
      />
    </ConsoleField>
    <div class="filter-actions">
      <NButton attr-type="submit" class="secondary" :disabled="disabled">应用筛选</NButton>
      <NButton quaternary attr-type="button" class="text-button" :disabled="disabled || (!active && !dirty)" @click="emit('clear')">
        清空筛选
      </NButton>
      <details ref="exportMenu" class="export-actions" @keydown.esc.prevent="escapeExport">
        <summary>
          <Download :size="15" aria-hidden="true" />
          导出
        </summary>
        <div class="export-menu">
          <NButton
            attr-type="button"
            class="secondary"
            :disabled="disabled || failed || total === 0 || dirty"
            title="导出当前筛选结果，最多 5000 条；仅含标识、状态和时间，不含正文。"
            @click="emit('export')"
          >
            {{ exporting ? '正在导出…' : '导出元数据' }}
          </NButton>
          <slot name="extra-actions" :blocked="disabled || failed || total === 0 || dirty" />
        </div>
      </details>
    </div>
    <p v-if="error" class="inline-error filter-feedback" role="alert">{{ error }}</p>
    <p v-else-if="dirty" class="muted filter-feedback" role="status">筛选尚未应用。</p>
  </ConsoleForm>
</template>
