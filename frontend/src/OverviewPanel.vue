<script setup lang="ts">
import { NButton } from 'naive-ui';
import { RefreshCw } from '@lucide/vue';
import { computed, onUnmounted, ref } from 'vue';
import { formatTime, type Api, type Overview } from './types';

import { readFailure } from './failure';
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
      error.value = readFailure(failure, '概览读取失败，请重试。');
  } finally { if (current === generation) loading.value = false; }
}
void load();
const timer = setInterval(() => { if (document.visibilityState === 'visible') void load(); }, 5000);
onUnmounted(() => { generation++; controller?.abort(); clearInterval(timer); });
// 失败率只在有投递时画条形；null 表示"今日还没有投递"，不是 0%。
const failureRate = computed(() => snapshot.value?.today.deliveryFailureRate ?? null);
</script>

<template>
  <section class="overview-panel" aria-label="运行概览">
    <div class="section-heading"><div><h1 tabindex="-1">运行概览</h1><p v-if="snapshot">{{ snapshot.dayStart.slice(0, 10) }} · UTC 当日起算</p><p v-else>设备在线、今日收件与投递健康</p></div><NButton quaternary size="small" attr-type="button" class="text-button" :disabled="loading" @click="load"><template #icon><RefreshCw :size="15" :class="{ spinning: loading }" /></template>刷新概览</NButton></div>
    <p v-if="loading" class="muted" role="status" aria-busy="true">正在读取概览…</p>
    <p v-else-if="error" class="feedback error" role="alert">{{ error }}</p>
    <template v-else-if="snapshot">
      <p v-if="snapshot.mqtt !== 'ready'" class="feedback error" role="status">MQTT 连接不可用，投递可能延迟。</p>
      <dl class="pet-stats overview-stats">
        <div data-metric="online"><dt>设备在线</dt><dd>{{ snapshot.devices.online }}<small> 台</small></dd></div>
        <div data-metric="offline"><dt>设备离线</dt><dd>{{ snapshot.devices.offline }}<small> 台</small></dd></div>
        <div data-metric="messages"><dt>今日收件</dt><dd>{{ snapshot.today.messages }}<small> 条</small></dd></div>
        <div data-metric="backlog"><dt>投递积压</dt><dd>{{ snapshot.backlog.pending + snapshot.backlog.sent }}<small> 条</small></dd></div>
      </dl>
      <p class="overview-caption">共 {{ snapshot.devices.total }} 台设备 · 停用 {{ snapshot.devices.disabled }} 台（可仍在线）</p>
      <p v-if="!snapshot.devices.total" class="overview-caption">尚未登记设备。</p>
      <div class="overview-grid">
        <article class="record-card">
          <div class="record-heading"><h2>投递状态</h2><span class="badge" :class="snapshot.failedDeliveries ? 'danger' : 'success'"><i aria-hidden="true"></i>当前失败 {{ snapshot.failedDeliveries }}</span></div>
          <dl class="management-metrics">
            <div><dt>等待投递</dt><dd>{{ snapshot.backlog.pending }}</dd></div>
            <div><dt>等待设备确认</dt><dd>{{ snapshot.backlog.sent }}</dd></div>
            <div data-metric="failure-rate"><dt>今日投递失败率</dt><dd>{{ failureRate === null ? '—' : (failureRate * 100).toFixed(1) + '%' }}</dd></div>
          </dl>
          <!-- 失败率是比例而不是计数：条形只表达这一比例，空值时不画成 0%。 -->
          <div v-if="failureRate !== null" class="health-bar" :class="{ 'health-bar--warn': failureRate >= 0.1 }">
            <span class="health-bar-fill" :style="{ width: Math.min(100, failureRate * 100) + '%' }" aria-hidden="true"></span>
            <span class="sr-only">今日投递失败率 {{ (failureRate * 100).toFixed(1) }}%</span>
          </div>
          <p class="overview-caption">今日收件产生 {{ snapshot.today.deliveries }} 条投递，失败 {{ snapshot.today.failedDeliveries }} 条；积压与失败统计保留全部记录。</p>
          <p v-if="!snapshot.today.messages" class="overview-caption">今日尚未收件。</p>
        </article>
      </div>
      <p class="overview-caption overview-stamp">快照 {{ formatTime(snapshot.asOf) }}（本地时间）· 每 5 秒更新</p>
    </template>
  </section>
</template>

<style scoped>
.overview-stats { margin-bottom: var(--space-3); }
.overview-caption { font-size: var(--text-sm); color: var(--muted); }
.overview-grid { margin-top: var(--space-4); }
.overview-grid h2 { font-size: var(--text-md); }
.overview-caption + .overview-caption { margin-top: var(--space-1); }
.overview-stamp { margin-top: var(--space-4); }
.health-bar { position: relative; overflow: hidden; height: 6px; margin: var(--space-3) 0; border-radius: 999px; background: var(--divider); }
.health-bar-fill { display: block; height: 100%; border-radius: 999px; background: var(--success); transition: width var(--motion) var(--ease); }
.health-bar--warn .health-bar-fill { background: var(--warning); }
</style>
