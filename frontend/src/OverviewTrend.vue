<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { NButton } from 'naive-ui';
import ConsoleState from './ConsoleState.vue';
import { deliveryErrorNames, formatTime, type Api, type OverviewTrend, type OverviewTrendPoint } from './types';
import { readFailure } from './failure';

// 趋势与快照分开读取：快照每 5 秒轮询，趋势只在切换范围或点刷新时读，避免长窗口反复扫表。
const props = defineProps<{ api: Api }>();
const ranges = [
  { id: '6h', label: '6 小时', hours: 6, bucket: '15m' },
  { id: '24h', label: '24 小时', hours: 24, bucket: '1h' },
  { id: '7d', label: '7 天', hours: 168, bucket: '6h' },
] as const;
type RangeId = (typeof ranges)[number]['id'];
const bucketLabels: Record<string, string> = { '15m': '15 分钟', '1h': '1 小时', '6h': '6 小时', '1d': '1 天' };
const range = ref<RangeId>('24h');
const reason = ref('');
const current = computed(() => ranges.find(item => item.id === range.value)!);
const data = ref<OverviewTrend | null>(null), loading = ref(false), error = ref('');
let alive = true, generation = 0, control: AbortController | undefined;

async function load() {
  control?.abort();
  const ctl = new AbortController();
  control = ctl;
  const ticket = ++generation;
  loading.value = true;
  error.value = '';
  try {
    const query = new URLSearchParams({ hours: String(current.value.hours), bucket: current.value.bucket });
    if (reason.value) query.set('reason', reason.value);
    const value = await props.api<OverviewTrend>(
      `overview/trend?${query}`, 'GET', undefined, ctl.signal);
    if (alive && ticket === generation) data.value = value;
  } catch (failure) {
    if (alive && ticket === generation && !ctl.signal.aborted) {
      data.value = null;
      error.value = readFailure(failure, '趋势读取失败，请重试。');
    }
  } finally {
    if (alive && ticket === generation) loading.value = false;
  }
}

const points = computed(() => data.value?.points ?? []);
const empty = computed(() => !!data.value && data.value.totals.messages === 0
  && data.value.totals.deliveries === 0 && data.value.totals.failures === 0);
const maxMessages = computed(() => Math.max(1, ...points.value.map(point => point.messages)));
const maxFailures = computed(() => Math.max(1, ...points.value.map(point => point.failures)));
const WIDTH = 600, HEIGHT = 56;
function slot(index: number) { return index * (WIDTH / Math.max(1, points.value.length)); }
function barWidth() { return Math.max(1, (WIDTH / Math.max(1, points.value.length)) * 0.68); }
function barX(index: number) { return slot(index) + (WIDTH / Math.max(1, points.value.length)) * 0.16; }
function barY(value: number, max: number) { return HEIGHT - Math.round((value / max) * (HEIGHT - 6)); }
function pointLabel(point: OverviewTrendPoint) {
  return `${formatTime(point.at)} · 收件 ${point.messages} · 投递 ${point.deliveries} · 失败 ${point.failures}`;
}
function reasonName(code: string) { return deliveryErrorNames[code] ?? code; }
function pickReason(code: string) { reason.value = reason.value === code ? '' : code; }
function clearReason() { if (reason.value) reason.value = ''; }
const failureSummary = computed(() => {
  if (!data.value) return '';
  const base = `失败事件 ${data.value.totals.failures} 次`;
  return data.value.reason
    ? `${base}（仅${reasonName(data.value.reason)}；窗口共 ${data.value.totals.allFailures} 次）`
    : base;
});
const summary = computed(() => data.value
  ? `窗口内收件 ${data.value.totals.messages} 条 · 投递 ${data.value.totals.deliveries} 条 · ${failureSummary.value}`
  : '');

onMounted(load);
onUnmounted(() => { alive = false; control?.abort(); });
watch([range, reason], load);
</script>

<template>
  <article class="record-card overview-trend">
    <div class="record-heading">
      <h2>运行趋势</h2>
      <div class="overview-trend-ranges">
        <NButton v-for="item in ranges" :key="item.id" attr-type="button" class="text-button"
          :data-range="item.id" :aria-pressed="range === item.id" :disabled="loading && range === item.id"
          @click="range = item.id">{{ item.label }}</NButton>
        <NButton attr-type="button" class="text-button" :disabled="loading" @click="load">
          {{ loading ? '读取中…' : '刷新趋势' }}
        </NButton>
      </div>
    </div>
    <ConsoleState v-if="error" kind="error" compact>{{ error }}</ConsoleState>
    <ConsoleState v-else-if="loading && !data" kind="loading" compact>正在读取趋势…</ConsoleState>
    <ConsoleState v-else-if="empty" compact>该窗口内没有保留的收件或投递记录。</ConsoleState>
    <template v-else-if="data">
      <p class="overview-trend-summary">{{ summary }}</p>
      <figure class="overview-trend-figure">
        <figcaption>收件（条）</figcaption>
        <svg class="overview-trend-chart overview-trend-chart--messages" :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
          preserveAspectRatio="none" role="img" :aria-label="`收件趋势，${summary}`">
          <line class="overview-trend-axis" x1="0" :y1="HEIGHT" :x2="WIDTH" :y2="HEIGHT" />
          <rect v-for="(point, index) in points" :key="point.at" class="overview-trend-bar"
            :class="{ 'is-partial': point.partial }" :data-index="index"
            :x="barX(index)" :y="barY(point.messages, maxMessages)" :width="barWidth()"
            :height="HEIGHT - barY(point.messages, maxMessages)">
            <title>{{ pointLabel(point) }}</title>
          </rect>
        </svg>
        <figcaption>失败事件（次）<template v-if="data.reason"> · 仅 {{ reasonName(data.reason) }}</template></figcaption>
        <svg class="overview-trend-chart overview-trend-chart--failures" :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
          preserveAspectRatio="none" role="img" :aria-label="`失败事件趋势，${failureSummary}`">
          <line class="overview-trend-axis" x1="0" :y1="HEIGHT" :x2="WIDTH" :y2="HEIGHT" />
          <rect v-for="(point, index) in points" :key="point.at" class="overview-trend-bar overview-trend-bar--failure"
            :class="{ 'is-partial': point.partial, 'is-empty': point.failures === 0 }" :data-index="index"
            :x="barX(index)" :y="barY(point.failures, maxFailures)" :width="barWidth()"
            :height="HEIGHT - barY(point.failures, maxFailures)">
            <title>{{ pointLabel(point) }}</title>
          </rect>
        </svg>
        <div v-if="data.failureReasons.length || data.reason" class="overview-trend-reasons">
          <p v-if="data.failureReasons.length" class="overview-caption">
            窗口内失败事件按原因（合计 {{ data.totals.allFailures }} 次），点选可只查看该原因
          </p>
          <ul v-if="data.failureReasons.length">
            <li v-for="item in data.failureReasons" :key="item.code">
              <NButton attr-type="button" class="text-button" :data-trend-reason="item.code"
                :aria-pressed="data.reason === item.code" @click="pickReason(item.code)">
                {{ reasonName(item.code) }} <strong>{{ item.count }}</strong>
              </NButton>
            </li>
          </ul>
          <p v-if="data.reason" class="overview-caption">
            <NButton attr-type="button" class="text-button" @click="clearReason">全部原因</NButton>
            <span v-if="data.totals.failures === 0">该原因在窗口内没有失败事件。</span>
          </p>
        </div>
        <p class="overview-caption">
          每桶 {{ bucketLabels[data.bucket] ?? data.bucket }}，共 {{ data.points.length }} 桶；最后一桶尚未结束。
          失败按投递历史记录的进入 failed 事件计数，不是当前失败状态回看。
        </p>
      </figure>
      <p class="overview-caption">
        窗口 {{ formatTime(data.from) }} 起（本地时间）· 只覆盖仍保留的记录，清理后的旧记录不再计入
      </p>
    </template>
  </article>
</template>

<style scoped>
.overview-trend { margin-top: var(--space-4); }
.overview-trend h2 { font-size: var(--text-md); }
.overview-trend .overview-caption { font-size: var(--text-sm); color: var(--muted); }
.overview-trend-ranges { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); }
.overview-trend-ranges [aria-pressed='true'] { color: var(--ink); font-weight: 600; }
.overview-trend-summary { font-size: var(--text-base); }
.overview-trend-figure { margin: var(--space-2) 0 0; }
.overview-trend-figure figcaption { font-size: var(--text-sm); color: var(--muted); }
.overview-trend-chart { display: block; width: 100%; height: 56px; margin: 2px 0 var(--space-2); }
.overview-trend-axis { stroke: var(--divider); stroke-width: 1; vector-effect: non-scaling-stroke; }
.overview-trend-bar { fill: var(--info); }
.overview-trend-bar.is-partial { opacity: .55; }
.overview-trend-bar--failure { fill: var(--danger); }
.overview-trend-bar--failure.is-empty { fill: transparent; }
.overview-trend-reasons { margin: var(--space-1) 0 var(--space-2); }
.overview-trend-reasons ul { display: flex; flex-wrap: wrap; gap: var(--space-2);
  margin: var(--space-1) 0; padding: 0; list-style: none; }
.overview-trend-reasons li { display: flex; }
.overview-trend-reasons [aria-pressed='true'] { color: var(--ink); font-weight: 600; }
.overview-trend-reasons strong { margin-left: 4px; font-variant-numeric: tabular-nums; }
</style>
