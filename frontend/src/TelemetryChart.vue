<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { formatTime, type TelemetryPoint } from './types';

const props = defineProps<{ points: TelemetryPoint[]; from: string; to: string; bucketSeconds: number }>();
const W = 640, H = 190, L = 48, R = 16, T = 12, B = 26, TIP_W = 214;
type Row = { at: number; heap: number; min: number; point: TelemetryPoint };

const rows = computed<Row[]>(() => props.points
  .map(point => ({ at: Date.parse(point.observedAt), heap: point.freeHeap / 1024, min: point.minFreeHeap / 1024, point }))
  .filter(row => Number.isFinite(row.at)));
const scale = computed(() => {
  if (!rows.value.length) return null;
  let low = Math.min(...rows.value.map(row => Math.min(row.heap, row.min)));
  let high = Math.max(...rows.value.map(row => Math.max(row.heap, row.min)));
  if (high - low < 2) { low -= 2; high += 2; }
  const pad = (high - low) * 0.08;
  return { low: low - pad, high: high + pad };
});
const domain = computed(() => {
  const from = Date.parse(props.from), to = Date.parse(props.to);
  if (Number.isFinite(from) && Number.isFinite(to) && to > from) return { from, to };
  const first = rows.value[0]?.at ?? 0, last = rows.value[rows.value.length - 1]?.at ?? first + 1;
  return { from: first, to: last > first ? last : first + 1 };
});
const x = (at: number) => L + (W - L - R) * Math.min(1, Math.max(0, (at - domain.value.from) / (domain.value.to - domain.value.from)));
const y = (value: number) => T + (H - T - B) * (1 - (value - scale.value!.low) / (scale.value!.high - scale.value!.low));
// 缺失桶保留为空：相邻点跨度超过 1.8 个桶就断开，不用直线替设备补数据。
const segments = computed(() => {
  const gap = props.bucketSeconds * 1000 * 1.8, list: Row[][] = [];
  let current: Row[] = [];
  for (const row of rows.value) {
    if (current.length && row.at - current[current.length - 1].at > gap) { list.push(current); current = []; }
    current.push(row);
  }
  if (current.length) list.push(current);
  return list;
});
function line(segment: Row[], key: 'heap' | 'min') {
  return segment.map((row, index) => `${index ? 'L' : 'M'}${x(row.at).toFixed(1)},${y(row[key]).toFixed(1)}`).join(' ');
}
const ticks = computed(() => {
  const value = scale.value;
  if (!value) return [];
  return [value.high, (value.high + value.low) / 2, value.low].map(at => ({ value: Math.round(at), y: y(at) }));
});
const timeLabels = computed(() => {
  const format = (at: number) => new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(at));
  const middle = (domain.value.from + domain.value.to) / 2;
  return [
    { x: L, text: format(domain.value.from), anchor: 'start' },
    { x: (L + W - R) / 2, text: format(middle), anchor: 'middle' },
    { x: W - R, text: format(domain.value.to), anchor: 'end' },
  ];
});
const hovered = ref<number | null>(null);
const selected = computed(() => (hovered.value === null ? null : rows.value[hovered.value] ?? null));
const tooltipX = computed(() => Math.min(W - TIP_W - 6, Math.max(6, x(selected.value!.at) - TIP_W / 2)));
function move(event: MouseEvent) {
  if (!rows.value.length) return;
  const rect = (event.currentTarget as SVGSVGElement).getBoundingClientRect();
  const ratio = Math.min(1, Math.max(0, ((event.clientX - rect.left) * (W / rect.width) - L) / (W - L - R)));
  const at = domain.value.from + ratio * (domain.value.to - domain.value.from);
  let best = 0, distance = Infinity;
  rows.value.forEach((row, index) => {
    const gap = Math.abs(row.at - at);
    if (gap < distance) { distance = gap; best = index; }
  });
  hovered.value = best;
}
function step(delta: number) {
  if (!rows.value.length) return;
  const base = hovered.value ?? rows.value.length - 1;
  hovered.value = Math.min(rows.value.length - 1, Math.max(0, base + delta));
}
watch(() => props.points, () => { hovered.value = null; });
const summary = computed(() => {
  if (!rows.value.length) return '所选范围内没有遥测采样点。';
  const last = rows.value[rows.value.length - 1];
  const low = Math.min(...rows.value.map(row => row.min));
  return `可用内存趋势，共 ${rows.value.length} 组采样。最后可用 ${Math.round(last.heap)} KB，期间启动以来最小 ${Math.round(low)} KB。`;
});
</script>

<template>
  <div class="chart-wrap">
    <div class="chart-legend" aria-hidden="true"><span><i class="swatch-heap"></i>可用内存</span><span><i class="swatch-min"></i>启动以来最小</span><span class="chart-unit">KB</span></div>
    <div class="chart-scroll">
      <svg class="telemetry-chart" :viewBox="`0 0 ${W} ${H}`" role="img" :aria-label="summary" tabindex="0"
        @mousemove="move" @mouseleave="hovered = null" @keydown.left.prevent="step(-1)" @keydown.right.prevent="step(1)">
        <g v-for="(tick, index) in ticks" :key="index" class="chart-grid">
          <line :x1="L" :x2="W - R" :y1="tick.y" :y2="tick.y" />
          <text :x="L - 8" :y="tick.y + 3.5" text-anchor="end">{{ tick.value }}</text>
        </g>
        <text v-for="label in timeLabels" :key="label.text" :x="label.x" :y="H - 6" :text-anchor="label.anchor" class="chart-axis">{{ label.text }}</text>
        <g v-for="(segment, index) in segments" :key="index">
          <path class="series-heap" :d="line(segment, 'heap')" />
          <path class="series-min" :d="line(segment, 'min')" />
          <template v-if="segment.length === 1">
            <circle class="dot-heap" :cx="x(segment[0].at)" :cy="y(segment[0].heap)" r="3" />
            <circle class="dot-min" :cx="x(segment[0].at)" :cy="y(segment[0].min)" r="3" />
          </template>
        </g>
        <g v-if="selected">
          <line class="chart-cursor" :x1="x(selected.at)" :x2="x(selected.at)" :y1="T" :y2="H - B" />
          <circle class="dot-heap" :cx="x(selected.at)" :cy="y(selected.heap)" r="3.5" />
          <circle class="dot-min" :cx="x(selected.at)" :cy="y(selected.min)" r="3.5" />
          <g class="chart-tooltip" :transform="`translate(${tooltipX}, 6)`">
            <rect :width="TIP_W" height="36" rx="6" />
            <text x="10" y="15">{{ formatTime(selected.point.observedAt) }}</text>
            <text x="10" y="29">可用 {{ Math.round(selected.heap) }} KB · 最小 {{ Math.round(selected.min) }} KB</text>
          </g>
        </g>
      </svg>
    </div>
  </div>
</template>

<style scoped>
.chart-wrap { margin-top: var(--space-3); }
.chart-legend { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-4); color: var(--muted); font-size: var(--text-sm); }
.chart-legend span { display: inline-flex; align-items: center; gap: 6px; }
.chart-legend i { width: 14px; height: 2px; border-radius: 999px; }
.swatch-heap { background: var(--accent); }
.swatch-min { background: var(--info); }
.chart-unit { margin-left: auto; }
.chart-scroll { overflow-x: auto; margin-top: var(--space-2); }
.telemetry-chart { display: block; width: 100%; min-width: 560px; height: auto; overflow: visible; }
.telemetry-chart:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.chart-grid line { stroke: var(--divider); stroke-width: 1; }
.chart-grid text, .chart-axis, .chart-tooltip text { fill: var(--muted); font-size: 10px; font-variant-numeric: tabular-nums; }
.series-heap { fill: none; stroke: var(--accent); stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.series-min { fill: none; stroke: var(--info); stroke-width: 1.4; stroke-dasharray: 4 3; stroke-linecap: round; stroke-linejoin: round; }
.dot-heap { fill: var(--accent); }
.dot-min { fill: var(--info); }
.chart-cursor { stroke: var(--border-hover); stroke-width: 1; }
.chart-tooltip rect { fill: var(--surface); stroke: var(--border); }
.chart-tooltip text { fill: var(--ink-2); }
</style>
