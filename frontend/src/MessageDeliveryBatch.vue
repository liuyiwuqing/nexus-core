<script setup lang="ts">
import { NButton } from 'naive-ui';
import { computed, ref } from 'vue';
import ConsoleState from './ConsoleState.vue';
import StatusNote from './StatusNote.vue';
import { readFailure } from './failure';
import { batchReasonNames, type Api, type DeliveryBatch } from './types';

// 一条消息通常发给多台设备；逐条进缩略时间线撤销既慢又容易漏。这里先只读核对、再确认写入，
// 两条动作各自报告「能处理几条、其余为什么不能」，不把部分成功说成全部成功。
const props = defineProps<{ messageId: string; api: Api; busy: boolean; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean> }>();

const open = ref(false);
const loading = ref(false);
const applying = ref<'' | 'cancel' | 'retry'>(''),
  error = ref(''),
  result = ref(''),
  cancel = ref<DeliveryBatch | null>(null),
  retry = ref<DeliveryBatch | null>(null);

const deliveries = computed(() => cancel.value?.counts.deliveries ?? retry.value?.counts.deliveries ?? 0);

async function preview() {
  loading.value = true;
  error.value = '';
  try {
    const [c, r] = await Promise.all([
      props.api<DeliveryBatch>(`messages/${props.messageId}/deliveries/cancel?dryRun=true`, 'POST', {}),
      props.api<DeliveryBatch>(`messages/${props.messageId}/deliveries/retry?dryRun=true`, 'POST', {}),
    ]);
    cancel.value = c;
    retry.value = r;
  } catch (failure) {
    // 旧计划可能与当前状态无关，不能和错误提示并排显示成"还能撤销 N 条"。
    cancel.value = null;
    retry.value = null;
    error.value = readFailure(failure, '无法核对投递状态，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

async function toggle() {
  open.value = !open.value;
  if (!open.value) return;
  result.value = '';
  await preview();
}

/** 「跳过 2 条：已经尝试过发送 1、已经开始投递 1」——按原因归并，不逐条列设备。 */
function skippedText(plan: DeliveryBatch) {
  if (!plan.counts.skipped) return '';
  const grouped = new Map<string, number>();
  for (const row of plan.skipped) {
    const name = batchReasonNames[row.reason || ''] || row.reason || '其他原因';
    grouped.set(name, (grouped.get(name) ?? 0) + 1);
  }
  return `跳过 ${plan.counts.skipped} 条：${[...grouped].map(([name, count]) => `${name} ${count}`).join('、')}`;
}

async function apply(action: 'cancel' | 'retry') {
  if (applying.value || props.busy) return;
  const token = action === 'cancel' ? 'CANCEL_MESSAGE_DELIVERIES' : 'RETRY_MESSAGE_DELIVERIES';
  applying.value = action;
  let applied = 0,
    skipped = 0;
  const done = await props.mutate(async () => {
    const response = await props.api<DeliveryBatch>(`messages/${props.messageId}/deliveries/${action}?confirm=${token}`, 'POST', {});
    applied = response.applied;
    skipped = response.counts.skipped;
  }, action === 'cancel' ? '已提交批量撤销。' : '已提交批量重试。');
  applying.value = '';
  if (!done) return;
  result.value =
    action === 'cancel'
      ? `已撤销 ${applied} 条未开始的投递${skipped ? `；${skipped} 条未撤销，原因见下方核对` : ''}。`
      : `已把 ${applied} 条失败的投递重新加入队列${skipped ? `；${skipped} 条未重试，原因见下方核对` : ''}。`;
  await preview();
}
</script>

<template>
  <div class="delivery-batch">
    <NButton attr-type="button" class="text-button" :disabled="busy || loading" :aria-expanded="open" @click="toggle">
      {{ open ? '收起投递批量操作' : '投递批量操作…' }}
    </NButton>
    <p v-if="open && !error && !loading" class="delivery-batch-scope">
      这条消息共有 {{ deliveries }} 条投递记录。撤销只处理从未开始发送的投递，重试只处理已失败的投递；每条都会按消息有效期、是否已读和设备启用状态重新判断。
    </p>
    <div v-if="open" class="delivery-batch-body" :aria-busy="loading">
      <ConsoleState v-if="loading && !cancel" kind="loading">正在核对可撤销与可重试的投递…</ConsoleState>
      <StatusNote v-if="error" tone="danger">{{ error }}</StatusNote>
      <StatusNote v-if="result" tone="info">{{ result }}</StatusNote>
      <div v-if="cancel && retry" class="delivery-batch-plans">
        <div class="delivery-batch-plan">
          <p>
            <strong>可撤销 {{ cancel.counts.candidates }} 条</strong>
            <span v-if="skippedText(cancel)" class="delivery-batch-skip">{{ skippedText(cancel) }}</span>
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
          <span v-else class="delivery-batch-skip">没有可撤销的投递。</span>
        </div>
        <div class="delivery-batch-plan">
          <p>
            <strong>可重试 {{ retry.counts.candidates }} 条</strong>
            <span v-if="skippedText(retry)" class="delivery-batch-skip">{{ skippedText(retry) }}</span>
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
          <span v-else class="delivery-batch-skip">没有可重试的投递。</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.delivery-batch { margin-top: var(--space-4); padding-top: var(--space-3); border-top: 1px solid var(--divider); }
.delivery-batch-scope { margin: var(--space-2) 0 0; color: var(--muted); font-size: var(--text-sm); }
.delivery-batch-body { margin-top: var(--space-3); }
.delivery-batch-plans { display: grid; gap: var(--space-3); }
.delivery-batch-plan { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2) var(--space-3); }
.delivery-batch-plan p { margin: 0; font-size: var(--text-base); }
.delivery-batch-skip { color: var(--muted); font-size: var(--text-sm); }
</style>
