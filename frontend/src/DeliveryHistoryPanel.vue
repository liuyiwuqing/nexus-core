<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import StatusNote from './StatusNote.vue';
import { NButton } from 'naive-ui';
import { useAccess } from './access';
const { operate } = useAccess();
import { onMounted, onUnmounted, ref } from 'vue';
import { deliveryNames, errorNames, type Api, type DeliveryHistory } from './types';

import { readFailure } from './failure';
const props = defineProps<{ eventId: string; api: Api; busy: boolean }>();
const emit = defineEmits<{ close: []; changed: [] }>();
const panel = ref<HTMLElement | null>(null), data = ref<DeliveryHistory | null>(null), page = ref(1);
const loading = ref(false), sending = ref(false), loadError = ref(''), cancelError = ref(''), notice = ref('');
const confirmCancel = ref(false), uncertain = ref(false);
const kindNames: Record<string, string> = { baseline: '开始留存（历史未知）', created: '建立投递', attempt_reserved: '预留发送尝试', retry_requested: '重新排队', state_changed: '状态变化', detail_changed: '信息更新' };
const path = `deliveries/${encodeURIComponent(props.eventId)}`;
let alive = true, generation = 0, controller: AbortController | undefined;
function historyTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? '—' : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium', hour12: false }).format(date);
}

async function load(keepVisible = false) {
  if (loading.value || sending.value) return;
  controller?.abort(); controller = new AbortController(); const current = ++generation;
  loading.value = true; if (!keepVisible) data.value = null; loadError.value = '';
  try {
    let value = await props.api<DeliveryHistory>(`${path}/history?page=${page.value}`, 'GET', undefined, controller.signal);
    if (!alive || current !== generation) return;
    const last = Math.max(1, Math.ceil(value.total / value.size));
    if (page.value > last) {
      page.value = last;
      value = await props.api<DeliveryHistory>(`${path}/history?page=${page.value}`, 'GET', undefined, controller.signal);
    }
    if (!alive || current !== generation) return;
    data.value = value;
    if (uncertain.value && value.delivery.status === 'cancelled') {
      uncertain.value = false; confirmCancel.value = false; cancelError.value = ''; notice.value = '已核对：投递已取消。'; emit('changed');
    } else if (!value.delivery.canCancel) {
      confirmCancel.value = false;
      if (uncertain.value) { uncertain.value = false; cancelError.value = errorNames.delivery_not_cancellable!; emit('changed'); }
    }
  } catch (failure) {
    if (alive && current === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) {
      data.value = null; loadError.value = readFailure(failure, '时间线读取失败，请重试。');
    }
  } finally { if (alive && current === generation) loading.value = false; }
}

async function cancel() {
  if (sending.value || loading.value || props.busy || !data.value?.delivery.canCancel || (!confirmCancel.value && !uncertain.value)) return;
  generation++; controller?.abort(); sending.value = true; cancelError.value = ''; notice.value = '';
  try {
    await props.api(`${path}/cancel`, 'POST');
    if (!alive) return;
    uncertain.value = false; confirmCancel.value = false; notice.value = '投递已取消。'; emit('changed');
  } catch (failure) {
    if (!alive) return;
    const status = failure instanceof Error && 'status' in failure ? Number(failure.status) : 0;
    const rejected = status >= 400 && status < 500 && status !== 408;
    uncertain.value = !rejected;
    if (rejected) confirmCancel.value = false;
    cancelError.value = rejected ? (failure as Error).message : '取消结果尚未确认。请刷新核对，或重试取消同一投递。';
  } finally {
    if (alive) { sending.value = false; await load(); }
  }
}
function changePage(value: number) { if (loading.value || sending.value) return; page.value = value; void load(); }
const timer = setInterval(() => { if (!sending.value && !props.busy && document.visibilityState === 'visible') void load(true); }, 5000);
onMounted(() => { panel.value?.focus(); void load(); });
onUnmounted(() => { alive = false; generation++; controller?.abort(); clearInterval(timer); });
</script>

<template>
  <section ref="panel" class="management-panel delivery-history-panel" tabindex="-1" aria-label="投递状态时间线">
    <div class="record-heading"><h2>投递状态时间线</h2><div class="management-actions"><NButton attr-type="button" class="text-button" :disabled="loading || sending" @click="load()">刷新时间线</NButton><NButton attr-type="button" class="text-button" @click="emit('close')">收起时间线</NButton></div></div>
    <p class="management-hint mono">{{ eventId }}</p>
    <StatusNote v-if="cancelError" :tone="uncertain ? 'warn' : 'danger'">{{ cancelError }}</StatusNote>
    <ConsoleState v-else-if="notice" kind="success" compact>{{ notice }}</ConsoleState>
    <ConsoleState v-if="loading && !data" kind="loading" compact>正在读取时间线…</ConsoleState>
    <ConsoleState v-else-if="loadError" kind="error" compact>{{ loadError }}</ConsoleState>
    <template v-else-if="data">
      <ConsoleState v-if="loading" kind="loading" compact>正在更新时间线…</ConsoleState>
      <div class="management-status"><span class="badge">{{ deliveryNames[data.delivery.status] || data.delivery.status }}</span><span>本轮尝试 {{ data.delivery.attempts }}</span><NButton attr-type="button" v-if="operate && data.delivery.canCancel && !confirmCancel && !uncertain" class="secondary compact" :disabled="busy || sending || loading" @click="confirmCancel = true">取消投递</NButton></div>
      <div v-if="operate && data.delivery.canCancel && (confirmCancel || uncertain)" class="delivery-cancel-confirm">
        <p>取消这条尚未开始发送的投递？不删除消息，也不影响其他设备。</p>
        <div class="management-actions"><NButton attr-type="button" class="secondary compact" :disabled="sending || busy || loading" @click="cancel">{{ sending ? '正在取消…' : uncertain ? '重试取消同一投递' : '确认取消' }}</NButton><NButton attr-type="button" v-if="!uncertain" class="text-button" :disabled="sending" @click="confirmCancel = false">暂不取消</NButton></div>
      </div>
      <p v-if="!data.includesCreation" class="management-hint" role="status">仅记录开始留存后的变化，此前历史未知。</p>
      <ConsoleState v-if="!data.items.length" compact>尚无可读取的变化记录。</ConsoleState>
      <ol v-else class="delivery-timeline"><li v-for="item in data.items" :key="item.sequence">
        <div class="record-heading"><strong>{{ kindNames[item.kind] || '状态记录' }}</strong><time :datetime="item.recordedAt">{{ historyTime(item.recordedAt) }}</time></div>
        <p><template v-if="item.previousStatus">{{ deliveryNames[item.previousStatus] || item.previousStatus }} → </template>{{ deliveryNames[item.status] || item.status }} · 本轮尝试 {{ item.attempts }}</p>
        <p v-if="item.lastError" class="management-hint">{{ errorNames[item.lastError] || item.lastError }}</p>
      </li></ol>
      <div class="pagination"><NButton attr-type="button" :disabled="page <= 1 || loading || sending" @click="changePage(page - 1)">上一页</NButton><span>{{ page }} / {{ Math.max(1, Math.ceil(data.total / data.size)) }}</span><NButton attr-type="button" :disabled="page * data.size >= data.total || loading || sending" @click="changePage(page + 1)">下一页</NButton></div>
      <p class="management-hint">记录 CORE 已提交的状态变化；预留尝试不代表发送成功，设备回执分别确认收到、展示与已读。时间按本地时区显示。</p>
    </template>
  </section>
</template>

<style scoped>
.delivery-timeline { padding-left: 22px; font-size: var(--text-base); }
.delivery-timeline li { padding: 14px 0; border-bottom: 1px solid var(--divider); }
.delivery-timeline .record-heading { flex-wrap: wrap; }
.delivery-timeline time { color: var(--muted); font-size: var(--text-sm); }
.delivery-cancel-confirm { padding: 14px; border: 1px solid var(--divider); border-radius: var(--radius-md); margin-bottom: 18px; font-size: var(--text-base); }
</style>
