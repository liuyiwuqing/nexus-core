<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import ConsoleDisclosure from './ConsoleDisclosure.vue';
import DeviceCredentialsSection from './DeviceCredentialsSection.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NCheckbox, NAutoComplete, NInputNumber, NSlider, NTimePicker } from 'naive-ui';
import { useAccess } from './access';
const { admin } = useAccess();
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { errorNames, formatRelative, formatTime, wifiDisconnectName, type Api, type Device, type DeviceConfig, type DeviceManagement, type TelemetryDisconnects, type TelemetryHistory, type TelemetryMetric } from './types';
import StatusNote from './StatusNote.vue';
import TelemetryChart from './TelemetryChart.vue';
import { readFailure } from './failure';
// historyFocus 由设备卡片的「网络趋势」传入：携带指标与请求序号，同一面板再次请求时也能生效。
const props = defineProps<{ device: Device; api: Api; busy: boolean; historyFocus?: { metric: TelemetryMetric; seq: number } | null }>();
const emit = defineEmits<{ close: [] }>();
const data = ref<DeviceManagement | null>(null), form = ref<DeviceConfig | null>(null);
// 复合控件允许编辑中暂时为空；API 仍只接收有效分钟数与毫秒数。
const timeDraft = ref({ startMinute: '', endMinute: '' }), duration = ref<number | null>(null);
const durationSummary = computed(() => duration.value == null ? '时长未知' : `${duration.value} 秒上限`);
const loading = ref(false), sending = ref(false), loadError = ref(''), submitError = ref(''), notice = ref('');
const baseline = ref(''), formRevision = ref(0);
type Submission = { requestId: string; expectedRevision: number; config: DeviceConfig };
const uncertain = ref<Submission | null>(null);
type Audit = { sequence: number; actor: string; event: string; detail: string; createdAt: string };
const auditId = ref(''), audit = ref<Audit[]>([]), auditLoading = ref(false), auditError = ref('');
const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const dirty = computed(() => !!form.value && (JSON.stringify(form.value) !== baseline.value || duration.value === null ||
  form.value.dnd.enabled && (!timeDraft.value.startMinute || !timeDraft.value.endMinute)));
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
const metric = ref<TelemetryMetric>(props.historyFocus?.metric ?? 'heap');
const historySection = ref<{ open: () => void } | null>(null);
// 面板按需渲染历史区：数据未到时先记住指标，等区块挂载后再展开；只展开一次，之后允许用户收起。
let revealPending = !!props.historyFocus;
function revealHistory() {
  if (!revealPending || !historySection.value) return;
  revealPending = false;
  historySection.value.open();
}
watch(() => props.historyFocus, value => {
  if (!value) return;
  metric.value = value.metric;
  revealPending = true;
  revealHistory();
});
watch(data, revealHistory, { flush: 'post' });
const history = ref<TelemetryHistory | null>(null), historyLoading = ref(false), historyError = ref('');
const lastPoint = computed(() => history.value?.points[history.value.points.length - 1] ?? null);
// 取不到的字段整段省略，避免出现"undefined · -52 dBm"这类拼接残句。
const networkSummary = computed(() => {
  const telemetry = data.value?.telemetry;
  if (!telemetry || (!telemetry.wifiSsid && !telemetry.wifiIp)) return '';
  return [telemetry.wifiSsid, telemetry.wifiIp, telemetry.wifiChannel ? `信道 ${telemetry.wifiChannel}` : '',
    telemetry.wifiChannelWidth ? `${telemetry.wifiChannelWidth} MHz` : '',
    telemetry.wifiRssi === undefined ? '' : `${telemetry.wifiRssi} dBm`, telemetry.wifiBssid].filter(Boolean).join(' · ');
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
  timeDraft.value = { startMinute: timeValue(current.config.dnd.startMinute), endMinute: timeValue(current.config.dnd.endMinute) };
  duration.value = current.config.message.durationMs / 1000;
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
const disconnects = ref<TelemetryDisconnects | null>(null), disconnectsLoading = ref(false), disconnectsError = ref('');
const disconnectsPath = `devices/${encodeURIComponent(props.device.deviceId)}/telemetry/disconnects?limit=20`;
// 没有断线原因、也读不到列表时不渲染整段，避免给未断线设备增加空区块。
const disconnectVisible = computed(() => !!data.value?.telemetry?.wifiDisconnectReason || !!disconnectsError.value
  || !!disconnects.value?.items.length);
let disconnectGeneration = 0, disconnectRead: AbortController | undefined;
async function loadDisconnects() {
  disconnectRead?.abort(); disconnectRead = new AbortController(); const current = ++disconnectGeneration;
  disconnectsLoading.value = true; disconnectsError.value = '';
  try {
    const value = await props.api<TelemetryDisconnects>(disconnectsPath, 'GET', undefined, disconnectRead.signal);
    if (alive && current === disconnectGeneration) disconnects.value = value;
  } catch (failure) {
    if (alive && current === disconnectGeneration && !(failure instanceof DOMException && failure.name === 'AbortError'))
      disconnectsError.value = readFailure(failure, '断线记录读取失败，请稍后重试。');
  } finally { if (alive && current === disconnectGeneration) disconnectsLoading.value = false; }
}
// 设备报告新的断线时刻后立即重读，避免等待下一次手动刷新。
watch(() => data.value?.telemetry?.wifiDisconnectedAt, (next, previous) => {
  if (previous !== undefined && next !== previous) void loadDisconnects();
});
function exportHistory() {
  const value = history.value;
  if (!value?.points.length) return;
  const header = ['采样时间', '接收时间', '样本数', '固件版本', '配置版本', '音量%', '亮度%', '静音', '免打扰', '未读',
    '可用内存(B)', '桶内最小(B)', '桶内最大(B)', '启动以来最小(B)', '音频欠载', '推理最长(ms)', '显示最长(ms)', '音频异常',
    'Wi-Fi IP', '信号(dBm)', 'SSID', '信道', '信道宽度(MHz)', 'BSSID', '断线原因', '断线时间'];
  const cell = (item: unknown) => {
    const text = item === null || item === undefined ? '' : String(item);
    return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  const lines = ['\uFEFF' + header.join(','), ...value.points.map(point => [
    point.observedAt, point.receivedAt, point.count, point.firmwareVersion, point.configRevision,
    point.volume, point.brightness, point.muted, point.dnd, point.unread,
    point.freeHeap, point.freeHeapMin, point.freeHeapMax, point.minFreeHeap,
    point.audioUnderruns, point.inferenceMaxMs, point.displayMaxMs, point.audioFaulted,
    point.wifiIp, point.wifiRssi, point.wifiSsid, point.wifiChannel, point.wifiChannelWidth, point.wifiBssid,
    point.wifiDisconnectReason, point.wifiDisconnectedAt,
  ].map(cell).join(','))];
  const url = URL.createObjectURL(new Blob([lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `telemetry-${props.device.deviceId}-${range.value}-${new Date().toISOString().slice(0, 16).replace('T', '-').replace(':', '')}.csv`;
  document.body.appendChild(link); link.click(); link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function refreshAll() { void refresh(); void loadHistory(); void loadDisconnects(); }
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
function setTime(key: 'startMinute' | 'endMinute', value: string | null) {
  timeDraft.value[key] = value ?? '';
  if (form.value && value && /^\d{2}:\d{2}$/.test(value)) { const [h, m] = value.split(':').map(Number); form.value.dnd[key] = h! * 60 + m!; }
}
function setDuration(value: number | null) {
  duration.value = value;
  if (form.value && value !== null) form.value.message.durationMs = Math.round(value * 1000);
}
onMounted(() => {
  void refresh(); void loadHistory(); void loadDisconnects();
  interval = setInterval(() => { if (document.visibilityState === 'visible' && !sending.value) void refresh(); }, 3000);
  historyTimer = setInterval(() => { if (document.visibilityState === 'visible' && !sending.value) void loadHistory(); }, 60000);
});
onUnmounted(() => {
  alive = false; generation++; historyGeneration++; disconnectGeneration++;
  clearInterval(interval); clearInterval(historyTimer);
  read?.abort(); historyRead?.abort(); disconnectRead?.abort();
});
</script>

<template>
  <section
    id="device-management-panel"
    class="management-panel"
    tabindex="-1"
    @keydown.esc="emit('close')"
    :aria-label="`${device.name}设置与状态`"
  >
    <div class="record-heading">
      <h2>设置与状态</h2>
      <div class="management-actions">
        <NButton attr-type="button" class="text-button" :disabled="loading" @click="refreshAll">
          {{ loading ? '刷新中…' : '刷新状态' }}
        </NButton>
        <NButton attr-type="button" class="text-button" @click="emit('close')">收起</NButton>
      </div>
    </div>
    <ConsoleState v-if="loadError" kind="error" compact>{{ loadError }}</ConsoleState>
    <ConsoleState v-else-if="loading && !data" kind="loading" compact>正在读取设备状态…</ConsoleState>
    <template v-if="data">
      <ConsoleState v-if="!data.telemetry" compact>尚未收到设备状态，连接设备后将自动显示。</ConsoleState>
      <template v-else>
        <div class="management-status">
          <span class="badge" :class="data.fresh ? 'success' : ''" :title="data.fresh ? '90 秒内收到的新遥测' : '超过 90 秒未收到新遥测'">
            {{ data.fresh ? '实时快照' : '快照已过期' }}
          </span>
          <span>采样于 {{ formatTime(new Date(data.telemetry.observedAt * 1000).toISOString()) }}</span>
          <span v-if="data.telemetry.muted">语音静音</span>
          <span v-if="data.telemetry.dnd">免打扰中</span>
          <span v-if="data.telemetry.audioFaulted" class="inline-error">音频异常</span>
        </div>
        <dl class="management-metrics">
          <div>
            <dt>固件</dt>
            <dd>{{ data.telemetry.firmwareVersion }}</dd>
          </div>
          <div>
            <dt>配置版本</dt>
            <dd>{{ data.telemetry.configRevision }}</dd>
          </div>
          <div>
            <dt>未读消息</dt>
            <dd>{{ data.telemetry.unread }}</dd>
          </div>
          <div>
            <dt>可用内存</dt>
            <dd>{{ Math.floor(data.telemetry.freeHeap / 1024) }} KB</dd>
          </div>
          <div v-if="networkSummary">
            <dt>网络</dt>
            <dd class="metric-detail">{{ networkSummary }}</dd>
          </div>
          <div v-if="disconnectSummary">
            <dt>上次断线</dt>
            <dd class="metric-detail">{{ disconnectSummary }}</dd>
          </div>
        </dl>
        <p v-if="data.telemetry.config && data.telemetry.muted !== data.telemetry.config.muted" class="management-hint">
          本机语音策略与已保存设置不同，请在设备上核对静音选择。
        </p>
      </template>

      <p v-if="data.configBlock" class="management-hint" role="status">
        {{ errorNames[data.configBlock] || '当前不能修改设置，请刷新后核对。' }}
      </p>
      <StatusNote v-if="staleDraft" tone="warn">
        设备设置已变化，你的编辑仍保留。
        <template #actions>
          <NButton attr-type="button" class="text-button" :disabled="sending" @click="resetDraft">加载设备新设置</NButton>
        </template>
      </StatusNote>
      <ConsoleState v-if="submitError" kind="error" compact>{{ submitError }}</ConsoleState>
      <ConsoleState v-else-if="notice" kind="success" compact>{{ notice }}</ConsoleState>
      <ConsoleForm v-if="form" class="management-form" @submit.prevent="submit">
        <div class="form-fields">
          <div class="form-columns">
            <ConsoleField
              path="field-1"
              :label-props="{ id: 'devicemanagementpanel-field-1' + '-label', for: 'devicemanagementpanel-field-1' }"
            >
              <template #label>音量 · {{ form.volume }}%</template>
              <NSlider :disabled="disabled" v-model:value="form.volume" :min="0" :max="100" />
            </ConsoleField>
            <ConsoleField
              path="field-2"
              :label-props="{ id: 'devicemanagementpanel-field-2' + '-label', for: 'devicemanagementpanel-field-2' }"
            >
              <template #label>亮度 · {{ form.brightness }}%</template>
              <NSlider :disabled="disabled" v-model:value="form.brightness" :min="0" :max="100" />
            </ConsoleField>
          </div>
          <p v-if="form.brightness === 0" class="management-hint">亮度为 0 将熄屏，可通过 USB 或远程恢复。</p>
          <div class="management-checks">
            <NCheckbox class="checkbox-label" v-model:checked="form.muted" :disabled="disabled" :aria-disabled="disabled">语音静音（停止收听与播放）</NCheckbox>
            <NCheckbox class="checkbox-label" v-model:checked="form.dnd.manual" :disabled="disabled" :aria-disabled="disabled">手动免打扰</NCheckbox>
            <NCheckbox class="checkbox-label" v-model:checked="form.dnd.allowCritical" :disabled="disabled" :aria-disabled="disabled">免打扰时允许紧急提醒</NCheckbox>
          </div>
          <ConsoleDisclosure label="更多设置" class="management-options" :level="3"
            :summary="`${form.timezone} · ${form.message.queueDepth} 条 · ${durationSummary}${form.dnd.enabled ? ' · 定时免打扰' : ''}`"
            :force-open="!!uncertain || !!submitError || staleDraft">
          <NCheckbox class="checkbox-label" v-model:checked="form.dnd.enabled" :disabled="disabled" :aria-disabled="disabled">定时免打扰</NCheckbox>
          <div v-if="form.dnd.enabled" class="form-columns">
            <ConsoleField
              path="field-3"
              :rule="fieldRule(() => timeDraft.startMinute, { required: true, disabled: () => disabled })"
              :label-props="{ id: 'devicemanagementpanel-field-3' + '-label', for: 'devicemanagementpanel-field-3' }"
            >
              <template #label>开始时间</template>
              <NTimePicker
                @keydown.esc.stop
                :disabled="disabled"
                :formatted-value="timeDraft.startMinute || null"
                @update:formatted-value="setTime('startMinute', $event)"
                format="HH:mm"
                value-format="HH:mm"
              />
            </ConsoleField>
            <ConsoleField
              path="field-4"
              :rule="fieldRule(() => timeDraft.endMinute, { required: true, disabled: () => disabled })"
              :label-props="{ id: 'devicemanagementpanel-field-4' + '-label', for: 'devicemanagementpanel-field-4' }"
            >
              <template #label>结束时间</template>
              <NTimePicker
                @keydown.esc.stop
                :disabled="disabled"
                :formatted-value="timeDraft.endMinute || null"
                @update:formatted-value="setTime('endMinute', $event)"
                format="HH:mm"
                value-format="HH:mm"
              />
            </ConsoleField>
          </div>
          <p v-if="form.dnd.enabled && form.dnd.startMinute === form.dnd.endMinute" class="management-hint">
            开始与结束时间相同，将全天免打扰。
          </p>
          <div class="form-columns">
            <ConsoleField
              path="field-5"
              :rule="
                fieldRule(() => form?.timezone, {
                  required: true,
                  pattern: '(Asia/Shanghai|UTC|Etc/UTC|UTC[+\\-][0-9]{2}:[0-9]{2})',
                  maxlength: 32,
                  disabled: () => disabled,
                })
              "
              :label-props="{ id: 'devicemanagementpanel-field-5' + '-label', for: 'devicemanagementpanel-field-5' }"
            >
              <template #label>时区</template>
              <NAutoComplete
                @keydown.esc.stop
                :options="['Asia/Shanghai', 'UTC', 'Etc/UTC']"
                :disabled="disabled"
                v-model:value="form.timezone"
                :maxlength="32"
                :input-props="{
                  required: true,
                  pattern: '(Asia/Shanghai|UTC|Etc/UTC|UTC[+\\-][0-9]{2}:[0-9]{2})',
                  id: 'devicemanagementpanel-field-5',
                  'aria-label': '时区',
                }"
              />
              <small>固定时区，不自动切换夏令时。</small>
            </ConsoleField>
            <ConsoleField
              path="field-6"
              :rule="
                fieldRule(() => form?.message.queueDepth, {
                  required: true,
                  min: 1,
                  max: 8,
                  step: '1',
                  number: true,
                  disabled: () => disabled,
                })
              "
              :label-props="{ id: 'devicemanagementpanel-field-6' + '-label', for: 'devicemanagementpanel-field-6' }"
            >
              <template #label>消息容量</template>
              <NInputNumber
                :disabled="disabled"
                v-model:value="form.message.queueDepth"
                :min="1"
                :max="8"
                :step="1"
                :input-props="{ required: true, id: 'devicemanagementpanel-field-6', 'aria-label': '消息容量' }"
              />
            </ConsoleField>
          </div>
          <div class="form-columns">
            <ConsoleField
              path="field-7"
              :rule="fieldRule(() => duration, { required: true, min: 5, max: 30, step: 'any', number: true, disabled: () => disabled })"
              :label-props="{ id: 'devicemanagementpanel-field-7' + '-label', for: 'devicemanagementpanel-field-7' }"
            >
              <template #label>消息显示时长上限（秒）</template>
              <NInputNumber
                :disabled="disabled"
                :value="duration"
                @update:value="setDuration"
                :min="5"
                :max="30"
                :input-props="{ required: true, id: 'devicemanagementpanel-field-7', 'aria-label': '消息显示时长上限（秒）' }"
              />
            </ConsoleField>
          </div>
          <p v-if="data.telemetry?.config && form.message.queueDepth < data.telemetry.config.message.queueDepth" class="management-hint">
            缩小容量不会删除已接收消息。
          </p>
          </ConsoleDisclosure>
        </div>
        <div class="form-footer">
          <span v-if="dirty && !uncertain">有未提交的修改</span>
          <span v-else-if="!dirty">设置来自设备回读</span>
          <NButton type="primary" attr-type="button" v-if="uncertain" class="primary" :disabled="sending || busy" @click="submit">
            {{ sending ? '正在核对…' : '重试同一请求' }}
          </NButton>
          <NButton type="primary" attr-type="submit" v-else class="primary" :disabled="disabled || !dirty">
            {{ sending ? '正在提交…' : '保存到设备' }}
          </NButton>
        </div>
      </ConsoleForm>
      <ConsoleDisclosure ref="historySection" label="状态历史" summary="来自设备已上报的遥测" class="telemetry-history" aria-label="遥测历史" :level="3" :force-open="!!historyError">
        <div class="history-heading">
          <div class="history-range" role="group" aria-label="历史时间范围">
            <NButton
              v-for="item in ranges"
              :key="item.id"
              attr-type="button"
              :type="range === item.id ? 'primary' : 'default'"
              @click="pickRange(item.id)"
            >
              {{ item.label }}
            </NButton>
          </div>
        </div>
        <div class="history-metrics" role="group" aria-label="历史指标">
          <NButton
            v-for="item in metrics"
            :key="item.id"
            attr-type="button"
            :type="metric === item.id ? 'primary' : 'default'"
            @click="metric = item.id"
          >
            {{ item.label }}
          </NButton>
          <NButton
            attr-type="button"
            class="history-export"
            :disabled="!history?.points.length"
            @click="exportHistory"
          >
            导出 CSV
          </NButton>
        </div>
        <ConsoleState v-if="historyError" kind="error" compact>{{ historyError }}</ConsoleState>
        <ConsoleState v-else-if="historyLoading && !history" kind="loading" compact>正在读取历史…</ConsoleState>
        <template v-else-if="history">
          <ConsoleState v-if="!history.points.length" compact>所选范围内没有采样点。</ConsoleState>
          <template v-else>
            <TelemetryChart
              :points="history.points"
              :from="history.from"
              :to="history.to"
              :bucket-seconds="currentRange.bucketSeconds"
              :metric="metric"
            />
            <p class="management-hint">
              {{ currentRange.label }} · {{ bucketLabels[history.bucket] }} · 最后一条 {{ formatTime(lastPoint?.observedAt) }}
              <template v-if="history.truncated">· 仅显示最新 2000 个点</template>
              <br />
              历史按设备上报时间排序，缺失区间留空；最后一条不代表设备在线。保留 {{ history.retentionDays }} 天。
            </p>
          </template>
        </template>
      </ConsoleDisclosure>
      <ConsoleDisclosure v-if="disconnectVisible" label="最近断线" class="disconnect-history" :level="3"
        :summary="disconnects?.items.length ? `${disconnects.items.length} 条` : '来自设备已上报的遥测'"
        :force-open="!!disconnectsError">
        <ConsoleState v-if="disconnectsError" kind="error" compact>{{ disconnectsError }}</ConsoleState>
        <ConsoleState v-else-if="disconnectsLoading && !disconnects" kind="loading" compact>正在读取断线记录…</ConsoleState>
        <ConsoleState v-else-if="!disconnects?.items.length" compact>历史样本中没有断线记录。</ConsoleState>
        <template v-else>
          <div v-for="item in disconnects.items" :key="item.observedAt + item.reason" class="management-record">
            <div class="record-heading">
              <span>{{ formatTime(item.disconnectedAt || item.observedAt) }}</span>
              <span class="badge">{{ wifiDisconnectName(item.reason) }}</span>
            </div>
            <p class="management-hint">
              {{ item.disconnectedAt ? `记录于 ${formatTime(item.observedAt)}` : '设备当时尚未对时，按上报时刻排序' }}
            </p>
          </div>
          <p class="management-hint">保留 {{ disconnects.retentionDays }} 天，最多 {{ disconnects.limit }} 条。</p>
        </template>
      </ConsoleDisclosure>
      <ConsoleDisclosure v-if="data.configRequests.length" label="最近配置记录" class="management-history" :level="3"
        :summary="`${data.configRequests.length} 条`" :force-open="data.configRequests.some(item => item.status !== 'applied')">
        <div v-for="item in data.configRequests" :key="item.requestId" class="management-record">
          <div class="record-heading">
            <span>{{ formatTime(item.createdAt) }}</span>
            <span class="badge" :class="item.status === 'applied' ? 'success' : ''">{{ stateNames[item.status] }}</span>
          </div>
          <p>
            音量 {{ item.requestedConfig.volume }}% · 亮度 {{ item.requestedConfig.brightness }}%
            <span v-if="item.confirmationSource">· {{ item.confirmationSource === 'ack' ? '设备回执确认' : '配置回读确认' }}</span>
          </p>
          <p v-if="item.lastError" class="management-hint">
            {{ errorNames[item.lastError] || '设备未能确认此次设置，请核对状态。' }}
            <span v-if="item.deviceCode !== null">（{{ item.deviceCode }}）</span>
          </p>
          <NButton attr-type="button" class="text-button" :disabled="auditLoading" @click="showAudit(item.requestId)">
            {{ auditId === item.requestId ? '收起操作记录' : '查看操作记录' }}
          </NButton>
          <template v-if="auditId === item.requestId">
            <ConsoleState v-if="auditLoading" kind="loading" compact>正在读取…</ConsoleState>
            <ConsoleState v-else-if="auditError" kind="error" compact>{{ auditError }}</ConsoleState>
            <ConsoleState v-else-if="!audit.length" compact>尚无操作记录。</ConsoleState>
            <ol v-else class="audit-list">
              <li v-for="entry in audit" :key="entry.sequence">
                {{ formatTime(entry.createdAt) }} · {{ eventNames[entry.event] || '状态更新' }}
                <span v-if="errorNames[entry.detail]">· {{ errorNames[entry.detail] }}</span>
              </li>
            </ol>
          </template>
        </div>
      </ConsoleDisclosure>
    </template>
    <DeviceCredentialsSection :device-id="device.deviceId" :api="api" :busy="busy" />
  </section>
</template>
