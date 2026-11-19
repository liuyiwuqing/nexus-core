<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import ConsoleField from './ConsoleField.vue';
import ConsoleForm from './ConsoleForm.vue';
import { fieldRule } from './formRules';
import { NButton, NInput } from 'naive-ui';
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ApiFailure, readFailure } from './failure';
import type { Api, Device, DeviceQueuePlan } from './types';

const props = defineProps<{ device: Device; api: Api; busy: boolean }>();
const emit = defineEmits<{ close: []; changed: [] }>();
const path = `devices/${encodeURIComponent(props.device.deviceId)}/deliveries/cancel`;
const plan = ref<DeviceQueuePlan | null>(null), draft = ref('');
const loading = ref(false), saving = ref(false), loadError = ref(''), submitError = ref(''), notice = ref('');
let alive = true, generation = 0;

const reasonNames: Record<string, string> = {
  not_pending: '已发送，等待回执', attempted: '已经开始投递', cancel_blocked: '历史记录限制撤销',
  message_expired: '消息已过期', message_read: '消息已读', device_disabled: '设备已停用',
};
const skippedSummary = computed(() => {
  const counts = new Map<string, number>();
  for (const item of plan.value?.skipped ?? []) counts.set(item.reason ?? '', (counts.get(item.reason ?? '') ?? 0) + 1);
  return [...counts].map(([reason, count]) => `${reasonNames[reason] ?? '其它原因'} ${count}`).join(' · ');
});

async function loadPlan() {
  const current = ++generation;
  if (!plan.value) loading.value = true;
  loadError.value = '';
  try {
    const value = await props.api<DeviceQueuePlan>(`${path}?dryRun=true`, 'POST');
    if (alive && current === generation) plan.value = value;
  } catch (failure) {
    if (alive && current === generation) {
      plan.value = null;
      loadError.value = readFailure(failure, '待发队列读取失败，请稍后重试。');
    }
  } finally { if (alive && current === generation) loading.value = false; }
}

async function cancelQueue() {
  const current = plan.value;
  if (!current || props.busy || saving.value || draft.value !== props.device.name || current.counts.candidates === 0) return;
  saving.value = true; submitError.value = ''; notice.value = '';
  try {
    const result = await props.api<DeviceQueuePlan>(`${path}?confirm=CANCEL_DEVICE_DELIVERIES`, 'POST');
    if (!alive) return;
    notice.value = result.applied
      ? `已撤销 ${result.applied} 条尚未开始发送的投递。`
      : '没有投递被撤销，队列可能已被其他操作更新。';
    draft.value = '';
    emit('changed');
    await loadPlan();
  } catch (failure) {
    if (!alive) return;
    const status = failure instanceof ApiFailure ? failure.status : 0;
    submitError.value = status === 409 ? '待撤销队列超过 500 条，未执行撤销。'
      : status === 404 ? '设备不存在，请刷新设备列表。'
        : status ? readFailure(failure, '撤销失败，队列未改变。')
          : '连接中断，撤销结果尚未确认；请先核对下方计数，不要重复提交。';
    // 未知结果先重读队列，页面显示的是服务端事实而不是上一次计划。
    await loadPlan();
  } finally { if (alive) saving.value = false; }
}

onMounted(() => { void loadPlan(); });
onUnmounted(() => { alive = false; generation++; });
</script>

<template>
  <section
    id="device-queue-panel"
    class="management-panel"
    tabindex="-1"
    :aria-label="`${device.name}待发投递撤销`"
    @keydown.esc="emit('close')"
  >
    <div class="record-heading">
      <h2>撤销待发投递</h2>
      <div class="management-actions">
        <NButton attr-type="button" class="text-button" :disabled="loading || saving" @click="loadPlan">刷新</NButton>
        <NButton attr-type="button" class="text-button" @click="emit('close')">收起</NButton>
      </div>
    </div>
    <p class="management-hint">只撤销尚未开始发送的投递；已发送等待回执的消息会保留，也不会从设备撤回。</p>
    <ConsoleState v-if="loadError" kind="error" compact>{{ loadError }}</ConsoleState>
    <ConsoleState v-else-if="loading && !plan" kind="loading" compact>正在核对待发投递…</ConsoleState>
    <template v-else-if="plan">
      <p class="device-seen">
        <span>待接收 <strong>{{ plan.counts.waiting }}</strong> 条</span>
        <span>可撤销 <strong>{{ plan.counts.candidates }}</strong> 条</span>
        <span v-if="plan.counts.skipped">保留 <strong>{{ plan.counts.skipped }}</strong> 条</span>
      </p>
      <p v-if="skippedSummary" class="management-hint">保留原因：{{ skippedSummary }}</p>
      <ConsoleState v-if="plan.counts.candidates === 0" compact>当前没有可撤销的投递。</ConsoleState>
      <ConsoleState v-if="submitError" kind="error" compact>{{ submitError }}</ConsoleState>
      <ConsoleState v-else-if="notice" kind="success" compact>{{ notice }}</ConsoleState>
      <ConsoleForm v-if="plan.counts.candidates" class="device-name-form" @submit.prevent="cancelQueue">
        <ConsoleField
          path="field-1"
          :rule="fieldRule(() => draft, { required: true, maxlength: 80, disabled: () => busy || saving })"
          :label-props="{ id: 'devicequeuepanel-field-1-label', for: 'devicequeuepanel-field-1' }"
        >
          <template #label>输入设备名称确认</template>
          <NInput
            :disabled="busy || saving"
            v-model:value="draft"
            :maxlength="80"
            :placeholder="device.name"
            :input-props="{ required: true, id: 'devicequeuepanel-field-1', 'aria-label': '输入设备名称确认', spellcheck: 'false' }"
          />
        </ConsoleField>
        <div class="management-actions">
          <NButton
            type="error"
            attr-type="submit"
            class="primary"
            :disabled="busy || saving || draft !== device.name"
          >
            {{ saving ? '正在撤销…' : `撤销 ${plan.counts.candidates} 条投递` }}
          </NButton>
          <NButton attr-type="button" :disabled="saving" @click="emit('close')">取消</NButton>
        </div>
      </ConsoleForm>
    </template>
  </section>
</template>
