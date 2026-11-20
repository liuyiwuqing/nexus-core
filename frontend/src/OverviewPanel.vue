<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import ConsoleForm from './ConsoleForm.vue';
import ConsoleField from './ConsoleField.vue';
import OverviewTrend from './OverviewTrend.vue';
import StatusNote from './StatusNote.vue';
import { NButton, NInputNumber, NSelect } from 'naive-ui';
import { RefreshCw } from '@lucide/vue';
import { computed, onUnmounted, ref } from 'vue';
import { deliveryErrorNames, formatTime, type AlertThresholdAudit, type AlertThresholdAuditPage, type AlertThresholdSettings, type Api, type Overview, type OverviewAlert, type OverviewAlertTarget } from './types';
import { useAccess } from './access';

import { readFailure } from './failure';
const props = defineProps<{ api: Api }>();
const emit = defineEmits<{ drill: [target: OverviewAlertTarget] }>();
const { admin } = useAccess();
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
onUnmounted(() => { generation++; thresholdGeneration++; controller?.abort(); clearInterval(timer); });
// 失败率只在有投递时画条形；null 表示"今日还没有投递"，不是 0%。
const failureRate = computed(() => snapshot.value?.today.deliveryFailureRate ?? null);
// 日起算点按服务端配置的时区显示；直接截 ISO 字符串会把 UTC 日期当成当地日期。
const localDay = computed(() => snapshot.value
  ? new Intl.DateTimeFormat('en-CA', { timeZone: snapshot.value.timezone, year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(snapshot.value.dayStart))
  : '');
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
// 阈值编辑放在快照分支外：快照每 5 秒重读一次，表单不能跟着被卸载。
// 失败率在输入框里用百分比，提交时换回 0–1，和 API 契约保持一致。
type ThresholdDraft = { failureRate: number | null; failureRateMinSample: number | null; backlogPending: number | null; onlineMin: number | null };
const editingThresholds = ref(false), thresholdSettings = ref<AlertThresholdSettings | null>(null);
const thresholdError = ref(''), thresholdBusy = ref(false);
const auditRows = ref<AlertThresholdAudit[]>([]), auditTotal = ref(0);
const auditHasMore = ref(false), auditBusy = ref(false), auditError = ref('');
type AuditRange = 'all' | '24h' | '7d' | '30d';
const auditRange = ref<AuditRange>('all');
const auditRangeOptions: { label: string; value: AuditRange }[] = [
  { label: '全部时间', value: 'all' }, { label: '近 24 小时', value: '24h' },
  { label: '近 7 天', value: '7d' }, { label: '近 30 天', value: '30d' },
];
const auditRangeLabel = computed(() => auditRangeOptions.find(option => option.value === auditRange.value)?.label ?? '全部时间');
const AUDIT_WINDOWS: Record<AuditRange, number> = { all: 0, '24h': 86400000, '7d': 604800000, '30d': 2592000000 };
let thresholdGeneration = 0;
const thresholdDraft = ref<ThresholdDraft>({ failureRate: null, failureRateMinSample: null, backlogPending: null, onlineMin: null });
function draftOf(thresholds: Overview['thresholds']): ThresholdDraft {
  return { failureRate: Math.round(thresholds.failureRate * 1000) / 10,
    failureRateMinSample: thresholds.failureRateMinSample, backlogPending: thresholds.backlogPending, onlineMin: thresholds.onlineMin };
}
function applyThresholds(result: AlertThresholdSettings) {
  thresholdSettings.value = result; thresholdDraft.value = draftOf(result.thresholds);
  auditRows.value = result.history; auditTotal.value = result.historyTotal;
  auditHasMore.value = result.history.length < result.historyTotal; auditError.value = '';
}
async function openThresholds() {
  const current = ++thresholdGeneration;
  editingThresholds.value = true; thresholdError.value = ''; thresholdSettings.value = null;
  auditRows.value = []; auditTotal.value = 0; auditHasMore.value = false; auditError.value = ''; auditRange.value = 'all';
  try {
    const result = await props.api<AlertThresholdSettings>('overview/thresholds', 'GET');
    if (current === thresholdGeneration) applyThresholds(result);
  } catch (failure) {
    if (current === thresholdGeneration) thresholdError.value = readFailure(failure, '阈值读取失败，请重试。');
  }
}
function closeThresholds() {
  thresholdGeneration++; editingThresholds.value = false; thresholdError.value = '';
  auditRows.value = []; auditTotal.value = 0; auditHasMore.value = false; auditBusy.value = false; auditError.value = '';
  auditRange.value = 'all';
}
/** 时间范围只影响审计列表；总数与 hasMore 由服务端按同一范围计算。 */
function auditQuery(before?: number): string {
  const params = new URLSearchParams({ size: '20' });
  if (before !== undefined) params.set('before', String(before));
  const window = AUDIT_WINDOWS[auditRange.value];
  if (window) params.set('from', new Date(Date.now() - window).toISOString());
  return params.toString();
}
async function loadAuditRange() {
  if (!editingThresholds.value) return;
  const current = thresholdGeneration;
  auditBusy.value = true; auditError.value = '';
  try {
    const page = await props.api<AlertThresholdAuditPage>(`overview/thresholds/audit?${auditQuery()}`, 'GET');
    if (current !== thresholdGeneration) return;
    auditRows.value = page.items; auditTotal.value = page.total; auditHasMore.value = page.hasMore;
  } catch (failure) {
    if (current === thresholdGeneration) auditError.value = readFailure(failure, '变更审计读取失败，请重试。');
  } finally { if (current === thresholdGeneration) auditBusy.value = false; }
}
async function loadOlderAudit() {
  if (auditBusy.value || !auditRows.value.length) return;
  const current = thresholdGeneration;
  const before = auditRows.value[auditRows.value.length - 1].sequence;
  auditBusy.value = true; auditError.value = '';
  try {
    const page = await props.api<AlertThresholdAuditPage>(`overview/thresholds/audit?${auditQuery(before)}`, 'GET');
    if (current !== thresholdGeneration) return;
    const known = new Set(auditRows.value.map(item => item.sequence));
    auditRows.value = [...auditRows.value, ...page.items.filter(item => !known.has(item.sequence))];
    auditTotal.value = page.total; auditHasMore.value = page.hasMore;
  } catch (failure) {
    if (current === thresholdGeneration) auditError.value = readFailure(failure, '更早的变更读取失败，请重试。');
  } finally { if (current === thresholdGeneration) auditBusy.value = false; }
}
async function saveThresholds() {
  const settings = thresholdSettings.value, draft = thresholdDraft.value;
  if (thresholdBusy.value || !settings) return;
  if (draft.failureRate === null || draft.failureRateMinSample === null || draft.backlogPending === null || draft.onlineMin === null) {
    thresholdError.value = '四项阈值都需要填写。'; return;
  }
  thresholdBusy.value = true; thresholdError.value = '';
  try {
    const result = await props.api<AlertThresholdSettings>('overview/thresholds', 'PUT', {
      version: settings.version,
      thresholds: { failureRate: draft.failureRate / 100, failureRateMinSample: draft.failureRateMinSample,
        backlogPending: draft.backlogPending, onlineMin: draft.onlineMin },
    });
    applyThresholds(result);
    if (auditRange.value !== 'all') await loadAuditRange();
    await load();
  } catch (failure) { thresholdError.value = readFailure(failure, '阈值保存失败，请重试。'); }
  finally { thresholdBusy.value = false; }
}
</script>

<template>
  <section class="overview-panel" aria-label="运行概览">
    <div class="section-heading"><div><h1 tabindex="-1">运行概览</h1><p v-if="snapshot">{{ localDay }} · {{ snapshot.timezone }} 当日起算</p><p v-else>设备在线、今日收件与投递健康</p></div><NButton attr-type="button" class="text-button" :disabled="loading" @click="load"><template #icon><RefreshCw :size="15" :class="{ spinning: loading }" /></template>刷新概览</NButton></div>
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
        <!-- 在线/离线计数本身就是设备页已有的状态筛选入口，直接跳过去看是哪些设备。 -->
        <div data-metric="online"><dt>设备在线</dt><dd><button type="button" class="overview-stat" data-stat="online" @click="emit('drill', { tab: 'devices', status: 'online' })">{{ snapshot.devices.online }}<small> 台</small><span class="sr-only">，按在线筛选设备</span></button></dd></div>
        <div data-metric="offline"><dt>设备离线</dt><dd><button type="button" class="overview-stat" data-stat="offline" @click="emit('drill', { tab: 'devices', status: 'offline' })">{{ snapshot.devices.offline }}<small> 台</small><span class="sr-only">，按离线筛选设备</span></button></dd></div>
        <div data-metric="messages"><dt>今日收件</dt><dd>{{ snapshot.today.messages }}<small> 条</small></dd></div>
        <div data-metric="backlog"><dt>投递积压</dt><dd>{{ snapshot.backlog.pending + snapshot.backlog.sent }}<small> 条</small></dd></div>
      </dl>
      <p class="overview-caption">共 {{ snapshot.devices.total }} 台设备 · <button type="button" class="overview-stat" data-stat="disabled" @click="emit('drill', { tab: 'devices', status: 'disabled' })">停用 {{ snapshot.devices.disabled }} 台<span class="sr-only">，按已停用筛选设备</span></button>（可仍在线）</p>

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
          <p v-if="thresholds" class="overview-caption">告警阈值：今日失败率 ≥ {{ percent(thresholds.failureRate) }}（投递 ≥ {{ thresholds.failureRateMinSample }} 条才判定）、积压 ≥ {{ thresholds.backlogPending }} 条、启用设备在线 &lt; {{ thresholds.onlineMin }} 台。<NButton v-if="admin" attr-type="button" class="text-button threshold-edit" @click="openThresholds">调整阈值</NButton></p>
          <!-- 失败原因的计数之和等于今日失败数：它是同一个派生数字，不是另算一遍。 -->
          <div v-if="snapshot.failureReasons.length" class="overview-reasons">
            <p class="overview-caption">今日失败原因（合计 {{ snapshot.today.failedDeliveries }} 条）</p>
            <ul class="overview-reason-list">
              <li v-for="reason in snapshot.failureReasons" :key="reason.code" :data-reason="reason.code">
                <span class="overview-reason-name">{{ deliveryErrorNames[reason.code] || reason.code }}</span>
                <strong class="overview-reason-count">{{ reason.count }} 条</strong>
                <NButton attr-type="button" :disabled="loading"
                         @click="emit('drill', { tab: 'deliveries', status: 'failed', error: reason.code })">只看这类</NButton>
              </li>
            </ul>
          </div>

        </article>
      </div>
      <p class="overview-caption overview-stamp">数据库快照 {{ formatTime(snapshot.asOf) }}（本地时间）· 页面每 5 秒重新读取</p>
    </template>
    <!-- 阈值设置独立于快照轮询：表单放在快照分支外，保存后调用 load() 让上面的文案采用新值。 -->
    <ConsoleForm v-if="admin && editingThresholds" class="inline-form threshold-editor" @submit="saveThresholds">
      <h2>告警阈值</h2>
      <p v-if="thresholdSettings" class="overview-caption">当前 v{{ thresholdSettings.version }} · 最近更新 {{ formatTime(thresholdSettings.updatedAt) }}</p>
      <p v-else-if="!thresholdError" class="overview-caption">正在读取阈值…</p>
      <div v-if="thresholdSettings" class="threshold-fields">
        <ConsoleField label="今日失败率（%）">
          <NInputNumber v-model:value="thresholdDraft.failureRate" :min="0" :max="100" :step="0.5" :precision="1" :show-button="false"
                         :input-props="{ id: 'threshold-failure-rate' }" />
        </ConsoleField>
        <ConsoleField label="失败率最小样本（条）">
          <NInputNumber v-model:value="thresholdDraft.failureRateMinSample" :min="1" :max="100000" :show-button="false"
                         :input-props="{ id: 'threshold-failure-sample' }" />
        </ConsoleField>
        <ConsoleField label="积压告警阈值（条）">
          <NInputNumber v-model:value="thresholdDraft.backlogPending" :min="1" :max="1000000" :show-button="false"
                         :input-props="{ id: 'threshold-backlog' }" />
        </ConsoleField>
        <ConsoleField label="在线设备下限（台）">
          <NInputNumber v-model:value="thresholdDraft.onlineMin" :min="1" :max="1000" :show-button="false"
                         :input-props="{ id: 'threshold-online' }" />
        </ConsoleField>
      </div>
      <div v-if="thresholdSettings" class="threshold-audit">
        <div class="threshold-audit-head">
          <p class="overview-caption">变更审计（已显示 {{ auditRows.length }} / 共 {{ auditTotal }} 条，最新在前<span v-if="auditRange !== 'all'"> · {{ auditRangeLabel }}</span>）</p>
          <NSelect v-model:value="auditRange" :options="auditRangeOptions" size="small" class="audit-range"
                   :disabled="auditBusy" :input-props="{ 'aria-label': '变更审计时间范围' }" @update:value="loadAuditRange" />
        </div>
        <ol v-if="auditRows.length">
          <li v-for="item in auditRows" :key="item.sequence">
            <span class="threshold-audit-version">v{{ item.fromVersion }} → v{{ item.toVersion }}</span>
            <span class="threshold-audit-actor">{{ item.actor }}</span>
            <span>{{ formatTime(item.recordedAt) }}</span>
            <span class="threshold-audit-values">失败率 ≥ {{ percent(item.failureRate) }} · 样本 {{ item.failureRateMinSample }} · 积压 {{ item.backlogPending }} · 在线 ≥ {{ item.onlineMin }}</span>
          </li>
        </ol>
        <p v-else class="overview-caption">{{ auditRange === 'all' ? '还没有变更记录。' : '该时间范围内还没有变更记录。' }}</p>
        <StatusNote v-if="auditError" tone="danger">{{ auditError }}</StatusNote>
        <NButton v-if="auditHasMore" attr-type="button" class="text-button" :disabled="auditBusy" @click="loadOlderAudit">
          {{ auditBusy ? '正在读取更早变更…' : '加载更早变更' }}
        </NButton>
      </div>
      <StatusNote v-if="thresholdError" tone="danger">{{ thresholdError }}</StatusNote>
      <div class="form-footer">
        <span>保存后立即用于下次概览读取，并记入变更审计。</span>
        <div class="threshold-actions">
          <NButton attr-type="button" class="text-button" :disabled="thresholdBusy" @click="closeThresholds">取消</NButton>
          <NButton type="primary" attr-type="submit" :disabled="thresholdBusy || !thresholdSettings">保存阈值</NButton>
        </div>
      </div>
    </ConsoleForm>
    <!-- 趋势独立于快照轮询：放在快照分支外，避免每 5 秒因 snapshot 置空而重挂重读。 -->
    <OverviewTrend v-if="!error" :api="api" />
  </section>
</template>

<style scoped>
.overview-stats { margin-bottom: var(--space-3); }
/* 计数保持数字排版，不套按钮边框；悬停/键盘聚焦时才显示为可点击的链接。 */
.overview-stat { display: inline; padding: 0; border: 0; background: none; color: inherit; font: inherit; font-variant-numeric: tabular-nums; text-align: left; vertical-align: baseline; }
.overview-stat:hover, .overview-stat:focus-visible { color: var(--accent); text-decoration: underline; text-underline-offset: 4px; }
.overview-alerts { display: grid; gap: var(--space-2); margin: 0 0 var(--space-3); padding: 0; list-style: none; }
.overview-alert { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); padding: var(--space-2) var(--space-3); border: 1px solid var(--warning-border); border-radius: var(--radius-md); background: var(--warning-soft); color: var(--ink-2); font-size: var(--text-sm); }
.overview-alert--error { border-color: var(--danger-border); background: var(--danger-soft); }
.overview-alert-action { flex: none; }
.overview-reasons { margin-top: var(--space-3); }
.overview-reason-list { display: grid; gap: var(--space-1); margin: var(--space-2) 0 0; padding: 0; list-style: none; }
.overview-reason-list li { display: flex; align-items: center; gap: var(--space-2); padding: var(--space-1) 0; border-bottom: 1px solid var(--divider); font-size: var(--text-sm); }
.overview-reason-name { flex: 1; }
.overview-reason-count { color: var(--ink-2); font-variant-numeric: tabular-nums; white-space: nowrap; }
.overview-caption { font-size: var(--text-sm); color: var(--muted); }
.overview-grid { margin-top: var(--space-4); }
.overview-grid h2 { font-size: var(--text-md); }
.overview-caption + .overview-caption { margin-top: var(--space-1); }
.overview-stamp { margin-top: var(--space-4); }
.threshold-edit { margin-left: var(--space-2); }
.threshold-editor { margin-top: var(--space-4); }
.threshold-fields { display: grid; grid-template-columns: repeat(auto-fit,minmax(180px,1fr)); gap: var(--space-2) var(--space-3); }
.threshold-fields :deep(.n-form-item) { margin-bottom: 0; }
.threshold-actions { display: flex; gap: var(--space-2); }
.threshold-audit { margin-top: var(--space-3); padding-top: var(--space-2); border-top: 1px solid var(--divider); }
.threshold-audit-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); flex-wrap: wrap; }
.audit-range { width: 132px; }
.threshold-audit ol { display: grid; gap: var(--space-1); margin: var(--space-1) 0; padding: 0; list-style: none; }
.threshold-audit li { display: flex; flex-wrap: wrap; gap: 2px var(--space-2); padding: var(--space-1) 0;
  border-bottom: 1px solid var(--divider); color: var(--ink-2); font-size: var(--text-sm); }
.threshold-audit li:last-child { border-bottom: 0; }
.threshold-audit-version { color: var(--ink); font-variant-numeric: tabular-nums; }
.threshold-audit-actor { overflow-wrap: anywhere; }
.threshold-audit-values { flex-basis: 100%; }
.health-bar { position: relative; overflow: hidden; height: 6px; margin: var(--space-3) 0; border-radius: 999px; background: var(--divider); }
.health-bar-fill { display: block; height: 100%; border-radius: 999px; background: var(--success); transition: width var(--motion) var(--ease); }
.health-bar--warn .health-bar-fill { background: var(--warning); }
</style>
