<script setup lang="ts">
import { formatTime, deliveryNames, errorNames, type Delivery, type Page } from './types';
defineProps<{ data: Page<Delivery>; busy: boolean; loading: boolean; retry: (id: string) => Promise<boolean> }>();
const emit = defineEmits<{ page: [page: number] }>();
</script>
<template>
  <section>
    <div class="section-heading"><div><h1>投递</h1><p>区分已发送、设备确认与用户已读</p></div></div>
    <div v-if="!data.items.length" class="empty-state"><h2>还没有投递记录</h2><p>消息到达后，会为已启用的设备建立投递记录。</p></div>
    <div v-else class="table-shell">
      <table><thead><tr><th>消息 / 设备</th><th>状态</th><th>尝试</th><th>操作</th></tr></thead>
        <tbody><tr v-for="delivery in data.items" :key="delivery.eventId"><td><strong>{{ delivery.title }}</strong><span class="cell-sub mono">{{ delivery.deviceId }}</span></td>
          <td><span class="badge" :class="delivery.status === 'read' ? 'success' : delivery.status === 'failed' ? 'danger' : ''">{{ deliveryNames[delivery.status] || delivery.status }}</span>
            <span v-if="delivery.lastError" class="cell-sub">{{ errorNames[delivery.lastError] || delivery.lastError }}</span>
            <span v-else-if="delivery.status === 'pending' || delivery.status === 'sent'" class="cell-sub">下次尝试 {{ formatTime(delivery.nextAttemptAt) }}</span></td>
          <td>{{ delivery.attempts }}</td><td><button v-if="delivery.status === 'failed'" class="secondary compact" :disabled="busy" @click="retry(delivery.eventId)">重试</button><span v-else class="muted">—</span></td>
        </tr></tbody></table>
      <div class="pagination"><button :disabled="data.page <= 1 || loading" @click="emit('page', data.page - 1)">上一页</button><span>{{ data.page }} / {{ Math.max(1, Math.ceil(data.total / data.size)) }}</span><button :disabled="data.page * data.size >= data.total || loading" @click="emit('page', data.page + 1)">下一页</button></div>
    </div>
  </section>
</template>
