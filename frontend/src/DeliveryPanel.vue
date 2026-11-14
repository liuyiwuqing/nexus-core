<script setup lang="ts">
import { nextTick, ref } from 'vue';
import DeliveryHistoryPanel from './DeliveryHistoryPanel.vue';
import ListSearchForm from './ListSearchForm.vue';
import { formatTime, deliveryNames, errorNames, type Api, type Delivery, type Page, type ListSearch } from './types';
defineProps<{ data: Page<Delivery>; api: Api; busy: boolean; loading: boolean; failed: boolean; status: string; search: ListSearch; exporting: boolean; retry: (id: string) => Promise<boolean> }>();
const emit = defineEmits<{ page: [page: number]; filter: [value: string]; search: [value: ListSearch]; clear: []; export: []; refresh: [] }>();
const selected = ref<string | null>(null);
function filterChanged(event: Event) { selected.value = null; emit('filter', (event.target as HTMLSelectElement).value); }
function pageChanged(page: number) { selected.value = null; emit('page', page); }
async function closeHistory() {
  const id = selected.value; selected.value = null; await nextTick();
  (document.getElementById(`delivery-history-${id}`) || document.querySelector<HTMLSelectElement>('select[aria-label="投递状态"]'))?.focus();
}
</script>
<template>
  <section>
    <div class="section-heading"><div><h1>投递</h1><p>{{ loading ? '正在加载…' : failed ? '未能读取投递' : `${data.total} 条投递 · 区分已发送、设备确认与用户已读` }}</p></div></div>
    <ListSearchForm kind="deliveries" :filters="search" :status-active="!!status" :disabled="loading || busy" :failed="failed" :total="data.total" :exporting="exporting" @apply="selected = null; emit('search', $event)" @clear="selected = null; emit('clear')" @export="emit('export')"><label>投递状态<select aria-label="投递状态" :value="status" :disabled="loading || busy" @change="filterChanged"><option value="">全部</option><option v-for="(name, value) in deliveryNames" :key="value" :value="value">{{ name }}</option></select></label></ListSearchForm>
    <div v-if="loading && !data.items.length" class="empty-state" role="status">正在加载投递…</div>
    <div v-else-if="failed" class="empty-state"><h2>投递读取失败</h2></div>
    <div v-else-if="!data.items.length && (status || Object.values(search).some(Boolean))" class="empty-state"><h2>没有符合条件的投递</h2></div>
    <div v-else-if="!data.items.length" class="empty-state"><h2>还没有投递记录</h2><p>消息到达后，会为已启用的设备建立投递记录。</p></div>
    <div v-else class="table-shell">
      <table><thead><tr><th>消息 / 设备</th><th>状态</th><th>尝试</th><th>操作</th></tr></thead>
        <tbody><tr v-for="delivery in data.items" :key="delivery.eventId"><td><strong>{{ delivery.title }}</strong><span class="cell-sub mono">{{ delivery.deviceId }}</span></td>
          <td><span class="badge" :class="delivery.status === 'read' ? 'success' : delivery.status === 'failed' ? 'danger' : ''">{{ deliveryNames[delivery.status] || delivery.status }}</span>
            <span v-if="delivery.lastError" class="cell-sub">{{ errorNames[delivery.lastError] || delivery.lastError }}</span>
            <span v-else-if="delivery.status === 'pending' || delivery.status === 'sent'" class="cell-sub">下次尝试 {{ formatTime(delivery.nextAttemptAt) }}</span></td>
          <td>{{ delivery.attempts }}</td><td><div class="management-actions"><button v-if="delivery.status === 'failed'" class="secondary compact" :disabled="busy" @click="retry(delivery.eventId)">重试</button><button :id="`delivery-history-${delivery.eventId}`" class="text-button" :disabled="busy" @click="selected = delivery.eventId">时间线</button></div></td>
        </tr></tbody></table>
      <div class="pagination"><button :disabled="data.page <= 1 || loading || busy" @click="pageChanged(data.page - 1)">上一页</button><span>{{ data.page }} / {{ Math.max(1, Math.ceil(data.total / data.size)) }}</span><button :disabled="data.page * data.size >= data.total || loading || busy" @click="pageChanged(data.page + 1)">下一页</button></div>
    </div>
    <DeliveryHistoryPanel v-if="selected" :key="selected" :event-id="selected" :api="api" :busy="busy" @close="closeHistory" @changed="emit('refresh')" />
  </section>
</template>
