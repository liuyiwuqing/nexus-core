<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { errorNames, formatTime, type Api } from './types';

interface Rule { ruleId: string; name: string; trigger: string; action: string; enabled: boolean; version: number; arguments: Record<string,string> }
interface Run { runId: string; ruleId: string; ruleVersion: number; name: string; state: string; attempts: number; lastError: string; createdAt: string; result: { outcome?: string } | null }
const props = defineProps<{ api: Api; busy: boolean; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean> }>();
const rules = ref<Rule[]>([]), runs = ref<Run[]>([]), error = ref(''), loading = ref(false), ai = ref(false);
const compose = ref(false), name = ref(''), kind = ref('comment'), time = ref('08:00'), zone = ref(Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai');
const title = ref(''), content = ref('');
const currentRules = computed(() => new Map(rules.value.map(rule => [rule.ruleId,rule])));
const states: Record<string,string> = { pending: '等待处理', executing: '正在入队', enqueued: '已加入队列', failed: '入队失败', cancelled: '已取消' };
let generation = 0, controller: AbortController | undefined;
async function load() {
  controller?.abort(); controller = new AbortController(); const request = ++generation; loading.value = true;
  try {
    const [list, history, model] = await Promise.all([props.api<{ items: Rule[] }>('automation/rules', 'GET', undefined, controller.signal), props.api<{ items: Run[] }>('automation/runs', 'GET', undefined, controller.signal), props.api<{ configured: boolean }>('ai', 'GET', undefined, controller.signal)]);
    if (request === generation) { rules.value = list.items; runs.value = history.items; ai.value = model.configured; error.value = ''; }
  } catch (failure) { if (request === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) error.value = failure instanceof Error ? failure.message : '读取自动化状态失败。'; }
  finally { if (request === generation) loading.value = false; }
}
async function create() {
  const body = kind.value === 'comment' ? { name: name.value, trigger: 'message.received', action: 'analyze_public_comment' }
    : { name: name.value, trigger: 'schedule.daily', action: 'notify_device', localTime: time.value, zone: zone.value, title: title.value, content: content.value };
  if (await props.mutate(() => props.api('automation/rules', 'POST', body), '规则已保存，启用后生效。')) { compose.value = false; name.value = ''; await load(); }
}
async function enable(rule: Rule) {
  if (await props.mutate(() => props.api(`automation/rules/${rule.ruleId}/enabled`, 'PUT', { enabled: !rule.enabled }), rule.enabled ? '已停用规则，并取消尚未开始的任务。' : '规则已启用。')) await load();
}
async function retry(run: Run) {
  if (await props.mutate(() => props.api(`automation/runs/${run.runId}/retry`, 'POST'), '已重新加入规则队列。')) await load();
}
void load();
const timer = setInterval(() => { if (!loading.value && !props.busy && document.visibilityState === 'visible') void load(); }, 5000);
onUnmounted(() => { generation++; controller?.abort(); clearInterval(timer); });
</script>

<template>
  <section>
    <div class="section-heading"><div><h1>自动化</h1><p>公开评论分析与每日设备提醒</p></div><button class="secondary" :aria-expanded="compose" @click="compose = !compose">{{ compose ? '收起' : '创建规则' }}</button></div>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <form v-if="compose" class="inline-form" @submit.prevent="create">
      <div class="form-columns"><label>名称<input v-model="name" maxlength="80" required :disabled="busy" /></label><label>规则<select v-model="kind" :disabled="busy"><option value="comment">Halo 新公开评论 → AI 分析</option><option value="daily">每日定时 → 设备提醒</option></select></label></div>
      <template v-if="kind === 'daily'"><div class="form-columns spaced"><label>时间<input v-model="time" type="time" required :disabled="busy" /></label><label>时区<input v-model="zone" required maxlength="100" :disabled="busy" /></label></div><label class="spaced">提醒标题<input v-model="title" maxlength="160" required :disabled="busy" /></label><label class="spaced">提醒内容<textarea v-model="content" maxlength="8192" rows="3" required :disabled="busy"></textarea></label></template>
      <div class="form-footer"><span>{{ kind === 'daily' ? '每天最多一次；启动后补发最近两小时内的当日提醒。新规则默认停用。' : '新规则默认停用。' }}</span><button class="primary" :disabled="busy">保存规则</button></div>
    </form>
    <div v-if="!rules.length && !loading" class="empty-state"><h2>暂无自动化规则</h2><p>规则启用后才处理新事件。</p></div>
    <div v-else class="record-list">
      <article v-for="rule in rules" :key="rule.ruleId" class="record-card"><div class="record-heading"><h2>{{ rule.name }}</h2><label class="checkbox-label"><input type="checkbox" :checked="rule.enabled" :disabled="busy || !rule.enabled && rule.action === 'analyze_public_comment' && !ai" @change="enable(rule)" />启用</label></div>
        <p class="muted">{{ rule.trigger === 'message.received' ? 'Halo 新公开评论 → AI 分析' : `每日 ${rule.arguments.localTime}（${rule.arguments.zone}）→ 设备提醒` }}</p>
        <p v-if="rule.trigger === 'schedule.daily'" class="record-target">{{ rule.arguments.title }}</p>
        <p v-if="rule.action === 'analyze_public_comment' && !ai" class="scope-note">AI 服务未配置。</p>
      </article>
    </div>
    <div v-if="runs.length" class="automation-history"><h2>最近执行</h2><div class="table-shell"><table><thead><tr><th>规则 / 时间</th><th>结果</th><th>操作</th></tr></thead><tbody><tr v-for="run in runs" :key="run.runId"><td><strong>{{ run.name }}</strong><span class="cell-sub">{{ formatTime(run.createdAt) }}</span></td><td><span class="badge" :class="run.state === 'enqueued' ? 'success' : run.state === 'failed' ? 'danger' : ''">{{ states[run.state] }}</span><span v-if="run.result" class="cell-sub">{{ run.result.outcome === 'analysis_queued' ? 'AI 分析队列' : '消息投递队列' }}</span><span v-if="run.lastError" class="cell-sub">{{ errorNames[run.lastError] || '处理未完成' }}</span></td><td><button v-if="run.state === 'failed' && currentRules.get(run.ruleId)?.enabled && currentRules.get(run.ruleId)?.version === run.ruleVersion" class="secondary compact" :disabled="busy" @click="retry(run)">重试</button><span v-else>—</span></td></tr></tbody></table></div></div>
    <p class="scope-note">停用会取消尚未开始的任务。设备确认、用户已读和 AI 分析结果可在消息及投递中核对。</p>
  </section>
</template>
