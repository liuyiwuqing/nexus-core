<script setup lang="ts">
import { NButton } from 'naive-ui';
import { useAccess } from './access';
const { admin } = useAccess();
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { errorNames, formatRelative, formatTime, wifiDisconnectName, type Api, type Device, type DeviceConfig, type DeviceManagement, type TelemetryHistory, type TelemetryMetric } from './types';
import StatusNote from './StatusNote.vue';
import TelemetryChart from './TelemetryChart.vue';
import { readFailure } from './failure';
const props = defineProps<{ device: Device; api: Api; busy: boolean }>();
const emit = defineEmits<{ close: [] }>();
const data = ref<DeviceManagement | null>(null), form = ref<DeviceConfig | null>(null);
const loading = ref(false), sending = ref(false), loadError = ref(''), submitError = ref(''), notice = ref('');
const baseline = ref(''), formRevision = ref(0);
type Submission = { requestId: string; expectedRevision: number; config: DeviceConfig };
const uncertain = ref<Submission | null>(null);
type Audit = { sequence: number; actor: string; event: string; detail: string; createdAt: string };
const auditId = ref(''), audit = ref<Audit[]>([]), auditLoading = ref(false), auditError = ref('');
const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const dirty = computed(() => !!form.value && JSON.stringify(form.value) !== baseline.value);
const staleDraft = computed(() => !!form.value && !!data.value?.telemetry?.config &&
  (data.value.telemetry.configRevision !== formRevision.value || JSON.stringify(data.value.telemetry.config) !== baseline.value));
const disabled = computed(() => !admin.value || props.busy || sending.value || !!uncertain.value || !!loadError.value || !data.value?.canConfigure || staleDraft.value);
const stateNames = { pending: '等待下发', sent: '等待设备确认', applied: '设备已保存', rejected: '设备未接受', expired: '已到期', unconfirmed: '结果未确认' };
const eventNames: Record<string, string> = { created: '管理员提交', publish_attempt: '尝试下发', publish_unconfirmed: '下发结果未确认', applied: '设备设置已确认', rejected: '设备未接受', expired: '请求到期', unconfirmed: '等待配置回读' };
let alive = true, generation = 0, interval: ReturnType<typeof setInterval> | undefined, read: AbortController | undefined;
const path = `devices/${encodeURIComponent(props.device.deviceId)}/management`;
const ranges = [
  { id: '1h', label: '1 小时', bucket: '1m', bucketSeconds: 60, hours: 1 },
  { id: '6h', label: '6 小时', bucket: '5m', bucketSeconds: 300, hours: 6 },
  { id: '24h', label: '24 小时', bucket: '15m', bucketSeconds: 900, hours: 24 },
  { id: '7d', label: '7 天', bucket: '1h', bucketSeconds: 3600, hours: 168 },
] as const;
type RangeId = (typeof ranges)[number]['id'];
const bucketLabels: Record<string, string> = { '1m': '1 分钟桶', '5m': '5 分钟桶', '15m': '15 分钟桶', '1h': '1 小时桶', '1d': '1 天桶' };
const range = ref<RangeId>('6h');
const currentRange = computed(() => ranges.find(item => item.id === range.value)!);
const metrics: { id: TelemetryMetric; label: string }[] = [
  { id: 'heap', label: '内存' },
  { id: 'volume', label: '音量' },
  { id: 'unread', label: '未读' },
  { id: 'audio', label: '音频' },
  { id: 'network', label: '网络' },
];
const metric = ref<TelemetryMetric>('heap');
const history = ref<TelemetryHistory | null>(null), historyLoading = ref(false), historyError = ref('');
const lastPoint = computed(() => history.value?.points[history.value.points.length - 1] ?? null);
// 取不到的字段整段省略，避免出现"undefined · -52 dBm"这类拼接残句。
const networkSummary = computed(() => {
  const telemetry = data.value?.telemetry;
  if (!telemetry || (!telemetry.wifiSsid && !telemetry.wifiIp)) return '';
  return [telemetry.wifiSsid, telemetry.wifiIp, telemetry.wifiChannel ? `信道 ${telemetry.wifiChannel}` : '',
    telemetry.wifiRssi === undefined ? '' : `${telemetry.wifiRssi} dBm`].filter(Boolean).join(' · ');
});
const disconnectSummary = computed(() => {
  const telemetry = data.value?.telemetry;
  if (!telemetry?.wifiDisconnectReason) return '';
  const when = telemetry.wifiDisconnectedAt ? ` · ${formatRelative(new Date(telemetry.wifiDisconnectedAt * 1000).toISOString())}` : '';
  return wifiDisconnectName(telemetry.wifiDisconnectReason) + when;
});
let historyGeneration = 0, historyRead: AbortController | undefined, historyTimer: ReturnType<typeof setInterval> | undefined;
function resetDraft() {
  const current = data.value?.telemetry;
  if (!current?.config) return;
  form.value = copy(current.config); baseline.value = JSON.stringify(current.config); formRevision.value = current.configRevision;
}
async function refresh() {
  if (loading.value) return;
  read?.abort(); read = new AbortController(); const current = ++generation;
  loading.value = true;
  try {
    const value = await props.api<DeviceManagement>(path, 'GET', undefined, read.signal);
    if (!alive || current !== generation) return;
    data.value = value; loadError.value = '';
    if (uncertain.value && value.configRequests.some(item => item.requestId === uncertain.value!.requestId)) {
      uncertain.value = null; submitError.value = ''; notice.value = '已找到提交记录，请查看设备确认状态。'; resetDraft();
    } else if (!form.value || !dirty.value) resetDraft();
  } catch (error) {
    if (alive && !(error instanceof DOMException && error.name === 'AbortError'))
      loadError.value = error instanceof Error && 'status' in error ? error.message : '无法读取设备状态，请稍后刷新。';
  } finally { if (alive && current === generation) loading.value = false; }
}
async function loadHistory() {
  historyRead?.abort(); historyRead = new AbortController(); const current = ++historyGeneration;
  historyLoading.value = true; historyError.value = '';
  const to = new Date(), from = new Date(to.valueOf() - currentRange.value.hours * 3600_000);
  const query = `devices/${encodeURIComponent(props.device.deviceId)}/telemetry?from=${encodeURIComponent(from.toISOString())}`
    + `&to=${encodeURIComponent(to.toISOString())}&bucket=${currentRange.value.bucket}`;
  try {
    const value = await props.api<TelemetryHistory>(query, 'GET', undefined, historyRead.signal);
    if (alive && current === historyGeneration) history.value = value;
  } catch (failure) {
    if (alive && current === historyGeneration && !(failure instanceof DOMException && failure.name === 'AbortError'))
      historyError.value = readFailure(failure, '历史读取失败，请稍后重试。');
  } finally { if (alive && current === historyGeneration) historyLoading.value = false; }
}
function pickRange(id: RangeId) {
  if (range.value === id) return;
  range.value = id; history.value = null; void loadHistory();
}
function exportHistory() {
  const value = history.value;
  if (!value?.points.length) return;
  const header = ['采样时间', '接收时间', '样本数', '固件版本', '配置版本', '音量%', '亮度%', '静音', '免打扰', '未读',
    '可用内存(B)', '桶内最小(B)', '桶内最大(B)', '启动以来最小(B)', '音频欠载', '推理最长(ms)', '显示最长(ms)', '音频异常',
    'Wi-Fi IP', '信号(dBm)', 'SSID', '信道', '断线原因', '断线时间'];
  const cell = (item: unknown) => {
    const text = item === null || item === undefined ? '' : String(item);
    return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  const lines = ['\uFEFF' + header.join(','), ...value.points.map(point => [
    point.observedAt, point.receivedAt, point.count, point.firmwareVersion, point.configRevision,
    point.volume, point.brightness, point.muted, point.dnd, point.unread,
    point.freeHeap, point.freeHeapMin, point.freeHeapMax, point.minFreeHeap,
    point.audioUnderruns, point.inferenceMaxMs, point.displayMaxMs, point.audioFaulted,
    point.wifiIp, point.wifiRssi, point.wifiSsid, point.wifiChannel,
    point.wifiDisconnectReason, point.wifiDisconnectedAt,
  ].map(cell).join(','))];
  const url = URL.createObjectURL(new Blob([lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `telemetry-${props.device.deviceId}-${range.value}-${new Date().toISOString().slice(0, 16).replace('T', '-').replace(':', '')}.csv`;
  document.body.appendChild(link); link.click(); link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function refreshAll() { void refresh(); void loadHistory(); }
async function submit() {
  if (sending.value || props.busy) return;
  if (!uncertain.value) {
    if (!form.value || disabled.value || !dirty.value) return;
    uncertain.value = { requestId: crypto.randomUUID(), expectedRevision: formRevision.value, config: copy(form.value) };
  }
  sending.value = true; submitError.value = ''; notice.value = '';
  try {
    await props.api(path + '/config-requests', 'POST', uncertain.value);
    if (!alive) return;
    uncertain.value = null; notice.value = '配置请求已保存。'; resetDraft(); await refresh();
  } catch (error) {
    if (!alive) return;
    const status = error instanceof Error && 'status' in error ? Number(error.status) : 0;
    if (status >= 400 && status < 500) uncertain.value = null;
    submitError.value = status ? (error as Error).message : '连接中断，提交结果尚未确认。请刷新记录或重试同一请求。';
    await refresh();
  } finally { if (alive) sending.value = false; }
}
async function showAudit(id: string) {
  if (auditLoading.value) return;
  if (auditId.value === id) { auditId.value = ''; return; }
  auditId.value = id; audit.value = []; auditLoading.value = true; auditError.value = '';
  try {
    const result = await props.api<{ items: Audit[] }>(`${path}/config-requests/${encodeURIComponent(id)}/audit`);
    if (alive && auditId.value === id) audit.value = result.items;
  } catch (error) { if (alive) auditError.value = error instanceof Error && 'status' in error ? error.message : '无法读取操作记录。'; }
  finally { if (alive) auditLoading.value = false; }
}
function timeValue(minutes: number) { return `${Math.floor(minutes / 60)}`.padStart(2, '0') + ':' + `${minutes % 60}`.padStart(2, '0'); }
function setTime(key: 'startMinute' | 'endMinute', event: Event) {
  const value = (event.target as HTMLInputElement).value;
  if (form.value && /^\d{2}:\d{2}$/.test(value)) { const [h, m] = value.split(':').map(Number); form.value.dnd[key] = h! * 60 + m!; }
}
const duration = computed({ get: () => (form.value?.message.durationMs ?? 8000) / 1000,
  set: (value: number) => { if (form.value) form.value.message.durationMs = Math.round(value * 1000); } });
onMounted(() => {
  void refresh(); void loadHistory();
  interval = setInterval(() => { if (document.visibilityState === 'visible' && !sending.value) void refresh(); }, 3000);
  historyTimer = setInterval(() => { if (document.visibilityState === 'visible' && !sending.value) void loadHistory(); }, 60000);
});
onUnmounted(() => {
  alive = false; generation++; historyGeneration++;
  clearInterval(interval); clearInterval(historyTimer);
  read?.abort(); historyRead?.abort();
});
</script>

<template>
  <section id="device-management-panel" class="management-panel" tabindex="-1" @keydown.esc="emit('close')" :aria-label="`${device.name}设置与状态`">
    <div class="record-heading"><h2>设置与状态</h2><div class="management-actions"><NButton quaternary attr-type="button" class="text-button" :disabled="loading" @click="refreshAll">{{ loading ? '刷新中…' : '刷新状态' }}</NButton><NButton quaternary attr-type="button" class="text-button" @click="emit('close')">收起</NButton></div></div>
    <p v-if="loadError" class="inline-error" role="alert">{{ loadError }}</p>
    <p v-else-if="loading && !data" role="status">正在读取设备状态…</p>
    <template v-if="data">
      <p v-if="!data.telemetry" class="management-status">尚未收到设备状态，连接设备后将自动显示。</p>
      <template v-else>
        <div class="management-status"><span class="badge" :class="data.fresh ? 'success' : ''" :title="data.fresh ? '90 秒内收到的新遥测' : '超过 90 秒未收到新遥测'">{{ data.fresh ? '实时快照' : '快照已过期' }}</span><span>采样于 {{ formatTime(new Date(data.telemetry.observedAt * 1000).toISOString()) }}</span><span v-if="data.telemetry.muted">语音静音</span><span v-if="data.telemetry.dnd">免打扰中</span><span v-if="data.telemetry.audioFaulted" class="inline-error">音频异常</span></div>
        <dl class="management-metrics"><div><dt>固件</dt><dd>{{ data.telemetry.firmwareVersion }}</dd></div><div><dt>配置版本</dt><dd>{{ data.telemetry.configRevision }}</dd></div><div><dt>未读消息</dt><dd>{{ data.telemetry.unread }}</dd></div><div><dt>可用内存</dt><dd>{{ Math.floor(data.telemetry.freeHeap / 1024) }} KB</dd></div><div v-if="networkSummary"><dt>网络</dt><dd>{{ networkSummary }}</dd></div><div v-if="disconnectSummary"><dt>上次断线</dt><dd>{{ disconnectSummary }}</dd></div></dl>
        <p v-if="data.telemetry.config && data.telemetry.muted !== data.telemetry.config.muted" class="management-hint">本机语音策略与已保存设置不同，请在设备上核对静音选择。</p>
      </template>
      <section class="telemetry-history" aria-label="遥测历史">
        <div class="history-heading">
          <div><h3>状态历史</h3><p>来自设备已上报的遥测</p></div>
          <div class="history-range" role="group" aria-label="历史时间范围">
            <NButton v-for="item in ranges" :key="item.id" size="small" attr-type="button" :type="range === item.id ? 'primary' : 'default'" :quaternary="range !== item.id" @click="pickRange(item.id)">{{ item.label }}</NButton>
          </div>
        </div>
        <div class="history-metrics" role="group" aria-label="历史指标">
          <NButton v-for="item in metrics" :key="item.id" size="small" attr-type="button" :type="metric === item.id ? 'primary' : 'default'" :quaternary="metric !== item.id" @click="metric = item.id">{{ item.label }}</NButton>
          <NButton size="small" quaternary attr-type="button" class="history-export" :disabled="!history?.points.length" @click="exportHistory">导出 CSV</NButton>
        </div>
        <p v-if="historyError" class="inline-error" role="alert">{{ historyError }}</p>
        <p v-else-if="historyLoading && !history" class="muted" role="status">正在读取历史…</p>
        <template v-else-if="history">
          <p v-if="!history.points.length" class="history-empty">所选范围内没有采样点。</p>
          <template v-else>
            <TelemetryChart :points="history.points" :from="history.from" :to="history.to" :bucket-seconds="currentRange.bucketSeconds" :metric="metric" />
            <p class="management-hint">{{ currentRange.label }} · {{ bucketLabels[history.bucket] }} · 最后一条 {{ formatTime(lastPoint?.observedAt) }}<template v-if="history.truncated"> · 仅显示最新 2000 个点</template><br>历史按设备上报时间排序，缺失区间留空；最后一条不代表设备在线。保留 {{ history.retentionDays }} 天。</p>
          </template>
        </template>
      </section>
      <p v-if="data.configBlock" class="management-hint" role="status">{{ errorNames[data.configBlock] || '当前不能修改设置，请刷新后核对。' }}</p>
      <StatusNote v-if="staleDraft" tone="warn">设备设置已变化，你的编辑仍保留。<template #actions><NButton quaternary attr-type="button" class="text-button" :disabled="sending" @click="resetDraft">加载设备新设置</NButton></template></StatusNote>
      <p v-if="submitError" class="inline-error" role="alert">{{ submitError }}</p><p v-else-if="notice" class="management-hint" role="status">{{ notice }}</p>
      <form v-if="form" class="management-form" @submit.prevent="submit">
        <fieldset :disabled="disabled">
          <div class="form-columns"><label>音量 · {{ form.volume }}%<input v-model.number="form.volume" type="range" min="0" max="100" /></label><label>亮度 · {{ form.brightness }}%<input v-model.number="form.brightness" type="range" min="0" max="100" /></label></div>
          <p v-if="form.brightness === 0" class="management-hint">亮度为 0 将熄屏，可通过 USB 或远程恢复。</p>
          <div class="management-checks"><label class="checkbox-label"><input v-model="form.muted" type="checkbox" />语音静音（停止收听与播放）</label><label class="checkbox-label"><input v-model="form.dnd.manual" type="checkbox" />手动免打扰</label><label class="checkbox-label"><input v-model="form.dnd.allowCritical" type="checkbox" />免打扰时允许紧急提醒</label></div>
          <label class="checkbox-label"><input v-model="form.dnd.enabled" type="checkbox" />定时免打扰</label>
          <div v-if="form.dnd.enabled" class="form-columns"><label>开始时间<input type="time" :value="timeValue(form.dnd.startMinute)" required @input="setTime('startMinute', $event)" /></label><label>结束时间<input type="time" :value="timeValue(form.dnd.endMinute)" required @input="setTime('endMinute', $event)" /></label></div>
          <p v-if="form.dnd.enabled && form.dnd.startMinute === form.dnd.endMinute" class="management-hint">开始与结束时间相同，将全天免打扰。</p>
          <div class="form-columns"><label>时区<input v-model="form.timezone" list="management-timezones" maxlength="32" pattern="(Asia/Shanghai|UTC|Etc/UTC|UTC[+\-][0-9]{2}:[0-9]{2})" required /><small>固定时区，不自动切换夏令时。</small></label><label>消息容量<input v-model.number="form.message.queueDepth" type="number" min="1" max="8" step="1" required /></label></div>
          <datalist id="management-timezones"><option value="Asia/Shanghai" /><option value="UTC" /></datalist>
          <div class="form-columns"><label>消息显示时长（秒）<input v-model.number="duration" type="number" min="5" max="30" step="any" required /></label></div>
          <p v-if="data.telemetry?.config && form.message.queueDepth < data.telemetry.config.message.queueDepth" class="management-hint">缩小容量不会删除已接收消息。</p>
        </fieldset>
        <div class="form-footer"><span v-if="dirty && !uncertain">有未提交的修改</span><span v-else-if="!dirty">设置来自设备回读</span><NButton type="primary" attr-type="button" v-if="uncertain" class="primary" :disabled="sending || busy" @click="submit">{{ sending ? '正在核对…' : '重试同一请求' }}</NButton><NButton type="primary" attr-type="submit" v-else class="primary" :disabled="disabled || !dirty">{{ sending ? '正在提交…' : '保存到设备' }}</NButton></div>
      </form>
      <details v-if="data.configRequests.length" class="management-history" open>
        <summary>最近配置记录</summary>
        <div v-for="item in data.configRequests" :key="item.requestId" class="management-record">
          <div class="record-heading"><span>{{ formatTime(item.createdAt) }}</span><span class="badge" :class="item.status === 'applied' ? 'success' : ''">{{ stateNames[item.status] }}</span></div>
          <p>音量 {{ item.requestedConfig.volume }}% · 亮度 {{ item.requestedConfig.brightness }}%<span v-if="item.confirmationSource"> · {{ item.confirmationSource === 'ack' ? '设备回执确认' : '配置回读确认' }}</span></p>
          <p v-if="item.lastError" class="management-hint">{{ errorNames[item.lastError] || '设备未能确认此次设置，请核对状态。' }}<span v-if="item.deviceCode !== null">（{{ item.deviceCode }}）</span></p>
          <NButton quaternary attr-type="button" class="text-button" :disabled="auditLoading" @click="showAudit(item.requestId)">{{ auditId === item.requestId ? '收起操作记录' : '查看操作记录' }}</NButton>
          <template v-if="auditId === item.requestId"><p v-if="auditLoading" role="status">正在读取…</p><p v-if="auditError" class="inline-error" role="alert">{{ auditError }}</p><ol v-else class="audit-list"><li v-for="entry in audit" :key="entry.sequence">{{ formatTime(entry.createdAt) }} · {{ eventNames[entry.event] || '状态更新' }}<span v-if="errorNames[entry.detail]"> · {{ errorNames[entry.detail] }}</span></li></ol></template>
        </div>
      </details>
    </template>
  </section>
</template>
