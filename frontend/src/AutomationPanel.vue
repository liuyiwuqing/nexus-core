<script setup lang="ts">
import { useAccess } from './access';
const { operate }=useAccess();
import { computed, nextTick, onUnmounted, ref } from 'vue';
import RuleEditor from './RuleEditor.vue';
import { errorNames, formatTime, type Api, type RuleSubmission, type AutomationRule, type RuleEditInput, type RuleEditSession, type RuleDeletion, type RulePreview } from './types';

interface Run { runId: string; ruleId: string; ruleVersion: number; name: string; state: string; attempts: number; lastError: string; createdAt: string; result: { outcome?: string } | null }
const props = defineProps<{ api: Api; busy: boolean; pending: RuleSubmission | null; uncertain: boolean; create: (input: Omit<RuleSubmission,'requestKey'>) => Promise<boolean>; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean>;
  editor: RuleEditSession | null; beginEdit: (rule: AutomationRule) => void; changeEdit: (input: RuleEditInput) => void; closeEdit: () => void; saveEdit: () => Promise<boolean>; checkEdit: (reload?: boolean) => Promise<boolean>;
  deletion: RuleDeletion | null; beginDelete: (rule: AutomationRule) => void; closeDelete: () => void; deleteRule: (checkOnly?: boolean) => Promise<boolean> }>();
const rules = ref<AutomationRule[]>([]), runs = ref<Run[]>([]), error = ref(''), loading = ref(false), ai = ref(false);
const compose = ref(!!props.pending), name = ref(props.pending?.name || ''), kind = ref(props.pending?.trigger === 'schedule.daily' ? 'daily' : 'comment'), time = ref(props.pending?.localTime || '08:00'), zone = ref(props.pending?.zone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai');
const title = ref(props.pending?.title || ''), content = ref(props.pending?.content || '');
const currentRules = computed(() => new Map(rules.value.map(rule => [rule.ruleId,rule])));
const deleteName = ref(''), preview = ref<RulePreview | null>(null), previewError = ref(''), previewLoading = ref(false);
const states: Record<string,string> = { pending: '等待处理', executing: '正在入队', enqueued: '已加入队列', failed: '入队失败', cancelled: '已取消' };
let alive = true, generation = 0, controller: AbortController | undefined;
async function load(keepVisible = false) {
  controller?.abort(); controller = new AbortController(); const request = ++generation; loading.value = true; error.value = '';
  if (!keepVisible) { rules.value = []; runs.value = []; ai.value = false; }
  try {
    const [list, history, model] = await Promise.all([props.api<{ items: AutomationRule[] }>('automation/rules', 'GET', undefined, controller.signal), props.api<{ items: Run[] }>('automation/runs', 'GET', undefined, controller.signal), props.api<{ configured: boolean }>('ai', 'GET', undefined, controller.signal)]);
    if (request === generation) { rules.value = list.items; runs.value = history.items; ai.value = model.configured; error.value = ''; }
  } catch (failure) { if (request === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) { rules.value = []; runs.value = []; ai.value = false; error.value = failure instanceof Error ? failure.message : '读取自动化状态失败。'; } }
  finally { if (request === generation) loading.value = false; }
}
async function save() {
  if (!operate.value || loading.value || error.value || props.busy) return;
  const body = kind.value === 'comment' ? { name: name.value, trigger: 'message.received', action: 'analyze_public_comment' }
    : { name: name.value, trigger: 'schedule.daily', action: 'notify_device', localTime: time.value, zone: zone.value, title: title.value, content: content.value };
  if (await props.create(body) && alive) { compose.value = false; name.value = ''; await load(); }
}
async function enable(rule: AutomationRule) {
  if (!operate.value || loading.value || error.value || props.busy) return;
  await props.mutate(() => props.api(`automation/rules/${rule.ruleId}/enabled`, 'PUT', { enabled: !rule.enabled, expectedVersion: rule.version }), rule.enabled ? '已停用规则，并取消尚未开始的任务。' : '规则已启用。');
  if (alive) await load();
}
async function retry(run: Run) {
  if (!operate.value || loading.value || error.value || props.busy) return;
  await props.mutate(() => props.api(`automation/runs/${run.runId}/retry`, 'POST'), '已重新加入规则队列。');
  if (alive) await load();
}
function edit(rule: AutomationRule) { if (!props.busy && !loading.value) { compose.value = false; props.beginEdit(rule); } }
async function editorAction(action: () => Promise<boolean>) {
  const id = props.editor?.rule.ruleId;
  await action();
  if (!alive) return;
  await load();
  if (!props.editor && id) { await nextTick(); document.getElementById(`edit-rule-${id}`)?.focus(); }
}
function closeEditor() {
  const id = props.editor?.rule.ruleId; props.closeEdit();
  void nextTick(() => { if (id) document.getElementById(`edit-rule-${id}`)?.focus(); });
}
function remove(rule: AutomationRule) { deleteName.value = ''; compose.value = false; props.beginDelete(rule); }
async function confirmDelete(checkOnly = false) {
  if (!props.deletion || props.busy || (!checkOnly && deleteName.value !== props.deletion.rule.name)) return;
  await props.deleteRule(checkOnly);
  if (alive) await load();
}
let previewController: AbortController | undefined;
function closePreview() { previewController?.abort(); preview.value = null; previewError.value = ''; previewLoading.value = false; }
async function test(rule: AutomationRule) {
  if (props.busy || loading.value || previewLoading.value) return;
  closePreview(); const control = new AbortController(); previewController = control; previewLoading.value = true;
  try {
    const result = await props.api<RulePreview>(`automation/rules/${rule.ruleId}/preview`, 'POST', { expectedVersion: rule.version }, control.signal);
    if (!result?.dryRun || result.rule?.ruleId !== rule.ruleId || result.rule.version !== rule.version) throw new Error('未能读取有效的预演结果。');
    if (alive && previewController === control) preview.value = result;
  } catch (failure) {
    if (alive && !control.signal.aborted) previewError.value = failure instanceof Error ? failure.message : '预演失败，请重新读取规则后重试。';
  } finally { if (alive && previewController === control) previewLoading.value = false; }
}
void load();
const timer = setInterval(() => { if (!loading.value && !props.busy && document.visibilityState === 'visible') void load(true); }, 5000);
onUnmounted(() => { alive = false; generation++; controller?.abort(); previewController?.abort(); clearInterval(timer); });
</script>

<template>
  <section>
    <div class="section-heading"><div><h1>自动化</h1><p>公开评论分析与每日设备提醒</p></div><div class="management-actions"><button class="text-button" :disabled="loading || busy" @click="load()">刷新</button><button class="secondary" :disabled="!operate || busy || loading || !!error || !!editor || !!deletion" :aria-expanded="compose" @click="compose = !compose">{{ compose ? '收起' : '创建规则' }}</button></div></div>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <p v-if="loading" class="muted" role="status" aria-busy="true">正在读取自动化状态…</p>
    <p v-if="uncertain" class="scope-note" role="status">规则保存结果尚未确认，重试保持同一规则和参数。刷新或关闭页面后请先核对规则列表。</p>
    <RuleEditor v-if="editor" :key="editor.rule.ruleId + ':' + editor.rule.version" :session="editor" :current="currentRules.get(editor.rule.ruleId)" :disabled="busy || loading" :failed="!!error" @change="changeEdit" @close="closeEditor" @save="editorAction(saveEdit)" @check="reload => editorAction(() => checkEdit(reload))" />
    <form v-if="deletion" class="inline-form rule-delete" aria-label="删除规则确认" @submit.prevent="confirmDelete()">
      <h2>删除“{{ deletion.rule.name }}”</h2><p>仅删除已停用且无在途任务的规则；执行历史和已生成消息保留。</p>
      <p v-if="deletion.uncertain" class="scope-note" role="status">删除结果尚未确认。可只读核对，或重试原版本删除。切换导航保留本次请求。</p>
      <label>输入规则名称确认<input v-model="deleteName" aria-label="确认删除的规则名称" :disabled="busy" autocomplete="off" /></label>
      <div class="form-footer"><button type="button" class="text-button" :disabled="busy" @click="closeDelete">关闭</button><div class="management-actions"><button v-if="deletion.uncertain" type="button" class="secondary" :disabled="busy" @click="confirmDelete(true)">核对删除结果</button><button class="primary" :disabled="busy || loading || !!error || deleteName !== deletion.rule.name">{{ deletion.uncertain ? '重试原版本删除' : '确认删除规则' }}</button></div></div>
    </form>
    <div v-if="previewLoading" class="scope-note" role="status">正在预演规则…</div>
    <p v-if="previewError" class="feedback error" role="alert">{{ previewError }} <button class="text-button" @click="closePreview">关闭</button></p>
    <section v-if="preview" class="inline-form rule-preview" aria-label="规则预演结果"><div class="record-heading"><h2>{{ preview.rule.name }} · 版本 {{ preview.rule.version }}</h2><button class="text-button" @click="closePreview">关闭预演</button></div><p>预演未执行动作，不创建消息、分析任务或投递。</p><p v-if="preview.rule.action === 'notify_device'">候选启用设备：{{ preview.candidateDevices }}。离线和免打扰由实际投递时的既有规则处理。</p><p v-else>仅分析符合公开性与授权检查的 Halo 新评论。</p><p v-if="preview.block" class="scope-note">{{ errorNames[preview.block] || '当前条件未满足。' }}</p><p v-if="!preview.rule.enabled" class="muted">规则当前停用，预演不会将其启用。</p></section>
    <form v-if="compose && !error" class="inline-form" @submit.prevent="save">
      <div class="form-columns"><label>名称<input v-model="name" maxlength="80" required :disabled="!operate || busy || loading || !!pending" /></label><label>规则<select v-model="kind" :disabled="!operate || busy || loading || !!pending"><option value="comment">Halo 新公开评论 → AI 分析</option><option value="daily">每日定时 → 设备提醒</option></select></label></div>
      <template v-if="kind === 'daily'"><div class="form-columns spaced"><label>时间<input v-model="time" type="time" required :disabled="!operate || busy || loading || !!pending" /></label><label>时区<input v-model="zone" required maxlength="100" :disabled="!operate || busy || loading || !!pending" /></label></div><label class="spaced">提醒标题<input v-model="title" maxlength="160" required :disabled="!operate || busy || loading || !!pending" /></label><label class="spaced">提醒内容<textarea v-model="content" maxlength="8192" rows="3" required :disabled="!operate || busy || loading || !!pending"></textarea></label></template>
      <div class="form-footer"><span>{{ kind === 'daily' ? '每天最多一次；启动后补发最近两小时内的当日提醒。新规则默认停用。' : '新规则默认停用。' }}</span><button class="primary" :disabled="busy || loading">{{ uncertain ? '重试保存同一规则' : '保存规则' }}</button></div>
    </form>
    <div v-if="!rules.length && !loading && !error" class="empty-state"><h2>暂无自动化规则</h2><p>规则启用后才处理新事件。</p></div>
    <div v-else class="record-list">
      <article v-for="rule in rules" :key="rule.ruleId" class="record-card"><div class="record-heading"><h2>{{ rule.name }}</h2><label class="checkbox-label"><input type="checkbox" :checked="rule.enabled" :disabled="!operate || busy || loading || !!editor || !!deletion || !rule.enabled && rule.action === 'analyze_public_comment' && !ai" @change="enable(rule)" />启用</label></div>
        <p class="muted">{{ rule.trigger === 'message.received' ? 'Halo 新公开评论 → AI 分析' : `每日 ${rule.arguments.localTime}（${rule.arguments.zone}）→ 设备提醒` }}</p>
        <p v-if="rule.trigger === 'schedule.daily'" class="record-target">{{ rule.arguments.title }}</p>
        <p v-if="rule.action === 'analyze_public_comment' && !ai" class="scope-note">AI 服务未配置。</p>
        <div class="management-actions spaced"><button :id="`edit-rule-${rule.ruleId}`" class="text-button" :disabled="!operate || busy || loading || !!pending || !!editor || !!deletion || !rule.canEdit" :title="errorNames[rule.editBlock] || '编辑已停用规则'" @click="edit(rule)">编辑</button><button class="text-button" :disabled="busy || loading || previewLoading || !!editor || !!deletion" @click="test(rule)">测试预演</button><button :id="`delete-rule-${rule.ruleId}`" class="text-button" :disabled="!operate || busy || loading || !!pending || !!editor || !!deletion || !rule.canEdit" @click="remove(rule)">删除</button><span v-if="rule.editBlock" class="muted">{{ errorNames[rule.editBlock] }}</span></div>
      </article>
    </div>
    <div v-if="runs.length" class="automation-history"><h2>最近执行</h2><div class="table-shell"><table><thead><tr><th>规则 / 时间</th><th>结果</th><th>操作</th></tr></thead><tbody><tr v-for="run in runs" :key="run.runId"><td><strong>{{ run.name }}</strong><span class="cell-sub">{{ formatTime(run.createdAt) }}</span></td><td><span class="badge" :class="run.state === 'enqueued' ? 'success' : run.state === 'failed' ? 'danger' : ''">{{ states[run.state] }}</span><span v-if="run.result" class="cell-sub">{{ run.result.outcome === 'analysis_queued' ? 'AI 分析队列' : '消息投递队列' }}</span><span v-if="run.lastError" class="cell-sub">{{ errorNames[run.lastError] || '处理未完成' }}</span></td><td><button v-if="run.state === 'failed' && currentRules.get(run.ruleId)?.enabled && currentRules.get(run.ruleId)?.version === run.ruleVersion" class="secondary compact" :disabled="busy || loading" @click="retry(run)">重试</button><span v-else>—</span></td></tr></tbody></table></div></div>
    <p class="scope-note">停用会取消尚未开始的任务。设备确认、用户已读和 AI 分析结果可在消息及投递中核对。</p>
  </section>
</template>
