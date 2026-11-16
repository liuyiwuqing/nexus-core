<script setup lang="ts">
import { NButton } from 'naive-ui';
import { onUnmounted, ref } from 'vue';
import { formatTime, type Api, type Overview } from './types';

const props = defineProps<{ api: Api }>();
const snapshot = ref<Overview | null>(null), loading = ref(false), error = ref('');
let generation = 0, controller: AbortController | undefined;
async function load() {
  if (loading.value) return;
  controller = new AbortController(); const current = ++generation;
  loading.value = true; snapshot.value = null; error.value = '';
  try {
    const result = await props.api<Overview>('overview', 'GET', undefined, controller.signal);
    if (current === generation) snapshot.value = result;
  } catch (failure) {
    if (current === generation && !(failure instanceof DOMException && failure.name === 'AbortError'))
      error.value = failure instanceof Error ? failure.message : '概览读取失败，请重试。';
  } finally { if (current === generation) loading.value = false; }
}
void load();
const timer = setInterval(() => { if (document.visibilityState === 'visible') void load(); }, 5000);
onUnmounted(() => { generation++; controller?.abort(); clearInterval(timer); });
</script>

<template>
  <section class="overview-panel" aria-label="运行概览">
    <div class="section-heading"><div><h1 tabindex="-1">运行概览</h1><p v-if="snapshot">{{ snapshot.dayStart.slice(0, 10) }} · UTC</p></div><NButton quaternary attr-type="button" class="text-button" :disabled="loading" @click="load">刷新概览</NButton></div>
    <p v-if="loading" class="muted" role="status" aria-busy="true">正在读取概览…</p>
    <p v-else-if="error" class="feedback error" role="alert">{{ error }}</p>
    <template v-else-if="snapshot">
      <p v-if="snapshot.mqtt !== 'ready'" class="feedback error" role="status">MQTT 连接不可用，投递可能延迟。</p>
      <dl class="pet-stats">
        <div data-metric="online"><dt>设备在线</dt><dd>{{ snapshot.devices.online }}</dd></div>
        <div data-metric="offline"><dt>设备离线</dt><dd>{{ snapshot.devices.offline }}</dd></div>
        <div data-metric="messages"><dt>今日收件</dt><dd>{{ snapshot.today.messages }}</dd></div>
        <div data-metric="backlog"><dt>投递积压</dt><dd>{{ snapshot.backlog.pending + snapshot.backlog.sent }}</dd></div>
      </dl>
      <p class="muted">共 {{ snapshot.devices.total }} 台设备 · 停用 {{ snapshot.devices.disabled }} 台（可仍在线）</p>
      <p v-if="!snapshot.devices.total" class="muted">尚未登记设备。</p>
      <article class="record-card">
        <h2>投递状态</h2>
        <dl class="management-metrics">
          <div><dt>等待投递</dt><dd>{{ snapshot.backlog.pending }}</dd></div>
          <div><dt>等待设备确认</dt><dd>{{ snapshot.backlog.sent }}</dd></div>
          <div><dt>当前失败</dt><dd>{{ snapshot.failedDeliveries }}</dd></div>
          <div data-metric="failure-rate"><dt>今日收件投递失败率</dt><dd>{{ snapshot.today.deliveryFailureRate === null ? '—' : (snapshot.today.deliveryFailureRate * 100).toFixed(1) + '%' }}</dd></div>
        </dl>
        <p class="muted">今日收件产生 {{ snapshot.today.deliveries }} 条投递，当前失败 {{ snapshot.today.failedDeliveries }} 条。积压与当前失败统计全部保留记录。</p>
      </article>
      <p v-if="!snapshot.today.messages" class="muted">今日尚未收件。</p>
      <p class="muted overview-stamp">快照 {{ formatTime(snapshot.asOf) }}（本地时间）· 每 5 秒更新</p>
    </template>
  </section>
</template>

<style scoped>
dl > div { display: block; margin-top: 0; }
dd { text-align: left; }
.management-metrics dd { white-space: nowrap; }
.overview-stamp { margin-top: 18px; font-size: 12px; }
</style>
