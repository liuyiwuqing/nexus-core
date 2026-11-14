<script setup lang="ts">
import { provide } from 'vue';
import { onMounted, onUnmounted, ref, watch } from 'vue';
import InboxPanel from './InboxPanel.vue';
import DevicesPanel from './DevicesPanel.vue';
import ConnectorsPanel from './ConnectorsPanel.vue';
import DeliveryPanel from './DeliveryPanel.vue';
import ToolsPanel from './ToolsPanel.vue';
import AutomationPanel from './AutomationPanel.vue';
import RoutingPanel from './RoutingPanel.vue';
import type { RoutingSession, SourceSession, AccessSession, TemplateSession } from './types';
import SourcesPanel from './SourcesPanel.vue';
import AccessPanel from './AccessPanel.vue';
import TemplatesPanel from './TemplatesPanel.vue';
import type { Role } from './access';
import PetPanel from './PetPanel.vue';
import MemoryPanel from './MemoryPanel.vue';
import OverviewPanel from './OverviewPanel.vue';
import { errorNames, type Message, type MessageSubmission, type ToolSubmission, type RuleSubmission, type AutomationRule, type RuleEditInput, type RuleEditSession, type RuleDeletion, type Device, type Connector, type Delivery, type Status, type Page, type Tab, type ListSearch } from './types';

const role=ref<Role>('viewer');provide('role',role);
const token = ref(''), tokenVisible = ref(false), authenticated = ref(false), loading = ref(false), busy = ref(false);
const error = ref(''), notice = ref(''), tab = ref<Tab>('inbox');
const messages = ref<Page<Message>>({ items: [], total: 0, page: 1, size: 30 });
const deliveries = ref<Page<Delivery>>({ items: [], total: 0, page: 1, size: 30 });
const devices = ref<Device[]>([]), connectors = ref<Connector[]>([]), status = ref<Status | null>(null);
const messageSubmission = ref<MessageSubmission | null>(null), messageUncertain = ref(false);
const toolSubmission = ref<ToolSubmission | null>(null), toolUncertain = ref(false);
const ruleSubmission = ref<RuleSubmission | null>(null), ruleUncertain = ref(false);
const ruleEdit = ref<RuleEditSession | null>(null);
const ruleDeletion = ref<RuleDeletion | null>(null);
const newRoutingSession=():RoutingSession=>({id:'',version:0,draft:null,pending:null,uncertain:false});
const routingSession=ref<RoutingSession>(newRoutingSession());
const sourceSession=ref<SourceSession>({pending:null,credentials:null});
const accessSession=ref<AccessSession>({issued:'',pendingIssue:false});
const templateSession=ref<TemplateSession>({id:'',version:0,draft:null,pending:null,uncertain:false});
const messageReadStatus = ref<Message['readStatus'] | ''>(''), deliveryStatus = ref(''), readFailed = ref(false);
const emptySearch = (): ListSearch => ({ identity: '', receivedFrom: '', receivedBefore: '' });
const messageSearch = ref<ListSearch>(emptySearch()), deliverySearch = ref<ListSearch>(emptySearch());
const exporting = ref<'messages' | 'deliveries' | null>(null);
const tabs: { id: Tab; label: string; glyph: string }[] = [{ id: 'inbox', label: '消息', glyph: '✉' }, { id: 'pet', label: '成长', glyph: '✦' }, { id: 'devices', label: '设备', glyph: '◉' }, { id: 'connectors', label: '接入', glyph: '⇄' }, { id: 'deliveries', label: '投递', glyph: '↗' }, { id: 'tools', label: '工具', glyph: '⌘' }, { id: 'automation', label: '自动化', glyph: '◷' }, { id: 'memory', label: '记忆', glyph: '◇' }, { id: 'overview', label: '概览', glyph: '◈' }, { id: 'routing', label: '路由', glyph: '⇢' }, { id: 'access', label: '权限', glyph: '⚿' }, { id: 'templates', label: '模板', glyph: '▤' }];
let epoch = 0, readGeneration = 0, controller: AbortController | undefined;
let exportGeneration = 0, exportController: AbortController | undefined;
let interval: ReturnType<typeof setInterval> | undefined;
class ApiFailure extends Error { constructor(readonly status: number, code: string) { super(errorNames[code] || `请求失败（HTTP ${status}）`); } }

async function api<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch('/api/v1/' + path, { method, signal, cache: 'no-store',
    headers: { Authorization: 'Bearer ' + token.value, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({ error: '' })) as { error?: string };
    if (response.status === 401) logout();
    throw new ApiFailure(response.status, problem.error || '');
  }
  const content = await response.text();
  return (content ? JSON.parse(content) : undefined) as T;
}

function logout() {
  cancelExport();
  epoch++; readGeneration++; controller?.abort(); authenticated.value = false; token.value = '';
  messages.value = { items: [], total: 0, page: 1, size: 30 }; deliveries.value = { items: [], total: 0, page: 1, size: 30 };
  devices.value = []; connectors.value = []; status.value = null; loading.value = false; notice.value = '';
  messageSubmission.value = null; messageUncertain.value = false;
  toolSubmission.value = null; toolUncertain.value = false; ruleSubmission.value = null; ruleUncertain.value = false;
  ruleEdit.value = null;
  ruleDeletion.value = null;
  routingSession.value=newRoutingSession();
  sourceSession.value={pending:null,credentials:null};accessSession.value={issued:'',pendingIssue:false};templateSession.value={id:'',version:0,draft:null,pending:null,uncertain:false};role.value='viewer';
  messageReadStatus.value = ''; deliveryStatus.value = ''; readFailed.value = false;
  messageSearch.value = emptySearch(); deliverySearch.value = emptySearch();
}

function querySuffix(kind: 'messages' | 'deliveries'): string {
  const search = kind === 'messages' ? messageSearch.value : deliverySearch.value;
  const state = kind === 'messages' ? messageReadStatus.value : deliveryStatus.value;
  const query = new URLSearchParams();
  if (state) query.set(kind === 'messages' ? 'readStatus' : 'status', state);
  if (search.identity) query.set(kind === 'messages' ? 'source' : 'deviceId', search.identity);
  if (search.receivedFrom) query.set('receivedFrom', new Date(search.receivedFrom).toISOString());
  if (search.receivedBefore) query.set('receivedBefore', new Date(search.receivedBefore).toISOString());
  return query.size ? '&' + query.toString() : '';
}

async function refresh(clearFeedback = false): Promise<boolean> {
  controller?.abort(); controller = new AbortController();
  const requestId = ++readGeneration, currentEpoch = epoch, previousError = error.value;
  loading.value = true;
  try {
    const result = await Promise.all([
      api<Page<Message>>(`messages?page=${messages.value.page}${querySuffix('messages')}`, 'GET', undefined, controller.signal),
      api<{ items: Device[] }>('devices', 'GET', undefined, controller.signal),
      api<{ items: Connector[] }>('connectors', 'GET', undefined, controller.signal),
      api<Page<Delivery>>(`deliveries?page=${deliveries.value.page}${querySuffix('deliveries')}`, 'GET', undefined, controller.signal),
      api<Status>('status', 'GET', undefined, controller.signal),
    ]);
    if (requestId !== readGeneration || currentEpoch !== epoch) return false;
    const messageLast = Math.max(1, Math.ceil(result[0].total / result[0].size));
    const deliveryLast = Math.max(1, Math.ceil(result[3].total / result[3].size));
    // 已读/重试或后台更新可能移走末页最后一项；用同一筛选重新读取有效页。
    if (result[0].page > messageLast || result[3].page > deliveryLast) {
      messages.value.page = Math.min(result[0].page, messageLast);
      deliveries.value.page = Math.min(result[3].page, deliveryLast);
      return await refresh(clearFeedback);
    }
    [messages.value, { items: devices.value }, { items: connectors.value }, deliveries.value, status.value] = result;
    // 后台读取恢复只清理读取错误；操作错误留给用户主动核对或下一次操作。
    if ((readFailed.value || clearFeedback) && error.value === previousError) error.value = '';
    readFailed.value = false;
    return true;
  } catch (failure) {
    if (failure instanceof DOMException && failure.name === 'AbortError') return false;
    if (requestId === readGeneration && currentEpoch === epoch) {
      readFailed.value = true;
      error.value = failure instanceof ApiFailure ? failure.message : '暂时无法连接 CORE，请检查服务后重试。';
    } else if (failure instanceof ApiFailure && failure.status === 401) error.value = failure.message;
    return false;
  } finally { if (requestId === readGeneration) loading.value = false; }
}

async function login() {
  if(loading.value)return;
  const currentEpoch=epoch;loading.value=true;
  token.value = token.value.trim(); error.value = ''; notice.value = '';
  try {
    const session=await api<{role:Role}>('session','POST');
    if(!['admin','operator','viewer'].includes(session.role))throw new Error('Invalid session');
    if(currentEpoch!==epoch)return;
    role.value=session.role;
    if(await refresh())authenticated.value=true;
  } catch(failure){if(currentEpoch===epoch||failure instanceof ApiFailure&&failure.status===401)error.value=failure instanceof Error?failure.message:'登录失败';}
  finally{if(currentEpoch===epoch)loading.value=false;}
}

async function mutate(action: () => Promise<unknown>, success: string): Promise<boolean> {
  if (busy.value) return false;
  busy.value = true; error.value = ''; notice.value = '';
  const currentEpoch = epoch;
  try {
    await action();
    if (currentEpoch !== epoch) return false;
    notice.value = success;
    await refresh();
    return true;
  } catch (failure) {
    if (currentEpoch === epoch || failure instanceof ApiFailure && failure.status === 401)
      error.value = failure instanceof ApiFailure ? failure.message : '连接中断，操作结果尚未确认，请刷新后核对。';
    return false;
  } finally { busy.value = false; }
}

async function createMessage(title: string, content: string): Promise<boolean> {
  if (busy.value) return false;
  const currentEpoch = epoch;
  // HTTP 结果未知时，重试必须保持同一逻辑提交；导航不能丢掉去重身份。
  const submission = messageSubmission.value ?? { eventId: crypto.randomUUID(), title, content };
  messageSubmission.value = submission;
  return mutate(async () => {
    try {
      const accepted = await api<{ messageId: string }>('messages', 'POST', {
        connectorId: 'manual', source: 'manual', eventType: 'manual.test', priority: 'normal', ...submission,
      });
      if (!accepted || typeof accepted.messageId !== 'string' || !accepted.messageId)
        throw new Error('Invalid message acknowledgment');
      if (currentEpoch === epoch) { messageSubmission.value = null; messageUncertain.value = false; }
    } catch (failure) {
      if (currentEpoch === epoch) {
        // 408/5xx/连接或解码失败均可能已保存。明确的 4xx 拒绝才允许编辑后新建提交。
        const rejected = failure instanceof ApiFailure && failure.status >= 400 && failure.status < 500 && failure.status !== 408;
        if (rejected) messageSubmission.value = null;
        messageUncertain.value = !rejected;
      }
      throw failure;
    }
  }, '测试消息已保存并进入投递队列。');
}
async function createTool(toolId: string, args: Record<string,string>): Promise<boolean> {
  if (busy.value) return false;
  const currentEpoch = epoch, wasUncertain = toolUncertain.value;
  const submission = toolSubmission.value ?? { requestKey: crypto.randomUUID(), toolId, arguments: { ...args } };
  toolSubmission.value = submission;
  return mutate(async () => {
    try {
      const accepted = await api<{ jobId: string; toolId: string; arguments: Record<string,string> }>('tool-jobs', 'POST', submission);
      if (!accepted || typeof accepted.jobId !== 'string' || !accepted.jobId || accepted.toolId !== submission.toolId || !accepted.arguments ||
          Object.keys(accepted.arguments).length !== Object.keys(submission.arguments).length || Object.entries(submission.arguments).some(([key,value]) => accepted.arguments[key] !== value))
        throw new Error('Invalid tool acknowledgment');
      if (currentEpoch === epoch) { toolSubmission.value = null; toolUncertain.value = false; }
    } catch (failure) {
      if (currentEpoch === epoch) {
        const rejected = failure instanceof ApiFailure && failure.status >= 400 && failure.status < 500 && failure.status !== 408;
        if (rejected && !wasUncertain) toolSubmission.value = null;
        toolUncertain.value = !rejected || wasUncertain;
      }
      throw failure;
    }
  }, submission.toolId === 'app.open' ? '请求已创建，请核对后确认。' : '查询已加入队列。');
}
async function createRule(input: Omit<RuleSubmission,'requestKey'>): Promise<boolean> {
  if (busy.value) return false;
  const currentEpoch = epoch, wasUncertain = ruleUncertain.value;
  const submission = ruleSubmission.value ?? { ...input, requestKey: crypto.randomUUID() };
  ruleSubmission.value = submission;
  return mutate(async () => {
    try {
      const accepted = await api<{ ruleId: string }>('automation/rules', 'POST', submission);
      if (!accepted || typeof accepted.ruleId !== 'string' || !accepted.ruleId) throw new Error('Invalid rule acknowledgment');
      if (currentEpoch === epoch) { ruleSubmission.value = null; ruleUncertain.value = false; }
    } catch (failure) {
      if (currentEpoch === epoch) {
        const rejected = failure instanceof ApiFailure && failure.status >= 400 && failure.status < 500 && failure.status !== 408;
        if (rejected && !wasUncertain) ruleSubmission.value = null;
        ruleUncertain.value = !rejected || wasUncertain;
      }
      throw failure;
    }
  }, '规则已保存；是否启用请在列表中核对。');
}
function beginRuleEdit(rule: AutomationRule) {
  if (busy.value || ruleSubmission.value || ruleDeletion.value || !rule.canEdit) return;
  ruleEdit.value = { rule, draft: { expectedVersion: rule.version, name: rule.name,
    ...(rule.trigger === 'schedule.daily' ? rule.arguments : {}) }, pending: null, uncertain: false, observed: null };
}
function changeRuleEdit(draft: RuleEditInput) { if (ruleEdit.value && !ruleEdit.value.pending && !busy.value) ruleEdit.value.draft = draft; }
function closeRuleEdit() {
  if (busy.value) return;
  if (ruleEdit.value?.uncertain) notice.value = '编辑已关闭；先前可能已保存的修改不会撤回。';
  ruleEdit.value = null;
}
function beginRuleDelete(rule: AutomationRule) {
  if (!busy.value && !ruleEdit.value && !ruleSubmission.value && rule.canEdit)
    ruleDeletion.value = { rule, uncertain: false };
}
function closeRuleDelete() { if (!busy.value) ruleDeletion.value = null; }
async function deleteRule(checkOnly = false): Promise<boolean> {
  const session = ruleDeletion.value, currentEpoch = epoch;
  if (!session || busy.value) return false;
  return mutate(async () => {
    try {
      if (!checkOnly) await api(`automation/rules/${session.rule.ruleId}?expectedVersion=${session.rule.version}`, 'DELETE');
      const current = await api<AutomationRule>(`automation/rules/${session.rule.ruleId}`);
      if (!current || current.ruleId !== session.rule.ruleId || !current.deleted) throw new Error('Deletion not confirmed');
      if (currentEpoch === epoch) ruleDeletion.value = null;
    } catch (failure) {
      if (currentEpoch === epoch) session.uncertain = true;
      throw failure;
    }
  }, '规则已删除，执行历史保留。');
}
function ruleMatchesEdit(rule: AutomationRule, input: RuleEditInput) {
  return !rule.deleted && rule.name === input.name && (rule.trigger === 'message.received' ||
    ['localTime', 'zone', 'title', 'content'].every(key => rule.arguments[key] === input[key as keyof RuleEditInput]));
}
async function saveRuleEdit(): Promise<boolean> {
  const session = ruleEdit.value;
  if (!session || busy.value) return false;
  const currentEpoch = epoch, wasUncertain = session.uncertain;
  const input = session.pending ?? { ...session.draft };
  session.pending = input;
  return mutate(async () => {
    try {
      const saved = await api<AutomationRule>(`automation/rules/${session.rule.ruleId}`, 'PUT', input);
      if (!saved || saved.ruleId !== session.rule.ruleId || saved.trigger !== session.rule.trigger || saved.action !== session.rule.action || ![input.expectedVersion, input.expectedVersion + 1].includes(saved.version) || saved.enabled !== false || !ruleMatchesEdit(saved, input))
        throw new Error('Invalid edit acknowledgment');
      if (currentEpoch === epoch && ruleEdit.value === session) ruleEdit.value = null;
    } catch (failure) {
      if (currentEpoch === epoch && ruleEdit.value === session) {
        const rejected = failure instanceof ApiFailure && failure.status >= 400 && failure.status < 500 && failure.status !== 408;
        if (rejected && !wasUncertain) session.pending = null;
        session.uncertain = !rejected || wasUncertain;
      }
      throw failure;
    }
  }, '规则已保存，保持停用；重新启用后生效。');
}
async function checkRuleEdit(reload = false): Promise<boolean> {
  const session = ruleEdit.value;
  if (!session || busy.value) return false;
  const currentEpoch = epoch;
  return mutate(async () => {
    const current = await api<AutomationRule>(`automation/rules/${session.rule.ruleId}`);
    if (currentEpoch !== epoch || ruleEdit.value !== session) return;
    if (!current || current.ruleId !== session.rule.ruleId || !Number.isInteger(current.version) || current.version < 1 || current.trigger !== session.rule.trigger || current.action !== session.rule.action || typeof current.name !== 'string' || typeof current.enabled !== 'boolean' || !current.arguments)
      throw new ApiFailure(502, 'invalid_rule_snapshot');
    if (reload) {
      // This is an explicit discard/load action, never a polling overwrite of the draft.
      ruleEdit.value = { rule: current, draft: { expectedVersion: current.version, name: current.name,
        ...(current.trigger === 'schedule.daily' ? current.arguments : {}) }, pending: null, uncertain: false, observed: null };
    } else if (session.pending && current.version >= session.pending.expectedVersion && ruleMatchesEdit(current, session.pending)) ruleEdit.value = null;
    else session.observed = current;
  }, reload ? '已载入当前版本。' : session.pending ? '已核对当前规则，请以当前定义和启停状态为准。' : '已读取当前规则。');
}
const markRead = (id: string) => mutate(() => api(`messages/${id}/read`, 'POST'), '已标记已读。');
const register = (deviceId: string, name: string) => mutate(() => api('devices', 'POST', { deviceId, name }), '设备已登记。');
const rename = (id: string, name: string, expectedName: string) => mutate(async () => {
  const updated = await api<{ deviceId: string; name: string }>(`devices/${encodeURIComponent(id)}/name`, 'PATCH', { name, expectedName });
  devices.value = devices.value.map(device => device.deviceId === updated.deviceId ? { ...device, name: updated.name } : device);
}, '设备名称已更新。');
const enable = (id: string, enabled: boolean) => mutate(() => api(`devices/${id}/enabled`, 'PUT', { enabled }), enabled ? '已启用提醒。' : '已停用提醒。');
const poll = () => mutate(() => api('connectors/halo/poll', 'POST'), 'Halo 同步完成。');
const importComment = (name: string) => mutate(() => api(`connectors/halo/comments/${encodeURIComponent(name)}/import`, 'POST'), '公开评论已导入；重复导入会合并到原消息。');
const retry = (id: string) => mutate(() => api(`deliveries/${id}/retry`, 'POST'), '已重新加入投递队列。');
function messagePage(page: number) { messages.value.page = page; void refresh(); }
function deliveryPage(page: number) { deliveries.value.page = page; void refresh(); }
function filterMessages(value: Message['readStatus'] | '') {
  if (loading.value || busy.value || exporting.value) return;
  messageReadStatus.value = value; messages.value = { ...messages.value, items: [], total: 0, page: 1 };
  void refresh();
}
function filterDeliveries(value: string) {
  if (loading.value || busy.value || exporting.value) return;
  deliveryStatus.value = value; deliveries.value = { ...deliveries.value, items: [], total: 0, page: 1 };
  void refresh();
}
function searchMessages(value: ListSearch, clear = false) {
  if (loading.value || busy.value || exporting.value) return;
  messageSearch.value = value;
  if (clear) messageReadStatus.value = '';
  messages.value = { ...messages.value, items: [], total: 0, page: 1 }; void refresh();
}
function searchDeliveries(value: ListSearch, clear = false) {
  if (loading.value || busy.value || exporting.value) return;
  deliverySearch.value = value;
  if (clear) deliveryStatus.value = '';
  deliveries.value = { ...deliveries.value, items: [], total: 0, page: 1 }; void refresh();
}
function cancelExport() { exportGeneration++; exportController?.abort(); exporting.value = null; }
watch(tab, cancelExport);
async function exportMetadata(kind: 'messages' | 'deliveries') {
  if (loading.value || busy.value || exporting.value || readFailed.value) return;
  const currentEpoch = epoch, generation = ++exportGeneration;
  exportController = new AbortController(); exporting.value = kind; error.value = ''; notice.value = '';
  const query = querySuffix(kind).replace(/^&/, '?');
  try {
    const response = await fetch(`/api/v1/${kind}/export${query}`, { signal: exportController.signal, cache: 'no-store', headers: { Authorization: 'Bearer ' + token.value } });
    if (currentEpoch !== epoch || generation !== exportGeneration) return;
    if (!response.ok) {
      const problem = await response.json().catch(() => ({ error: '' })) as { error?: string };
      if (currentEpoch !== epoch || generation !== exportGeneration) return;
      if (response.status === 401) logout();
      throw new ApiFailure(response.status, problem.error || '');
    }
    if (response.headers.get('Content-Type')?.split(';')[0].trim() !== 'text/csv') throw new Error('invalid_export');
    const blob = await response.blob();
    if (currentEpoch !== epoch || generation !== exportGeneration) return;
    if (!blob.size) throw new Error('invalid_export');
    const url = URL.createObjectURL(blob), link = document.createElement('a');
    link.href = url; link.download = `${kind}-metadata.csv`; document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    notice.value = '元数据 CSV 已生成。';
  } catch (failure) {
    if (failure instanceof DOMException && failure.name === 'AbortError') return;
    if (currentEpoch === epoch && generation === exportGeneration || failure instanceof ApiFailure && failure.status === 401)
      error.value = failure instanceof ApiFailure ? failure.message : '未能导出元数据，请重试。';
  } finally { if (generation === exportGeneration) exporting.value = null; }
}
onMounted(() => { interval = setInterval(() => { if (authenticated.value && !loading.value && !busy.value && !exporting.value && document.visibilityState === 'visible') void refresh(); }, 5000); });
onUnmounted(() => { clearInterval(interval); controller?.abort(); cancelExport(); });
</script>

<template>
  <main v-if="!authenticated" class="login-page">
    <form class="login-card" @submit.prevent="login">
      <div class="brand"><span class="pet-symbol" aria-hidden="true"><i></i><i></i></span><span>NEXUS<span class="brand-sub">PET CONSOLE</span></span></div>
      <h1>连接你的桌面伙伴</h1><p>使用管理员或独立角色令牌登录。</p>
      <label class="token-label">管理令牌<input v-model="token" :type="tokenVisible ? 'text' : 'password'" autocomplete="off" spellcheck="false" required :disabled="loading" /></label>
      <label class="checkbox-label"><input v-model="tokenVisible" type="checkbox" />显示令牌</label>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <button class="primary full-width" :disabled="loading">{{ loading ? '正在连接…' : '进入消息中心' }}</button>
      <small>令牌仅保留在当前页面，退出后清除。</small>
    </form>
  </main>
  <div v-else class="app-layout">
    <aside class="sidebar">
      <div class="brand"><span class="pet-symbol" aria-hidden="true"><i></i><i></i></span><span>NEXUS<span class="brand-sub">PET CONSOLE</span></span></div>
      <nav aria-label="主导航"><button v-for="item in tabs.filter(v=>role==='admin'||!['access','tools'].includes(v.id))" :key="item.id" :class="{ active: tab === item.id }" :aria-current="tab === item.id ? 'page' : undefined" @click="tab = item.id"><span aria-hidden="true">{{ item.glyph }}</span>{{ item.label }}</button></nav>
      <div class="sidebar-bottom"><span class="local-indicator">本机控制台</span><button class="text-button" @click="logout">退出登录</button></div>
    </aside>
    <div class="workspace">
      <header class="topbar"><span class="muted">{{role==='admin'?'管理员':role==='operator'?'操作员 · 消息与规则':'只读访问'}}</span><span class="connection-status" :class="status?.mqtt === 'ready' ? 'connected' : 'disconnected'"><i></i>{{ status?.mqtt === 'ready' ? '消息连接正常' : '消息连接不可用' }}</span><button class="text-button" :disabled="loading" @click="refresh(true)">{{ loading ? '刷新中…' : '刷新' }}</button></header>
      <div class="content">
        <div v-if="error" class="feedback error" role="alert"><span>{{ error }}</span><button class="text-button" @click="refresh(true)">重试</button></div>
        <div v-else-if="notice" class="feedback" role="status"><span>{{ notice }}</span><button class="text-button" aria-label="关闭提示" @click="notice = ''">×</button></div>
        <InboxPanel v-if="tab === 'inbox'" :data="messages" :loading="loading" :failed="readFailed" :read-status="messageReadStatus" :busy="busy || !!exporting" :search="messageSearch" :exporting="exporting === 'messages'" :pending="messageSubmission" :uncertain="messageUncertain" :create="createMessage" :mark-read="markRead" :api="api" :mutate="mutate" @page="messagePage" @filter="filterMessages" @search="searchMessages" @clear="searchMessages(emptySearch(), true)" @export="exportMetadata('messages')" @connectors="tab = 'connectors'" />
        <DevicesPanel v-else-if="tab === 'devices'" :items="devices" :busy="busy" :api="api" :register="register" :enable="enable" :rename="rename" />
        <section v-else-if="tab === 'connectors'"><ConnectorsPanel :items="connectors" :busy="busy" :poll="poll" :import-comment="importComment" /><SourcesPanel :api="api" :devices="devices" :busy="busy" :session="sourceSession" :mutate="mutate" /></section>
        <DeliveryPanel v-else-if="tab === 'deliveries'" :data="deliveries" :api="api" :busy="busy || !!exporting" :loading="loading" :failed="readFailed" :status="deliveryStatus" :search="deliverySearch" :exporting="exporting === 'deliveries'" :retry="retry" @page="deliveryPage" @filter="filterDeliveries" @search="searchDeliveries" @clear="searchDeliveries(emptySearch(), true)" @export="exportMetadata('deliveries')" @refresh="refresh" />
        <ToolsPanel v-else-if="tab === 'tools'" :api="api" :mutate="mutate" :busy="busy" :pending="toolSubmission" :uncertain="toolUncertain" :create="createTool" />
        <PetPanel v-else-if="tab === 'pet'" :api="api" :mutate="mutate" :busy="busy" />
        <MemoryPanel v-else-if="tab === 'memory'" :api="api" />
        <OverviewPanel v-else-if="tab === 'overview'" :api="api" />
        <AccessPanel v-else-if="tab === 'access' && role === 'admin'" :session="accessSession" :api="api" :busy="busy" :mutate="mutate" />
        <TemplatesPanel v-else-if="tab === 'templates'" :api="api" :busy="busy" :mutate="mutate" :session="templateSession" />
        <RoutingPanel v-else-if="tab === 'routing'" :api="api" :devices="devices" :busy="busy" :mutate="mutate" :session="routingSession" />
        <AutomationPanel v-else :api="api" :mutate="mutate" :busy="busy" :pending="ruleSubmission" :uncertain="ruleUncertain" :create="createRule" :editor="ruleEdit" :begin-edit="beginRuleEdit" :change-edit="changeRuleEdit" :close-edit="closeRuleEdit" :save-edit="saveRuleEdit" :check-edit="checkRuleEdit" :deletion="ruleDeletion" :begin-delete="beginRuleDelete" :close-delete="closeRuleDelete" :delete-rule="deleteRule" />
      </div>
    </div>
  </div>
</template>
