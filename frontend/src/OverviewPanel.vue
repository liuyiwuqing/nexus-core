<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import OverviewTrend from './OverviewTrend.vue';
import { NButton } from 'naive-ui';
import { RefreshCw } from '@lucide/vue';
import { computed, onUnmounted, ref } from 'vue';
import { formatTime, type Api, type Overview, type OverviewAlert, type OverviewAlertTarget } from './types';

import { readFailure } from './failure';
const props = defineProps<{ api: Api }>();
const emit = defineEmits<{ drill: [target: OverviewAlertTarget] }>();
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
// 阈值来自服务端：页面不再自己写 10%，否则改阈值要两处一起改。
const thresholds = computed(() => snapshot.value?.thresholds ?? null);
const percent = (value: number) => (value * 100).toFixed(1) + '%';
// 样本太少时条形不染色：和"不报失败率告警"保持同一个判定。
const rateWarn = computed(() => {
  const today = snapshot.value?.today, limits = snapshot.value?.thresholds;
  return !!today && !!limits && today.deliveryFailureRate !== null
    && today.deliveries >= limits.failureRateMinSample && today.deliveryFailureRate >= limits.failureRate;
});
// code 是服务端契约；未知 code 原样显示，新告警不会因为前端没改版而消失。
function alertText(alert: OverviewAlert): string {
  if (alert.code === 'mqtt_connection') return 'MQTT 连接不可用，投递可能延迟。';
  if (alert.code === 'devices_online') return `没有在线设备：启用设备中在线 ${alert.value} 台，低于告警阈值 ${alert.threshold} 台。`;
  if (alert.code === 'delivery_backlog') return `投递积压 ${alert.value} 条，达到告警阈值 ${alert.threshold} 条。`;
  if (alert.code === 'delivery_failure_rate') return `今日投递失败率 ${percent(alert.value)}，达到告警阈值 ${percent(alert.threshold)}。`;
  return alert.code;
}
const levelText = (level: OverviewAlert['level']) => (level === 'error' ? '错误' : '告警');
// 目标由服务端给出：页面不猜"积压该跳到哪个筛选"，也就不可能跳到一个不存在的筛选。
function alertAction(alert: OverviewAlert): string {
  if (!alert.target) return '';
  if (alert.target.tab === 'devices') return '查看设备';
  return alert.target.status === 'failed' ? '查看失败投递' : '查看等待投递';
}
</script>

<template>
  <section class="overview-panel" aria-label="运行概览">
    <div class="section-heading"><div><h1 tabindex="-1">运行概览</h1><p v-if="snapshot">{{ snapshot.dayStart.slice(0, 10) }} · UTC 当日起算</p><p v-else>设备在线、今日收件与投递健康</p></div><NButton attr-type="button" class="text-button" :disabled="loading" @click="load"><template #icon><RefreshCw :size="15" :class="{ spinning: loading }" /></template>刷新概览</NButton></div>
    <ConsoleState v-if="loading" kind="loading" :compact="false">正在读取概览…</ConsoleState>
    <ConsoleState v-else-if="error" kind="error" compact>{{ error }}</ConsoleState>
    <template v-else-if="snapshot">
      <!-- 一次读取里命中的告警；顺序由服务端固定，页面不再排序或改写级别。 -->
      <ul v-if="snapshot.alerts.length" class="overview-alerts" role="status" aria-atomic="true">
        <li v-for="alert in snapshot.alerts" :key="alert.code" class="overview-alert"
            :class="'overview-alert--' + alert.level" :data-alert="alert.code">
          <span><span class="sr-only">{{ levelText(alert.level) }}：</span>{{ alertText(alert) }}</span>
          <NButton v-if="alert.target" attr-type="button" class="overview-alert-action"
                   :data-alert-action="alert.code" @click="emit('drill', alert.target)">{{ alertAction(alert) }}</NButton>
        </li>
      </ul>
      <dl class="pet-stats overview-stats">
        <div data-metric="online"><dt>设备在线</dt><dd>{{ snapshot.devices.online }}<small> 台</small></dd></div>
        <div data-metric="offline"><dt>设备离线</dt><dd>{{ snapshot.devices.offline }}<small> 台</small></dd></div>
        <div data-metric="messages"><dt>今日收件</dt><dd>{{ snapshot.today.messages }}<small> 条</small></dd></div>
        <div data-metric="backlog"><dt>投递积压</dt><dd>{{ snapshot.backlog.pending + snapshot.backlog.sent }}<small> 条</small></dd></div>
      </dl>
      <p class="overview-caption">共 {{ snapshot.devices.total }} 台设备 · 停用 {{ snapshot.devices.disabled }} 台（可仍在线）</p>

      <div class="overview-grid">
        <article class="record-card">
          <div class="record-heading"><h2>投递状态</h2><span class="badge" :class="snapshot.failedDeliveries ? 'danger' : 'success'"><i aria-hidden="true"></i>当前失败 {{ snapshot.failedDeliveries }}</span></div>
          <dl class="management-metrics">
            <div><dt>等待投递</dt><dd>{{ snapshot.backlog.pending }}</dd></div>
            <div><dt>等待设备确认</dt><dd>{{ snapshot.backlog.sent }}</dd></div>
            <div data-metric="failure-rate"><dt>今日投递失败率</dt><dd>{{ failureRate === null ? '—' : percent(failureRate) }}</dd></div>
          </dl>
          <!-- 失败率是比例而不是计数：条形只表达这一比例，空值时不画成 0%。 -->
          <div v-if="failureRate !== null" class="health-bar" :class="{ 'health-bar--warn': rateWarn }">
            <span class="health-bar-fill" :style="{ width: Math.min(100, failureRate * 100) + '%' }" aria-hidden="true"></span>
            <span class="sr-only">今日投递失败率 {{ (failureRate * 100).toFixed(1) }}%</span>
          </div>
          <p class="overview-caption">今日收件产生 {{ snapshot.today.deliveries }} 条投递，失败 {{ snapshot.today.failedDeliveries }} 条；积压与失败统计保留全部记录。</p>
          <p v-if="thresholds" class="overview-caption">告警阈值：今日失败率 ≥ {{ percent(thresholds.failureRate) }}（投递 ≥ {{ thresholds.failureRateMinSample }} 条才判定）、积压 ≥ {{ thresholds.backlogPending }} 条、启用设备在线 &lt; {{ thresholds.onlineMin }} 台。</p>

        </article>
      </div>
      <p class="overview-caption overview-stamp">数据库快照 {{ formatTime(snapshot.asOf) }}（本地时间）· 页面每 5 秒重新读取</p>
    </template>
    <!-- 趋势独立于快照轮询：放在快照分支外，避免每 5 秒因 snapshot 置空而重挂重读。 -->
    <OverviewTrend v-if="!error" :api="api" />
  </section>
</template>

<style scoped>
.overview-stats { margin-bottom: var(--space-3); }
.overview-alerts { display: grid; gap: var(--space-2); margin: 0 0 var(--space-3); padding: 0; list-style: none; }
.overview-alert { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); padding: var(--space-2) var(--space-3); border: 1px solid var(--warning-border); border-radius: var(--radius-md); background: var(--warning-soft); color: var(--ink-2); font-size: var(--text-sm); }
.overview-alert--error { border-color: var(--danger-border); background: var(--danger-soft); }
.overview-alert-action { flex: none; }
.overview-caption { font-size: var(--text-sm); color: var(--muted); }
.overview-grid { margin-top: var(--space-4); }
.overview-grid h2 { font-size: var(--text-md); }
.overview-caption + .overview-caption { margin-top: var(--space-1); }
.overview-stamp { margin-top: var(--space-4); }
.health-bar { position: relative; overflow: hidden; height: 6px; margin: var(--space-3) 0; border-radius: 999px; background: var(--divider); }
.health-bar-fill { display: block; height: 100%; border-radius: 999px; background: var(--success); transition: width var(--motion) var(--ease); }
.health-bar--warn .health-bar-fill { background: var(--warning); }
</style>
