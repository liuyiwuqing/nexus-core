<script setup lang="ts">
import { NButton } from 'naive-ui';
import { computed, ref, watch } from 'vue';
import ConsoleState from './ConsoleState.vue';
import StatusNote from './StatusNote.vue';
import { readFailure } from './failure';
import { batchReasonNames, type Api, type DeliveryMultiBatch } from './types';

// 一次核对多条消息：撤销只处理从未开始发送的投递，重试只处理已失败的投递。
// 服务端把整单放在一个事务里，任一消息不可读就整单拒绝，所以这里不展示"部分成功"的选项。
const props = defineProps<{ messageIds: string[]; api: Api; busy: boolean; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean> }>();

const loading = ref(false);
const applying = ref<'' | 'cancel' | 'retry'>(''),
  error = ref(''),
  result = ref(''),
  cancel = ref<DeliveryMultiBatch | null>(null),
  retry = ref<DeliveryMultiBatch | null>(null);

const messages = computed(() => cancel.value?.counts.messages ?? retry.value?.counts.messages ?? props.messageIds.length);
const deliveries = computed(() => cancel.value?.counts.deliveries ?? retry.value?.counts.deliveries ?? 0);

async function preview() {
  loading.value = true;
  error.value = '';
  try {
    const [c, r] = await Promise.all([
      props.api<DeliveryMultiBatch>('deliveries/batch-cancel?dryRun=true', 'POST', { messageIds: props.messageIds }),
      props.api<DeliveryMultiBatch>('deliveries/batch-retry?dryRun=true', 'POST', { messageIds: props.messageIds }),
    ]);
    cancel.value = c;
    retry.value = r;
  } catch (failure) {
    // 旧计划已经和当前选择无关，不能和错误提示并排显示成"还能撤销 N 条"。
    cancel.value = null;
    retry.value = null;
    error.value = readFailure(failure, '无法核对所选消息的投递状态，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.messageIds.join(','),
  () => {
    result.value = '';
    void preview();
  },
  { immediate: true },
);

/** 「跳过 2 条：已经尝试过发送 1、消息已读 1」——跨消息合并原因，不逐条列设备。 */
function skippedText(plan: DeliveryMultiBatch) {
  if (!plan.counts.skipped) return '';
  const grouped = new Map<string, number>();
  for (const message of plan.messages)
    for (const row of message.skipped) {
      const name = batchReasonNames[row.reason || ''] || row.reason || '其他原因';
      grouped.set(name, (grouped.get(name) ?? 0) + 1);
    }
  return `跳过 ${plan.counts.skipped} 条：${[...grouped].map(([name, count]) => `${name} ${count}`).join('、')}`;
}

async function apply(action: 'cancel' | 'retry') {
  if (applying.value || props.busy) return;
  const token = action === 'cancel' ? 'CANCEL_MESSAGE_DELIVERIES_BATCH' : 'RETRY_MESSAGE_DELIVERIES_BATCH';
  applying.value = action;
  let applied = 0,
    skipped = 0,
    touched = 0;
  const done = await props.mutate(async () => {
    const response = await props.api<DeliveryMultiBatch>(`deliveries/batch-${action}?confirm=${token}`, 'POST', { messageIds: props.messageIds });
    applied = response.applied;
    skipped = response.counts.skipped;
    touched = response.messages.filter(message => message.applied > 0).length;
  }, action === 'cancel' ? '已提交跨消息批量撤销。' : '已提交跨消息批量重试。');
  applying.value = '';
  if (!done) return;
  result.value =
    action === 'cancel'
      ? `已撤销 ${applied} 条未开始的投递，涉及 ${touched} 条消息${skipped ? `；${skipped} 条未撤销，原因见下方核对` : ''}。`
      : `已把 ${applied} 条失败的投递重新加入队列，涉及 ${touched} 条消息${skipped ? `；${skipped} 条未重试，原因见下方核对` : ''}。`;
  await preview();
}
</script>

<template>
  <div class="multi-batch" :aria-busy="loading">
    <p v-if="!error && !loading" class="multi-batch-scope">
      已选 {{ messages }} 条消息，共 {{ deliveries }} 条投递记录。任一所选消息无法读取时整单拒绝，不做部分处理；每条投递都会按消息有效期、是否已读和设备启用状态重新判断。
    </p>
    <ConsoleState v-if="loading && !cancel" kind="loading">正在核对所选消息的可撤销与可重试投递…</ConsoleState>
    <StatusNote v-if="error" tone="danger">{{ error }}</StatusNote>
    <StatusNote v-if="result" tone="info">{{ result }}</StatusNote>
    <div v-if="cancel && retry" class="multi-batch-plans">
      <div class="multi-batch-plan">
        <p>
          <strong>可撤销 {{ cancel.counts.candidates }} 条</strong>
          <span v-if="skippedText(cancel)" class="multi-batch-skip">{{ skippedText(cancel) }}</span>
        </p>
        <NButton
          v-if="cancel.counts.candidates"
          attr-type="button"
          class="secondary compact"
          :disabled="busy || !!applying"
          @click="apply('cancel')"
        >
          {{ applying === 'cancel' ? '正在撤销…' : `确认撤销 ${cancel.counts.candidates} 条` }}
        </NButton>
        <span v-else class="multi-batch-skip">没有可撤销的投递。</span>
      </div>
      <div class="multi-batch-plan">
        <p>
          <strong>可重试 {{ retry.counts.candidates }} 条</strong>
          <span v-if="skippedText(retry)" class="multi-batch-skip">{{ skippedText(retry) }}</span>
        </p>
        <NButton
          v-if="retry.counts.candidates"
          attr-type="button"
          class="secondary compact"
          :disabled="busy || !!applying"
          @click="apply('retry')"
        >
          {{ applying === 'retry' ? '正在重试…' : `确认重试 ${retry.counts.candidates} 条` }}
        </NButton>
        <span v-else class="multi-batch-skip">没有可重试的投递。</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.multi-batch { margin: 0 0 var(--space-3); padding: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--surface); }
.multi-batch-scope { margin: 0 0 var(--space-3); color: var(--muted); font-size: var(--text-sm); }
.multi-batch-plans { display: grid; gap: var(--space-3); }
.multi-batch-plan { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2) var(--space-3); }
.multi-batch-plan p { margin: 0; font-size: var(--text-base); }
.multi-batch-skip { color: var(--muted); font-size: var(--text-sm); }
</style>
