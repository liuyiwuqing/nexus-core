<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import StatusNote from './StatusNote.vue';
import { NButton } from 'naive-ui';
import { computed, onUnmounted, ref, watch } from 'vue';
import { readFailure } from './failure';
import {
  deliveryErrorNames, deliveryNames, errorNames, formatTime,
  type Api, type MessageDeliveries, type MessageDelivery,
} from './types';

// 只读快照：动作（撤销/重试）仍走批量接口或投递页，这里不提供状态之外的推断。
const props = defineProps<{
  messageId: string; api: Api; busy: boolean; title?: string; closable?: boolean;
}>();
const emit = defineEmits<{ close: [] }>();
const data = ref<MessageDeliveries | null>(null);
const loading = ref(false);
const loadError = ref('');
let alive = true;
let generation = 0;
let controller: AbortController | undefined;

async function load() {
  if (loading.value) return;
  controller?.abort();
  controller = new AbortController();
  const current = ++generation;
  loading.value = true;
  loadError.value = '';
  try {
    const value = await props.api<MessageDeliveries>(
      `messages/${encodeURIComponent(props.messageId)}/deliveries?page=1&size=100`, 'GET', undefined, controller.signal,
    );
    if (!alive || current !== generation) return;
    data.value = value;
  } catch (failure) {
    if (!alive || current !== generation || (failure instanceof DOMException && failure.name === 'AbortError')) return;
    data.value = null;
    loadError.value = readFailure(failure, '投递状态读取失败，请重试。');
  } finally {
    if (alive && current === generation) loading.value = false;
  }
}

watch(() => props.messageId, () => { data.value = null; void load(); });
onUnmounted(() => { alive = false; generation++; controller?.abort(); });
void load();

const statusOrder = ['pending', 'sent', 'received', 'displayed', 'read', 'failed', 'expired', 'cancelled'];
const summary = computed(() => {
  if (!data.value) return '';
  const parts = Object.entries(data.value.counts)
    .sort(([a], [b]) => (statusOrder.indexOf(a) + 1 || 99) - (statusOrder.indexOf(b) + 1 || 99))
    .map(([status, count]) => `${deliveryNames[status] || status} ${count}`);
  return [`共 ${data.value.total} 条投递`, ...parts].join(' · ');
});
const incomplete = computed(() => !!data.value && data.value.total > data.value.items.length);
const reason = (item: MessageDelivery) =>
  item.lastError ? deliveryErrorNames[item.lastError] || errorNames[item.lastError] || item.lastError : '';
function timeline(item: MessageDelivery) {
  const parts: string[] = [];
  if (item.receivedAt) parts.push(`收到 ${formatTime(item.receivedAt)}`);
  if (item.displayedAt) parts.push(`展示 ${formatTime(item.displayedAt)}`);
  if (item.readAt) parts.push(`已读 ${formatTime(item.readAt)}`);
  if (parts.length) return parts.join(' · ');
  return item.status === 'pending' || item.status === 'sent' ? `下次尝试 ${formatTime(item.nextAttemptAt)}` : '—';
}
</script>

<template>
  <section class="message-receipts" :aria-busy="loading" aria-label="消息投递状态">
    <div class="record-heading">
      <h2>{{ title || '投递状态' }}</h2>
      <div class="management-actions">
        <NButton attr-type="button" class="text-button" :disabled="loading || busy" @click="load()">
          刷新投递状态
        </NButton>
        <NButton v-if="closable" attr-type="button" class="text-button" @click="emit('close')">收起</NButton>
      </div>
    </div>
    <p v-if="summary" class="management-hint">{{ summary }}</p>
    <ConsoleState v-if="loading && !data" kind="loading">正在加载投递状态…</ConsoleState>
    <StatusNote v-else-if="loadError" tone="danger">{{ loadError }}</StatusNote>
    <ConsoleState v-else-if="data && !data.items.length" compact>这条消息还没有投递记录。</ConsoleState>
    <div v-else-if="data" class="table-shell" tabindex="0" role="region" aria-label="消息投递状态，可用方向键横向滚动查看全部列">
      <table>
        <thead>
          <tr>
            <th scope="col">设备</th>
            <th scope="col">状态</th>
            <th scope="col">尝试</th>
            <th scope="col">时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in data.items" :key="item.eventId">
            <td>
              <span>{{ item.deviceName || item.deviceId }}</span>
              <span v-if="item.deviceName" class="cell-sub mono">{{ item.deviceId }}</span>
            </td>
            <td data-label="状态">
              <span class="badge" :class="item.status === 'read' ? 'success' : item.status === 'failed' ? 'danger' : ''">
                {{ deliveryNames[item.status] || item.status }}
              </span>
              <span v-if="reason(item)" class="cell-sub">{{ reason(item) }}</span>
            </td>
            <td data-label="尝试">{{ item.attempts }}</td>
            <td data-label="时间"><span class="cell-sub">{{ timeline(item) }}</span></td>
          </tr>
        </tbody>
      </table>
      <p v-if="incomplete" class="management-hint">共 {{ data.total }} 条投递，只显示前 {{ data.items.length }} 条。</p>
    </div>
  </section>
</template>
