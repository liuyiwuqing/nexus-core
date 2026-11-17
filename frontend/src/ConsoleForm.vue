<script setup lang="ts">
import { nextTick, ref } from 'vue';
import { NForm, type FormInst } from 'naive-ui';

defineOptions({ inheritAttrs: false });
const emit = defineEmits<{ submit: [event: Event] }>();
const form = ref<FormInst | null>(null);
const validating = ref(false);
async function submit(event: Event) {
  if (validating.value) return;
  validating.value = true;
  try {
    await form.value?.validate();
  } catch {
    await nextTick();
    const root = (event.currentTarget ?? event.target) as HTMLElement;
    root
      .querySelector<HTMLElement>(
        '.n-form-item-blank--error input, .n-form-item-blank--error textarea, .n-form-item-blank--error [tabindex="0"]',
      )
      ?.focus();
    return;
  } finally {
    validating.value = false;
  }
  emit('submit', event);
}
</script>

<template>
  <NForm ref="form" v-bind="$attrs" novalidate :show-require-mark="false" label-placement="top" @submit.prevent="submit"><slot /></NForm>
</template>
