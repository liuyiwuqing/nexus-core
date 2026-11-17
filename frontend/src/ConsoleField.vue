<script setup lang="ts">
import { onMounted, onUpdated, ref, useId } from 'vue';
import { NFormItem } from 'naive-ui';

defineOptions({ inheritAttrs: false });
const item = ref<InstanceType<typeof NFormItem> | null>(null);
const id = useId();
// DatePicker/TimePicker 没有 inputProps；把 Naive 标签关联到它实际渲染的可聚焦控件。
function labelControls() {
  const root = item.value?.$el as HTMLElement | undefined;
  const label = root?.querySelector<HTMLLabelElement>('.n-form-item-label');
  if (!root || !label) return;
  label.id ||= `field-${id}-label`;
  const select = root.querySelector('.n-select');
  if (select && !select.hasAttribute('aria-label')) select.setAttribute('aria-label', label.textContent?.trim() ?? '');
  const compositeName = root.querySelector('.n-date-picker[aria-label], .n-time-picker[aria-label]')?.getAttribute('aria-label');
  const controls = root.querySelectorAll<HTMLElement>(
    'input:not([type="hidden"]), textarea, [role="slider"], .n-base-selection-label, .n-base-selection-tags',
  );
  controls.forEach((control, index) => {
    if (!control.id) control.id = index === 0 && label.htmlFor ? label.htmlFor : `field-${id}-${index}`;
    if (index === 0) label.htmlFor = control.id;
    if (compositeName) control.setAttribute('aria-label', compositeName);
    if (select && control.closest('.n-base-selection')) control.setAttribute('aria-label', select.getAttribute('aria-label') ?? '');
    if (!control.hasAttribute('aria-label')) control.setAttribute('aria-labelledby', label.id);
  });
  root.querySelector('.n-date-picker[aria-label], .n-time-picker[aria-label]')?.removeAttribute('aria-label');
}
onMounted(labelControls);
onUpdated(labelControls);
</script>

<template>
  <NFormItem ref="item" v-bind="$attrs"
    ><template v-for="(_, name) in $slots" #[name]><slot :name="name" /></template
  ></NFormItem>
</template>
