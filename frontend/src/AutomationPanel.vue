<script setup lang="ts">
import ConsoleDisclosure from './ConsoleDisclosure.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NCheckbox, NInput, NSelect, NTimePicker } from 'naive-ui';
import { useAccess } from './access';
const { operate }=useAccess();
import { computed, nextTick, onUnmounted, ref } from 'vue';
import RuleEditor from './RuleEditor.vue';
import { errorNames, formatTime, type Api, type RuleSubmission, type AutomationRule, type RuleEditInput, type RuleEditSession, type RuleDeletion, type RulePreview } from './types';

import { readFailure } from './failure';
import StatusNote from './StatusNote.vue';
interface Run { runId: string; ruleId: string; ruleVersion: number; name: string; state: string; attempts: number; lastError: string; createdAt: string; result: { outcome?: string } | null }
const props = defineProps<{ api: Api; busy: boolean; pending: RuleSubmission | null; uncertain: boolean; create: (input: Omit<RuleSubmission,'requestKey'>) => Promise<boolean>; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean>;
  editor: RuleEditSession | null; beginEdit: (rule: AutomationRule) => void; changeEdit: (input: RuleEditInput) => void; closeEdit: () => void; saveEdit: () => Promise<boolean>; checkEdit: (reload?: boolean) => Promise<boolean>;
  deletion: RuleDeletion | null; beginDelete: (rule: AutomationRule) => void; closeDelete: () => void; deleteRule: (checkOnly?: boolean) => Promise<boolean> }>();
const rules = ref<AutomationRule[]>([]), runs = ref<Run[]>([]), error = ref(''), loading = ref(false), ai = ref(false);
const compose = ref(!!props.pending), name = ref(props.pending?.name || ''), kind = ref(props.pending?.trigger === 'schedule.daily' ? 'daily' : props.pending?.action==='notify_device'?'incoming':'comment'), time = ref(props.pending?.localTime || '08:00'), zone = ref(props.pending?.zone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai');
const title = ref(props.pending?.title || ''), content = ref(props.pending?.content || '');
const conditions=ref<Record<string,string>>({...props.pending?.conditions});
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
  } catch (failure) { if (request === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) { rules.value = []; runs.value = []; ai.value = false; error.value = readFailure(failure, '读取自动化状态失败。'); } }
  finally { if (request === generation) loading.value = false; }
}
async function save() {
  if (!operate.value || loading.value || error.value || props.busy) return;
  const body = kind.value === 'comment' ? { name: name.value, trigger: 'message.received', action: 'analyze_public_comment',conditions:conditions.value }
    : { name: name.value, trigger: kind.value==='daily'?'schedule.daily':'message.received', action: 'notify_device', ...(kind.value==='daily'?{localTime:time.value,zone:zone.value}:{conditions:conditions.value}),title:title.value,content:content.value };
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
    if (alive && !control.signal.aborted) previewError.value = readFailure(failure, '预演失败，请重新读取规则后重试。');
  } finally { if (alive && previewController === control) previewLoading.value = false; }
}
void load();
const timer = setInterval(() => { if (!loading.value && !props.busy && document.visibilityState === 'visible') void load(true); }, 5000);
onUnmounted(() => { alive = false; generation++; controller?.abort(); previewController?.abort(); clearInterval(timer); });
</script>

<template>
  <section>
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">自动化</h1>
      </div>
      <div class="management-actions">
        <NButton attr-type="button" class="text-button" :disabled="loading || busy" @click="load()">刷新</NButton>
        <NButton
          attr-type="button"
          class="secondary"
          :disabled="!operate || busy || loading || !!error || !!editor || !!deletion"
          :aria-expanded="compose"
          @click="compose = !compose"
        >
          {{ compose ? '收起' : '创建规则' }}
        </NButton>
      </div>
    </div>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <p v-if="loading" class="muted" role="status" aria-busy="true">正在读取自动化状态…</p>
    <StatusNote v-if="uncertain" tone="warn">规则保存结果尚未确认，重试保持同一规则和参数。刷新或关闭页面后请先核对规则列表。</StatusNote>
    <RuleEditor
      v-if="editor"
      :key="editor.rule.ruleId + ':' + editor.rule.version"
      :session="editor"
      :current="currentRules.get(editor.rule.ruleId)"
      :disabled="busy || loading"
      :failed="!!error"
      @change="changeEdit"
      @close="closeEditor"
      @save="editorAction(saveEdit)"
      @check="(reload) => editorAction(() => checkEdit(reload))"
    />
    <ConsoleForm v-if="deletion" class="inline-form rule-delete confirmation-panel is-destructive" aria-label="删除规则确认" @submit.prevent="confirmDelete()">
      <h2>删除“{{ deletion.rule.name }}”</h2>
      <p>仅删除已停用且无在途任务的规则；执行历史和已生成消息保留。</p>
      <StatusNote v-if="deletion.uncertain" tone="warn">删除结果尚未确认。可只读核对，或重试原版本删除。切换导航保留本次请求。</StatusNote>
      <ConsoleField path="field-1" :label-props="{ id: 'automationpanel-field-1' + '-label', for: 'automationpanel-field-1' }">
        <template #label>输入规则名称确认</template>
        <NInput
          :disabled="busy"
          v-model:value="deleteName"
          :input-props="{ autocomplete: 'off', 'aria-label': '确认删除的规则名称', id: 'automationpanel-field-1' }"
        />
      </ConsoleField>
      <div class="form-footer">
        <NButton attr-type="button" class="text-button" :disabled="busy" @click="closeDelete">关闭</NButton>
        <div class="management-actions">
          <NButton attr-type="button" v-if="deletion.uncertain" class="secondary" :disabled="busy" @click="confirmDelete(true)">
            核对删除结果
          </NButton>
          <NButton
            type="error"
            attr-type="submit"
            class="primary"
            :disabled="busy || loading || !!error || deleteName !== deletion.rule.name"
          >
            {{ deletion.uncertain ? '重试原版本删除' : '确认删除规则' }}
          </NButton>
        </div>
      </div>
    </ConsoleForm>
    <div v-if="previewLoading" class="scope-note" role="status">正在预演规则…</div>
    <p v-if="previewError" class="feedback error" role="alert">
      {{ previewError }}
      <NButton attr-type="button" class="text-button" @click="closePreview">关闭</NButton>
    </p>
    <section v-if="preview" class="inline-form rule-preview" aria-label="规则预演结果">
      <div class="record-heading">
        <h2>{{ preview.rule.name }} · 版本 {{ preview.rule.version }}</h2>
        <NButton attr-type="button" class="text-button" @click="closePreview">关闭预演</NButton>
      </div>
      <p>预演未执行动作，不创建消息、分析任务或投递。</p>
      <p v-if="preview.rule.action === 'notify_device'">
        候选启用设备：{{ preview.candidateDevices }}。离线和免打扰由实际投递时的既有规则处理。
      </p>
      <p v-else>仅分析符合公开性与授权检查的 Halo 新评论。</p>
      <p v-if="preview.block" class="scope-note">{{ errorNames[preview.block] || '当前条件未满足。' }}</p>
      <p v-if="!preview.rule.enabled" class="muted">规则当前停用，预演不会将其启用。</p>
    </section>
    <ConsoleForm v-if="compose && !error" class="inline-form" @submit.prevent="save">
      <div class="form-columns">
        <ConsoleField
          path="field-2"
          :rule="fieldRule(() => name, { required: true, maxlength: 80, disabled: () => !operate || busy || loading || !!pending })"
          :label-props="{ id: 'automationpanel-field-2' + '-label', for: 'automationpanel-field-2' }"
        >
          <template #label>名称</template>
          <NInput
            :disabled="!operate || busy || loading || !!pending"
            v-model:value="name"
            :maxlength="80"
            :input-props="{ required: true, id: 'automationpanel-field-2', 'aria-label': '名称' }"
          />
        </ConsoleField>
        <ConsoleField path="field-3" :label-props="{ id: 'automationpanel-field-3' + '-label', for: 'automationpanel-field-3' }">
          <template #label>规则</template>
          <NSelect
            @keydown.esc.stop
            :disabled="!operate || busy || loading || !!pending"
            v-model:value="kind"
            :options="[
              { label: 'Halo 新公开评论 → AI 分析', value: 'comment' },
              { label: '每日定时 → 设备提醒', value: 'daily' },
              { label: '新消息条件 → 设备提醒', value: 'incoming' },
            ]"
            :input-props="{ id: 'automationpanel-field-3', 'aria-label': '规则' }"
          />
        </ConsoleField>
      </div>
      <template v-if="kind !== 'comment'">
        <div v-if="kind === 'daily'" class="form-columns spaced">
          <ConsoleField
            path="field-4"
            :rule="fieldRule(() => time, { required: true, disabled: () => !operate || busy || loading || !!pending })"
            :label-props="{ id: 'automationpanel-field-4' + '-label', for: 'automationpanel-field-4' }"
          >
            <template #label>时间</template>
            <NTimePicker
              @keydown.esc.stop
              :disabled="!operate || busy || loading || !!pending"
              :formatted-value="time || null"
              @update:formatted-value="time = $event || ''"
              format="HH:mm"
              value-format="HH:mm"
            />
          </ConsoleField>
          <ConsoleField
            path="field-5"
            :rule="fieldRule(() => zone, { required: true, maxlength: 100, disabled: () => !operate || busy || loading || !!pending })"
            :label-props="{ id: 'automationpanel-field-5' + '-label', for: 'automationpanel-field-5' }"
          >
            <template #label>时区</template>
            <NInput
              :disabled="!operate || busy || loading || !!pending"
              v-model:value="zone"
              :maxlength="100"
              :input-props="{ required: true, id: 'automationpanel-field-5', 'aria-label': '时区' }"
            />
          </ConsoleField>
        </div>
        <ConsoleField
          class="spaced"
          path="field-6"
          :rule="fieldRule(() => title, { required: true, maxlength: 160, disabled: () => !operate || busy || loading || !!pending })"
          :label-props="{ id: 'automationpanel-field-6' + '-label', for: 'automationpanel-field-6' }"
        >
          <template #label>提醒标题</template>
          <NInput
            :disabled="!operate || busy || loading || !!pending"
            v-model:value="title"
            :maxlength="160"
            :input-props="{ required: true, id: 'automationpanel-field-6', 'aria-label': '提醒标题' }"
          />
        </ConsoleField>
        <ConsoleField
          class="spaced"
          path="field-7"
          :rule="fieldRule(() => content, { required: true, maxlength: 8192, disabled: () => !operate || busy || loading || !!pending })"
          :label-props="{ id: 'automationpanel-field-7' + '-label', for: 'automationpanel-field-7' }"
        >
          <template #label>提醒内容</template>
          <NInput
            rows="3"
            :disabled="!operate || busy || loading || !!pending"
            v-model:value="content"
            type="textarea"
            :maxlength="8192"
            :input-props="{ required: true, id: 'automationpanel-field-7', 'aria-label': '提醒内容' }"
          />
        </ConsoleField>
      </template>
      <ConsoleDisclosure v-if="kind !== 'daily'" label="匹配条件" class="automation-conditions" :level="3"
        :summary="Object.values(conditions).filter(Boolean).length ? `${Object.values(conditions).filter(Boolean).length} 项` : '不限'"
        :force-open="!!pending">
      <div class="form-columns">
        <ConsoleField
          v-for="(label, key) in { source: '匹配来源', eventType: '匹配事件类型', keyword: '包含关键词', priority: '匹配优先级' }"
          :key="key"
          :path="'field-8-' + key"
          :label-props="{
            id: 'automationpanel-field-1' + '-' + key + '-' + key + '-label',
            for: 'automationpanel-field-1' + '-' + key + '-' + key,
          }"
        >
          <template #label>{{ label }}</template>
          <NSelect
            @keydown.esc.stop
            v-if="key === 'priority'"
            :disabled="busy || loading || !!pending"
            v-model:value="conditions[key]"
            :options="[
              { label: '不限', value: '' },
              { label: '低', value: 'low' },
              { label: '普通', value: 'normal' },
              { label: '高', value: 'high' },
              { label: '紧急', value: 'critical' },
            ]"
            :aria-label="label"
            :input-props="{ id: 'automationpanel-field-1' + '-' + key, 'aria-label': label }"
          />
          <NInput
            v-else
            placeholder="留空不限制"
            :disabled="busy || loading || !!pending"
            v-model:value="conditions[key]"
            :maxlength="key === 'keyword' ? 160 : key === 'source' ? 32 : 64"
            :input-props="{ 'aria-label': label }"
          />
        </ConsoleField>
      </div>
      </ConsoleDisclosure>
      <div class="form-footer">
        <span>
          {{
            kind === 'daily'
              ? '每天最多一次；启动后补发最近两小时内的当日提醒。新规则默认停用。'
              : '条件为同时满足。新规则默认停用；不处理自动化生成的提醒。'
          }}
        </span>
        <NButton type="primary" attr-type="submit" class="primary" :disabled="busy || loading">
          {{ uncertain ? '重试保存同一规则' : '保存规则' }}
        </NButton>
      </div>
    </ConsoleForm>
    <div v-if="!rules.length && !loading && !error" class="empty-state">
      <h2>暂无自动化规则</h2>
      <p>规则启用后才处理新事件。</p>
    </div>
    <div v-else class="record-list">
      <article v-for="rule in rules" :key="rule.ruleId" class="record-card">
        <div class="record-heading">
          <h2>{{ rule.name }}</h2>
          <NCheckbox
            class="checkbox-label"
            :checked="rule.enabled"
            :disabled="
              !operate || busy || loading || !!editor || !!deletion || (!rule.enabled && rule.action === 'analyze_public_comment' && !ai)
            "
            @update:checked="enable(rule)" :aria-disabled="
              !operate || busy || loading || !!editor || !!deletion || (!rule.enabled && rule.action === 'analyze_public_comment' && !ai)
            "
          >
            启用
          </NCheckbox>
        </div>
        <p class="muted">
          {{
            rule.trigger === 'message.received'
              ? rule.action === 'notify_device'
                ? '新消息条件 → 设备提醒'
                : 'Halo 新公开评论 → AI 分析'
              : `每日 ${rule.arguments.localTime}（${rule.arguments.zone}）→ 设备提醒`
          }}
        </p>
        <p v-if="rule.action === 'notify_device'" class="record-target">{{ rule.arguments.title }}</p>
        <p v-if="rule.trigger === 'message.received'" class="muted">
          匹配：{{ rule.arguments.sourceFilter || '全部来源' }} · {{ rule.arguments.eventTypeFilter || '全部事件'
          }}{{ rule.arguments.keywordFilter ? ' · 包含 ' + rule.arguments.keywordFilter : ''
          }}{{ rule.arguments.priorityFilter ? ' · ' + rule.arguments.priorityFilter : '' }}
        </p>
        <p v-if="rule.action === 'analyze_public_comment' && !ai" class="scope-note">AI 服务未配置。</p>
        <div class="management-actions spaced">
          <NButton
            attr-type="button"
            :id="`edit-rule-${rule.ruleId}`"
            class="text-button"
            :disabled="!operate || busy || loading || !!pending || !!editor || !!deletion || !rule.canEdit"
            :title="errorNames[rule.editBlock] || '编辑已停用规则'"
            @click="edit(rule)"
          >
            编辑
          </NButton>
          <NButton
            attr-type="button"
            class="text-button"
            :disabled="busy || loading || previewLoading || !!editor || !!deletion"
            @click="test(rule)"
          >
            测试预演
          </NButton>
          <NButton
            attr-type="button"
            :id="`delete-rule-${rule.ruleId}`"
            class="text-button"
            :disabled="!operate || busy || loading || !!pending || !!editor || !!deletion || !rule.canEdit"
            @click="remove(rule)"
          >
            删除
          </NButton>
          <span v-if="rule.editBlock" class="muted">{{ errorNames[rule.editBlock] }}</span>
        </div>
      </article>
    </div>
    <ConsoleDisclosure v-if="runs.length" label="最近执行" class="automation-history" :summary="`${runs.length} 条`" :force-open="runs.some(r => r.state === 'failed')">
      <div class="table-shell" tabindex="0" role="region" aria-label="最近执行，可用方向键横向滚动查看全部列">
        <table>
          <thead>
            <tr>
              <th scope="col">规则 / 时间</th>
              <th scope="col">结果</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="run in runs" :key="run.runId">
              <td>
                <strong>{{ run.name }}</strong>
                <span class="cell-sub">{{ formatTime(run.createdAt) }}</span>
              </td>
              <td data-label="结果">
                <span class="badge" :class="run.state === 'enqueued' ? 'success' : run.state === 'failed' ? 'danger' : ''">
                  {{ states[run.state] }}
                </span>
                <span v-if="run.result" class="cell-sub">
                  {{ run.result.outcome === 'analysis_queued' ? 'AI 分析队列' : '消息投递队列' }}
                </span>
                <span v-if="run.lastError" class="cell-sub">{{ errorNames[run.lastError] || '处理未完成' }}</span>
              </td>
              <td>
                <NButton
                  attr-type="button"
                  v-if="
                    run.state === 'failed' &&
                    currentRules.get(run.ruleId)?.enabled &&
                    currentRules.get(run.ruleId)?.version === run.ruleVersion
                  "
                  class="secondary compact"
                  :disabled="busy || loading"
                  @click="retry(run)"
                >
                  重试
                </NButton>
                <span v-else>—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </ConsoleDisclosure>
    <p class="scope-note">停用会取消尚未开始的任务。设备确认、用户已读和 AI 分析结果可在消息及投递中核对。</p>
  </section>
</template>
