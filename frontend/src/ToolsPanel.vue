<script setup lang="ts">
import { NButton } from 'naive-ui';
import { computed, onUnmounted, ref, watch } from 'vue';
import { errorNames, formatTime, type Api, type ToolSubmission } from './types';

import { readFailure } from './failure';
import StatusNote from './StatusNote.vue';
interface Tool { id: string; title: string; risk: string; argument: string }
interface Capability { id: string; available: boolean; projectIds?: string[]; applicationIds?: string[] }
interface Catalog { configured: boolean; state: string; tools: Tool[]; bridge?: { tools: Capability[] } }
interface Job { jobId: string; toolId: string; state: string; risk: string; arguments: Record<string, string>; argumentsHash: string;
  result: Record<string, unknown> | null; lastError: string | null; createdAt: string; expiresAt: string }
interface Audit { sequence: number; event: string; detail: string; createdAt: string }
const props = defineProps<{ api: Api; busy: boolean; pending: ToolSubmission | null; uncertain: boolean; create: (toolId: string, args: Record<string,string>) => Promise<boolean>; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean> }>();
const catalog = ref<Catalog | null>(null), jobs = ref<Job[]>([]), error = ref(''), loading = ref(false);
const toolId = ref(props.pending?.toolId || 'system.info'), argument = ref(Object.values(props.pending?.arguments || {})[0] || ''), auditId = ref(''), audit = ref<Audit[]>([]);
const auditLoading = ref(false), auditError = ref('');
const selected = computed(() => catalog.value?.tools.find(tool => tool.id === toolId.value));
const capability = computed(() => catalog.value?.bridge?.tools.find(tool => tool.id === toolId.value));
const choices = computed(() => capability.value?.projectIds || capability.value?.applicationIds || []);
const available = computed(() => catalog.value?.state === 'ready' && capability.value?.available);
const names: Record<string,string> = { awaiting_confirmation: '等待你确认', queued: '等待执行', executing: '正在执行', succeeded: '已获得结果', failed: '执行失败', unknown: '结果未知', cancelled: '已取消', expired: '已过期' };
const events: Record<string,string> = { created: '已创建', confirmed: '用户已确认', executing: '开始执行', succeeded: '获得结果', failed: '执行失败', unknown: '结果未知', cancelled: '已取消', expired: '请求过期' };
const resultNames: Record<string,string> = { system: '操作系统', release: '系统版本', machine: '架构', cpuCount: 'CPU 逻辑核心', projectId: '项目', branch: '分支', changedFiles: '已跟踪文件变更数', applicationId: '应用', launchRequested: '已请求系统打开' };
let alive = true, generation = 0, auditGeneration = 0, controller: AbortController | undefined, auditController: AbortController | undefined;
async function load(keepVisible = false) {
  controller?.abort(); controller = new AbortController(); const request = ++generation; loading.value = true; error.value = '';
  if (!keepVisible) { catalog.value = null; jobs.value = []; }
  try {
    const [tools, records] = await Promise.all([props.api<Catalog>('tools', 'GET', undefined, controller.signal), props.api<{ items: Job[] }>('tool-jobs', 'GET', undefined, controller.signal)]);
    if (request === generation) { catalog.value = tools; jobs.value = records.items; error.value = ''; }
  } catch (failure) { if (request === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) { catalog.value = null; jobs.value = []; error.value = readFailure(failure, '读取工具状态失败。'); } }
  finally { if (request === generation) loading.value = false; }
}
async function prepare() {
  if (loading.value || error.value || props.busy || !selected.value || (!props.pending && !available.value)) return;
  const args: Record<string,string> = selected.value.argument ? { [selected.value.argument]: argument.value } : {};
  if (await props.create(toolId.value,args) && alive) await load();
}
async function action(job: Job, kind: 'confirm' | 'cancel' | 'reconcile') {
  if (loading.value || error.value || props.busy) return;
  await props.mutate(() => props.api(`tool-jobs/${job.jobId}/${kind}`, 'POST', kind === 'confirm' ? { argumentsHash: job.argumentsHash } : undefined), kind === 'confirm' ? '已确认执行。' : kind === 'cancel' ? '请求已取消。' : '已核对执行结果。');
  if (alive) await load();
}
async function history(job: Job) {
  auditController?.abort(); const request = ++auditGeneration;
  audit.value = []; auditError.value = ''; auditLoading.value = false;
  if (auditId.value === job.jobId) { auditId.value = ''; return; }
  const id = job.jobId; auditId.value = id; auditLoading.value = true; auditController = new AbortController();
  try { const result = await props.api<{ items: Audit[] }>(`tool-jobs/${id}/audit`, 'GET', undefined, auditController.signal); if (alive && request === auditGeneration) audit.value = result.items; }
  catch (failure) { if (alive && request === auditGeneration && !(failure instanceof DOMException && failure.name === 'AbortError')) auditError.value = readFailure(failure, '读取审计失败。'); }
  finally { if (alive && request === auditGeneration) auditLoading.value = false; }
}
watch(toolId, () => { argument.value = ''; });
void load();
const timer = setInterval(() => { if (!loading.value && !props.busy && document.visibilityState === 'visible') void load(true); }, 5000);
onUnmounted(() => { alive = false; generation++; auditGeneration++; controller?.abort(); auditController?.abort(); clearInterval(timer); });
</script>

<template>
  <section>
    <div class="section-heading"><div><h1 tabindex="-1">Mac 工具</h1><p>{{ error ? 'Bridge 状态未知' : !catalog ? '正在读取 Bridge 状态' : catalog.state === 'ready' ? 'Bridge 已连接' : catalog.configured ? 'Bridge 暂不可用' : 'Bridge 未启用' }}</p></div><NButton quaternary attr-type="button" class="text-button" :disabled="loading || busy" @click="load()">刷新</NButton></div>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <p v-if="loading" class="muted" role="status" aria-busy="true">正在读取工具状态…</p>
    <StatusNote v-if="uncertain" tone="warn">工具请求结果尚未确认，重试将核对同一请求和参数。刷新或关闭页面后请先核对请求列表。</StatusNote>
    <form v-if="catalog && !error" class="inline-form" @submit.prevent="prepare">
      <div class="form-columns"><label>工具<select v-model="toolId" :disabled="busy || loading || !!pending"><option v-for="tool in catalog?.tools || []" :key="tool.id" :value="tool.id">{{ tool.title }}{{ tool.risk === 'confirmation' ? ' · 需确认' : '' }}</option></select></label>
        <label v-if="selected?.argument">{{ selected.argument === 'projectId' ? '项目白名单' : '应用白名单' }}<select v-model="argument" required :disabled="busy || loading || !!pending"><option value="" disabled>请选择</option><option v-if="pending && argument && !choices.includes(argument)" :value="argument">{{ argument }}</option><option v-for="choice in choices" :key="choice">{{ choice }}</option></select></label></div>
      <div class="form-footer"><span>{{ !catalog?.configured ? '先完成本机 Bridge 配置。' : !available ? '此能力未加入 Bridge 白名单或尚不可用。' : selected?.risk === 'confirmation' ? '创建请求后核对目标，再确认打开应用。' : '仅查询，不修改项目文件。' }}</span><NButton type="primary" attr-type="submit" class="primary" :disabled="busy || loading || !pending && (!available || !!selected?.argument && !argument)">{{ uncertain ? '重试同一请求' : selected?.risk === 'confirmation' ? '创建打开请求' : '查询' }}</NButton></div>
    </form>
    <div v-if="!jobs.length && !loading && !error" class="empty-state"><h2>暂无工具请求</h2><p>执行状态和确认记录会保留在这里。</p></div>
    <div class="record-list">
      <article v-for="job in jobs" :key="job.jobId" class="record-card">
        <div class="record-heading"><h2>{{ catalog?.tools.find(tool => tool.id === job.toolId)?.title || job.toolId }}</h2><span class="badge" :class="job.state === 'succeeded' ? 'success' : ['failed','unknown'].includes(job.state) ? 'danger' : ''">{{ names[job.state] }}</span></div>
        <p v-if="Object.keys(job.arguments).length" class="record-target">{{ Object.values(job.arguments).join(' · ') }}</p>
        <small>{{ formatTime(job.createdAt) }}<template v-if="job.state === 'awaiting_confirmation'"> · 确认期限 {{ formatTime(job.expiresAt) }}</template></small>
        <p v-if="job.lastError" class="inline-error">{{ errorNames[job.lastError] || '执行未完成，请检查 Bridge 配置与白名单。' }}</p>
        <p v-if="job.state === 'unknown'" class="scope-note">先核对原请求结果，避免重复执行。</p>
        <dl v-if="job.result && Object.keys(job.result).length" class="tool-result"><div v-for="(value,key) in job.result" :key="key"><dt>{{ resultNames[key] || key }}</dt><dd>{{ typeof value === 'boolean' ? value ? '是' : '否' : value }}</dd></div></dl>
        <div class="record-actions">
          <NButton type="primary" size="small" attr-type="button" v-if="job.state === 'awaiting_confirmation'" class="primary compact" :disabled="busy || loading" @click="action(job,'confirm')">确认打开 {{ job.arguments.applicationId }}</NButton>
          <NButton size="small" attr-type="button" v-if="['queued','awaiting_confirmation'].includes(job.state)" class="secondary compact" :disabled="busy || loading" @click="action(job,'cancel')">取消</NButton>
          <NButton size="small" attr-type="button" v-if="job.state === 'unknown'" class="secondary compact" :disabled="busy || loading" @click="action(job,'reconcile')">核对原请求结果</NButton>
          <NButton quaternary attr-type="button" class="text-button" @click="history(job)">{{ auditId === job.jobId ? '收起记录' : '查看记录' }}</NButton>
        </div>
        <p v-if="auditId === job.jobId && auditLoading" class="muted" role="status">正在读取记录…</p>
        <p v-else-if="auditId === job.jobId && auditError" class="inline-error" role="alert">{{ auditError }}</p>
        <p v-else-if="auditId === job.jobId && !audit.length" class="muted">尚无审计记录。</p>
        <ol v-else-if="auditId === job.jobId" class="audit-list"><li v-for="row in audit" :key="row.sequence">{{ formatTime(row.createdAt) }} · {{ events[row.event] || row.event }}<span v-if="row.detail"> · {{ errorNames[row.detail] || row.detail }}</span></li></ol>
      </article>
    </div>
  </section>
</template>
