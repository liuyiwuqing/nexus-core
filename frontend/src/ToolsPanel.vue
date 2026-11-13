<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { errorNames, formatTime, type Api } from './types';

interface Tool { id: string; title: string; risk: string; argument: string }
interface Capability { id: string; available: boolean; projectIds?: string[]; applicationIds?: string[] }
interface Catalog { configured: boolean; state: string; tools: Tool[]; bridge?: { tools: Capability[] } }
interface Job { jobId: string; toolId: string; state: string; risk: string; arguments: Record<string, string>; argumentsHash: string;
  result: Record<string, unknown> | null; lastError: string | null; createdAt: string; expiresAt: string }
interface Audit { sequence: number; event: string; detail: string; createdAt: string }
const props = defineProps<{ api: Api; busy: boolean; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean> }>();
const catalog = ref<Catalog | null>(null), jobs = ref<Job[]>([]), error = ref(''), loading = ref(false);
const toolId = ref('system.info'), argument = ref(''), auditId = ref(''), audit = ref<Audit[]>([]);
const selected = computed(() => catalog.value?.tools.find(tool => tool.id === toolId.value));
const capability = computed(() => catalog.value?.bridge?.tools.find(tool => tool.id === toolId.value));
const choices = computed(() => capability.value?.projectIds || capability.value?.applicationIds || []);
const available = computed(() => catalog.value?.state === 'ready' && capability.value?.available);
const names: Record<string,string> = { awaiting_confirmation: '等待你确认', queued: '等待执行', executing: '正在执行', succeeded: '已获得结果', failed: '执行失败', unknown: '结果未知', cancelled: '已取消', expired: '已过期' };
const events: Record<string,string> = { created: '已创建', confirmed: '用户已确认', executing: '开始执行', succeeded: '获得结果', failed: '执行失败', unknown: '结果未知', cancelled: '已取消', expired: '请求过期' };
const resultNames: Record<string,string> = { system: '操作系统', release: '系统版本', machine: '架构', cpuCount: 'CPU 逻辑核心', projectId: '项目', branch: '分支', changedFiles: '已跟踪文件变更数', applicationId: '应用', launchRequested: '已请求系统打开' };
let generation = 0, controller: AbortController | undefined;
async function load() {
  controller?.abort(); controller = new AbortController(); const request = ++generation; loading.value = true;
  try {
    const [tools, records] = await Promise.all([props.api<Catalog>('tools', 'GET', undefined, controller.signal), props.api<{ items: Job[] }>('tool-jobs', 'GET', undefined, controller.signal)]);
    if (request === generation) { catalog.value = tools; jobs.value = records.items; error.value = ''; }
  } catch (failure) { if (request === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) error.value = failure instanceof Error ? failure.message : '读取工具状态失败。'; }
  finally { if (request === generation) loading.value = false; }
}
async function prepare() {
  if (!selected.value) return;
  const body = { requestKey: crypto.randomUUID(), toolId: toolId.value, arguments: selected.value.argument ? { [selected.value.argument]: argument.value } : {} };
  if (await props.mutate(() => props.api('tool-jobs', 'POST', body), selected.value.risk === 'confirmation' ? '请求已创建，请核对后确认。' : '查询已加入队列。')) await load();
}
async function action(job: Job, kind: 'confirm' | 'cancel' | 'reconcile') {
  if (await props.mutate(() => props.api(`tool-jobs/${job.jobId}/${kind}`, 'POST', kind === 'confirm' ? { argumentsHash: job.argumentsHash } : undefined), kind === 'confirm' ? '已确认执行。' : kind === 'cancel' ? '请求已取消。' : '已核对执行结果。')) await load();
}
async function history(job: Job) {
  if (auditId.value === job.jobId) { auditId.value = ''; return; }
  const id = job.jobId; auditId.value = id; audit.value = [];
  try { const result = await props.api<{ items: Audit[] }>(`tool-jobs/${id}/audit`); if (auditId.value === id) audit.value = result.items; }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '读取审计失败。'; }
}
watch(toolId, () => { argument.value = ''; });
void load();
const timer = setInterval(() => { if (!loading.value && !props.busy && document.visibilityState === 'visible') void load(); }, 5000);
onUnmounted(() => { generation++; controller?.abort(); clearInterval(timer); });
</script>

<template>
  <section>
    <div class="section-heading"><div><h1>Mac 工具</h1><p>{{ catalog?.state === 'ready' ? 'Bridge 已连接' : catalog?.configured ? 'Bridge 暂不可用' : 'Bridge 未启用' }}</p></div><button class="text-button" :disabled="loading" @click="load">刷新</button></div>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <form class="inline-form" @submit.prevent="prepare">
      <div class="form-columns"><label>工具<select v-model="toolId" :disabled="busy"><option v-for="tool in catalog?.tools || []" :key="tool.id" :value="tool.id">{{ tool.title }}{{ tool.risk === 'confirmation' ? ' · 需确认' : '' }}</option></select></label>
        <label v-if="selected?.argument">{{ selected.argument === 'projectId' ? '项目白名单' : '应用白名单' }}<select v-model="argument" required :disabled="busy"><option value="" disabled>请选择</option><option v-for="choice in choices" :key="choice">{{ choice }}</option></select></label></div>
      <div class="form-footer"><span>{{ !catalog?.configured ? '先完成本机 Bridge 配置。' : !available ? '此能力未加入 Bridge 白名单或尚不可用。' : selected?.risk === 'confirmation' ? '创建请求后核对目标，再确认打开应用。' : '仅查询，不修改项目文件。' }}</span><button class="primary" :disabled="busy || !available || !!selected?.argument && !argument">{{ selected?.risk === 'confirmation' ? '创建打开请求' : '查询' }}</button></div>
    </form>
    <div v-if="!jobs.length" class="empty-state"><h2>暂无工具请求</h2><p>执行状态和确认记录会保留在这里。</p></div>
    <div class="record-list">
      <article v-for="job in jobs" :key="job.jobId" class="record-card">
        <div class="record-heading"><h2>{{ catalog?.tools.find(tool => tool.id === job.toolId)?.title || job.toolId }}</h2><span class="badge" :class="job.state === 'succeeded' ? 'success' : ['failed','unknown'].includes(job.state) ? 'danger' : ''">{{ names[job.state] }}</span></div>
        <p v-if="Object.keys(job.arguments).length" class="record-target">{{ Object.values(job.arguments).join(' · ') }}</p>
        <small>{{ formatTime(job.createdAt) }}<template v-if="job.state === 'awaiting_confirmation'"> · 确认期限 {{ formatTime(job.expiresAt) }}</template></small>
        <p v-if="job.lastError" class="inline-error">{{ errorNames[job.lastError] || '执行未完成，请检查 Bridge 配置与白名单。' }}</p>
        <p v-if="job.state === 'unknown'" class="scope-note">先核对原请求结果，避免重复执行。</p>
        <dl v-if="job.result && Object.keys(job.result).length" class="tool-result"><div v-for="(value,key) in job.result" :key="key"><dt>{{ resultNames[key] || key }}</dt><dd>{{ typeof value === 'boolean' ? value ? '是' : '否' : value }}</dd></div></dl>
        <div class="record-actions">
          <button v-if="job.state === 'awaiting_confirmation'" class="primary compact" :disabled="busy" @click="action(job,'confirm')">确认打开 {{ job.arguments.applicationId }}</button>
          <button v-if="['queued','awaiting_confirmation'].includes(job.state)" class="secondary compact" :disabled="busy" @click="action(job,'cancel')">取消</button>
          <button v-if="job.state === 'unknown'" class="secondary compact" :disabled="busy" @click="action(job,'reconcile')">核对原请求结果</button>
          <button class="text-button" @click="history(job)">{{ auditId === job.jobId ? '收起记录' : '查看记录' }}</button>
        </div>
        <ol v-if="auditId === job.jobId" class="audit-list"><li v-for="row in audit" :key="row.sequence">{{ formatTime(row.createdAt) }} · {{ events[row.event] || row.event }}<span v-if="row.detail"> · {{ errorNames[row.detail] || row.detail }}</span></li></ol>
      </article>
    </div>
  </section>
</template>
