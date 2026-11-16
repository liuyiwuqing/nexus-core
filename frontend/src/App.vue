<script setup lang="ts">
import { NButton, NInput } from 'naive-ui';
import { provide } from 'vue';
import ConsoleShell from './ConsoleShell.vue';
import { onMounted, onUnmounted, ref, watch } from 'vue';
import InboxPanel from './InboxPanel.vue';
import { asyncPanel, prefetch, prefetchTab } from './panels';
import type { RoutingSession, SourceSession, AccessSession, TemplateSession } from './types';
import type {PullSession} from './types';
import { watchConsoleEvents } from './eventStream';
import type { Role } from './access';
import { errorNames, type Message, type MessageSubmission, type ToolSubmission, type RuleSubmission, type AutomationRule, type RuleEditInput, type RuleEditSession, type RuleDeletion, type Device, type Connector, type Delivery, type Status, type Page, type Tab, type ListSearch } from './types';
// 首屏只保留外壳与默认页；其余页面按标签页拆分为独立 chunk，登录后首包不再包含全部面板。
const DevicesPanel = asyncPanel('devices');
const ConnectorsPanel = asyncPanel('connectors');
const DeliveryPanel = asyncPanel('deliveries');
const ToolsPanel = asyncPanel('tools');
const AutomationPanel = asyncPanel('automation');
const RoutingPanel = asyncPanel('routing');
const SourcesPanel = asyncPanel('sources');
const PullPanel = asyncPanel('pull');
const AccessPanel = asyncPanel('access');
const TemplatesPanel = asyncPanel('templates');
const SetupGuide = asyncPanel('guide');
const PetPanel = asyncPanel('pet');
const MemoryPanel = asyncPanel('memory');
const OverviewPanel = asyncPanel('overview');

const role=ref<Role>('viewer');provide('role',role);
const showGuide=ref(false),selectedDevice=ref(''),eventsConnected=ref(false);
let stream:AbortController|undefined,reconnect:ReturnType<typeof setTimeout>|undefined,streamGeneration=0;
function stopEvents(){streamGeneration++;stream?.abort();clearTimeout(reconnect);eventsConnected.value=false;}
function startEvents(){stopEvents();const generation=streamGeneration;let attempts=0;const connect=()=>{if(!authenticated.value||generation!==streamGeneration)return;const control=new AbortController();stream=control;void watchConsoleEvents(token.value,control.signal,event=>{if(generation!==streamGeneration)return;if(event==='unauthorized'){logout();return;}if(event==='connected'){eventsConnected.value=true;attempts=0;}if(event==='invalidate'&&document.visibilityState==='visible'&&!loading.value&&!busy.value&&!exporting.value)void refresh(false,invalidateScope());}).catch(()=>{}).finally(()=>{if(generation!==streamGeneration||control.signal.aborted)return;eventsConnected.value=false;reconnect=setTimeout(connect,Math.min(30000,1000*2**Math.min(attempts++,5)));});};connect();}
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
const pullSession=ref<PullSession>({pending:null,uncertainPoll:null});
const accessSession=ref<AccessSession>({issued:'',pendingIssue:false});
const templateSession=ref<TemplateSession>({id:'',version:0,draft:null,pending:null,uncertain:false});
const messageReadStatus = ref<Message['readStatus'] | ''>(''), deliveryStatus = ref(''), readFailed = ref(false);
const emptySearch = (): ListSearch => ({ identity: '', receivedFrom: '', receivedBefore: '' });
const messageSearch = ref<ListSearch>(emptySearch()), deliverySearch = ref<ListSearch>(emptySearch());
const exporting = ref<'messages' | 'deliveries' | null>(null);
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
  stopEvents();showGuide.value=false;selectedDevice.value='';
  cancelExport();
  epoch++; readGeneration++; controller?.abort(); authenticated.value = false; token.value = '';
  messages.value = { items: [], total: 0, page: 1, size: 30 }; deliveries.value = { items: [], total: 0, page: 1, size: 30 };
  devices.value = []; connectors.value = []; status.value = null; loading.value = false; notice.value = '';
  messageSubmission.value = null; messageUncertain.value = false;
  toolSubmission.value = null; toolUncertain.value = false; ruleSubmission.value = null; ruleUncertain.value = false;
  ruleEdit.value = null;
  ruleDeletion.value = null;
  routingSession.value=newRoutingSession();
  sourceSession.value={pending:null,credentials:null};pullSession.value={pending:null,uncertainPoll:null};accessSession.value={issued:'',pendingIssue:false};templateSession.value={id:'',version:0,draft:null,pending:null,uncertain:false};role.value='viewer';
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

type Resource = 'messages' | 'deliveries' | 'devices' | 'connectors' | 'status';
const allResources: readonly Resource[] = ['messages', 'devices', 'connectors', 'deliveries', 'status'];
// 每个标签页实际渲染的集合。轮询只取当前页需要的接口，其余集合沿用上一次的值；
// 设备清单只在设备 / 路由 / 接入页会被改写，登录时已建立快照，故不放进消息页的轮询里（失效事件会补）。
const tabResources: Record<Tab, readonly Resource[]> = {
  inbox: ['messages', 'status'],
  deliveries: ['deliveries', 'status'],
  devices: ['devices', 'status'],
  routing: ['devices', 'status'],
  connectors: ['connectors', 'devices', 'status'],
  tools: ['status'],
  pet: ['status'],
  memory: ['status'],
  overview: ['status'],
  access: ['status'],
  templates: ['status'],
  automation: ['status'],
};
// SSE 只告知"有变化"，不说明变了什么；设备清单在外壳（设备视图）里始终可见，失效时必须一并补齐，
// 否则停在消息页时新登记的设备不会出现在选择器里。普通轮询不含它，避免每 5 秒为下拉框多打一次请求。
function invalidateScope(): readonly Resource[] {
  const polled = tabResources[tab.value];
  return polled.includes('devices') ? polled : [...polled, 'devices'];
}

async function refresh(clearFeedback = false, resources?: readonly Resource[]): Promise<boolean> {
  controller?.abort(); controller = new AbortController();
  const requestId = ++readGeneration, currentEpoch = epoch, previousError = error.value;
  const need = resources ?? tabResources[tab.value], signal = controller.signal;
  // 纯状态探测不改变任何可见集合，不占用 loading，避免顶栏每隔几秒闪一次"刷新中"。
  const track = need.some(resource => resource !== 'status');
  if (track) loading.value = true;
  try {
    const [messagePage, deviceList, connectorList, deliveryPage, liveStatus] = await Promise.all([
      need.includes('messages') ? api<Page<Message>>(`messages?page=${messages.value.page}${querySuffix('messages')}`, 'GET', undefined, signal) : undefined,
      need.includes('devices') ? api<{ items: Device[] }>('devices', 'GET', undefined, signal) : undefined,
      need.includes('connectors') ? api<{ items: Connector[] }>('connectors', 'GET', undefined, signal) : undefined,
      need.includes('deliveries') ? api<Page<Delivery>>(`deliveries?page=${deliveries.value.page}${querySuffix('deliveries')}`, 'GET', undefined, signal) : undefined,
      need.includes('status') ? api<Status>('status', 'GET', undefined, signal) : undefined,
    ]);
    if (requestId !== readGeneration || currentEpoch !== epoch) return false;
    // 已读/重试或后台更新可能移走末页最后一项；用同一筛选重新读取有效页，两个列表一起纠正后再重取。
    let repaged = false;
    if (messagePage) {
      const messageLast = Math.max(1, Math.ceil(messagePage.total / messagePage.size));
      if (messagePage.page > messageLast) { messages.value.page = messageLast; repaged = true; }
      else messages.value = messagePage;
    }
    if (deliveryPage) {
      const deliveryLast = Math.max(1, Math.ceil(deliveryPage.total / deliveryPage.size));
      if (deliveryPage.page > deliveryLast) { deliveries.value.page = deliveryLast; repaged = true; }
      else deliveries.value = deliveryPage;
    }
    if (repaged) return await refresh(clearFeedback, resources);
    if (deviceList) {
      devices.value = deviceList.items;
      if (selectedDevice.value && !devices.value.some(d => d.deviceId === selectedDevice.value)) selectedDevice.value = '';
    }
    if (connectorList) connectors.value = connectorList.items;
    if (liveStatus) status.value = liveStatus;
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
  } finally { if (track && requestId === readGeneration) loading.value = false; }
}

// 用户主动触发（顶栏刷新、错误重试、登录）才做全量拉取，避免"点了刷新却没刷新当前页"。
const fullRefresh = (clearFeedback = false) => refresh(clearFeedback, allResources);

async function login() {
  if(loading.value)return;
  const currentEpoch=epoch;loading.value=true;
  token.value = token.value.trim(); error.value = ''; notice.value = '';
  try {
    const session=await api<{role:Role}>('session','POST');
    if(!['admin','operator','viewer'].includes(session.role))throw new Error('Invalid session');
    if(currentEpoch!==epoch)return;
    role.value=session.role;
    if(await refresh(false, allResources)){authenticated.value=true;showGuide.value=devices.value.length===0;startEvents();}
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
function editableRule(rule:AutomationRule):RuleEditInput {
  return {expectedVersion:rule.version,name:rule.name,
    ...(rule.action==='notify_device'?{title:rule.arguments.title,content:rule.arguments.content}:{}),
    ...(rule.trigger==='schedule.daily'?{localTime:rule.arguments.localTime,zone:rule.arguments.zone}:{conditions:Object.fromEntries(['source','eventType','keyword','priority'].map(k=>[k,rule.arguments[k+'Filter']||'']))})};
}
function beginRuleEdit(rule: AutomationRule) {
  if (busy.value || ruleSubmission.value || ruleDeletion.value || !rule.canEdit) return;
  ruleEdit.value = { rule, draft: editableRule(rule), pending: null, uncertain: false, observed: null };
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
function ruleMatchesEdit(rule:AutomationRule,input:RuleEditInput) {
  return !rule.deleted&&rule.name===input.name&&
    (rule.action!=='notify_device'||['title','content'].every(k=>rule.arguments[k]===input[k as 'title'|'content']))&&
    (rule.trigger!=='schedule.daily'||['localTime','zone'].every(k=>rule.arguments[k]===input[k as 'localTime'|'zone']))&&
    (rule.trigger!=='message.received'||['source','eventType','keyword','priority'].every(k=>(rule.arguments[k+'Filter']||'')===(input.conditions?.[k]||'')));
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
      ruleEdit.value = { rule: current, draft: editableRule(current), pending: null, uncertain: false, observed: null };
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
function pullMessages(source:string){tab.value='inbox';void searchMessages({...emptySearch(),identity:source});}
const poll = () => mutate(() => api('connectors/halo/poll', 'POST'), 'Halo 同步完成。');
const importComment = (name: string) => mutate(() => api(`connectors/halo/comments/${encodeURIComponent(name)}/import`, 'POST'), '公开评论已导入；重复导入会合并到原消息。');
const retry = (id: string) => mutate(() => api(`deliveries/${id}/retry`, 'POST'), '已重新加入投递队列。');
function messagePage(page: number) { messages.value.page = page; void refresh(); }
function deliveryPage(page: number) { deliveries.value.page = page; void refresh(); }
function filterMessages(value: Message['readStatus'] | '') {
  if (busy.value || exporting.value) return;
  messageReadStatus.value = value; messages.value = { ...messages.value, items: [], total: 0, page: 1 };
  void refresh();
}
function filterDeliveries(value: string) {
  if (busy.value || exporting.value) return;
  deliveryStatus.value = value; deliveries.value = { ...deliveries.value, items: [], total: 0, page: 1 };
  void refresh();
}
function searchMessages(value: ListSearch, clear = false) {
  if (busy.value || exporting.value) return;
  messageSearch.value = value;
  if (clear) messageReadStatus.value = '';
  messages.value = { ...messages.value, items: [], total: 0, page: 1 }; void refresh();
}
function searchDeliveries(value: ListSearch, clear = false) {
  if (busy.value || exporting.value) return;
  deliverySearch.value = value;
  if (clear) deliveryStatus.value = '';
  deliveries.value = { ...deliveries.value, items: [], total: 0, page: 1 }; void refresh();
}
function cancelExport() { exportGeneration++; exportController?.abort(); exporting.value = null; }
watch(tab, next => {
  cancelExport();
  // 悬停预取没赶上的（键盘/程序化切换）在这里补一次；同一 chunk 只会请求一次。
  prefetchTab(next);
  if (authenticated.value && !busy.value && !exporting.value) void refresh();
});
// 接入引导会出现在任意标签页之上，单独预取，避免首次展开时闪骨架。
watch(showGuide, open => { if (open) prefetch('guide'); });
async function exportMetadata(kind: 'messages' | 'deliveries',content=false) {
  if(content&&(role.value!=='admin'||kind!=='messages'))return;
  if (loading.value || busy.value || exporting.value || readFailed.value) return;
  const currentEpoch = epoch, generation = ++exportGeneration;
  exportController = new AbortController(); exporting.value = kind; error.value = ''; notice.value = '';
  const query = querySuffix(kind).replace(/^&/, '?');
  try {
    const response = await fetch(content?`/api/v1/messages/content-export?confirm=EXPORT_MESSAGE_CONTENT${querySuffix('messages')}`:`/api/v1/${kind}/export${query}`, { signal: exportController.signal, cache: 'no-store', headers: { Authorization: 'Bearer ' + token.value } });
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
    link.href = url; link.download = `${kind}-${content?'content':'metadata'}.csv`; document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    notice.value = content?'正文 CSV 已生成，仅含最近七天未清理内容。':'元数据 CSV 已生成。';
  } catch (failure) {
    if (failure instanceof DOMException && failure.name === 'AbortError') return;
    if (currentEpoch === epoch && generation === exportGeneration || failure instanceof ApiFailure && failure.status === 401)
      error.value = failure instanceof ApiFailure ? failure.message : content?'未能导出正文，请重试。':'未能导出元数据，请重试。';
  } finally { if (generation === exportGeneration) exporting.value = null; }
}
let lastPeriodic=0;
function visibleRefresh(){if(authenticated.value&&document.visibilityState==='visible'&&!loading.value&&!busy.value&&!exporting.value)void refresh();}
onMounted(() => { document.addEventListener('visibilitychange',visibleRefresh);interval = setInterval(() => { if(eventsConnected.value&&Date.now()-lastPeriodic<15000)return;lastPeriodic=Date.now();if (authenticated.value && !loading.value && !busy.value && !exporting.value && document.visibilityState === 'visible') void refresh(); }, 5000); });
onUnmounted(() => { document.removeEventListener('visibilitychange',visibleRefresh);stopEvents();clearInterval(interval); controller?.abort(); cancelExport(); });
</script>

<template>
  <main v-if="!authenticated" class="login-page">
    <div class="login-intro"><div class="brand"><span class="pet-symbol" aria-hidden="true"><i></i><i></i></span><span>NEXUS<span class="brand-sub">桌面伙伴管理台</span></span></div><div class="pet-illustration" aria-hidden="true"><span class="pet-symbol"><i></i><i></i></span></div></div>
    <form class="login-card" @submit.prevent="login">
      <h1 tabindex="-1">连接你的桌面伙伴</h1><p>使用管理员或独立角色令牌登录。</p>
      <label class="token-label">管理令牌<NInput v-model:value="token" :type="tokenVisible ? 'text' : 'password'" :input-props="{ autocomplete: 'off', spellcheck: false, required: true }" :disabled="loading" /></label>
      <label class="checkbox-label"><input v-model="tokenVisible" type="checkbox" />显示令牌</label>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <NButton type="primary" attr-type="submit" class="primary full-width" :disabled="loading">{{ loading ? '正在连接…' : '进入消息中心' }}</NButton>
      <small>令牌仅保留在当前页面，退出后清除。</small>
    </form>
  </main>
  <ConsoleShell v-else v-model:tab="tab" v-model:selected-device="selectedDevice" :devices="devices" :role="role" :loading="loading" :connected="status?.mqtt === 'ready'" @logout="logout" @guide="showGuide = !showGuide" @refresh="fullRefresh(true)">
      <main id="main-content" class="content">
        <SetupGuide v-if="showGuide" :api="api" :refresh-key="devices.length" @close="showGuide=false" @navigate="tab=$event" />
        <div v-if="error" class="feedback error" role="alert"><span>{{ error }}</span><NButton quaternary attr-type="button" class="text-button" @click="fullRefresh(true)">重试</NButton></div>
        <div v-else-if="notice" class="feedback" role="status"><span>{{ notice }}</span><NButton quaternary attr-type="button" class="text-button" aria-label="关闭提示" @click="notice = ''">×</NButton></div>
        <InboxPanel v-if="tab === 'inbox'" :data="messages" :loading="loading" :failed="readFailed" :read-status="messageReadStatus" :busy="busy || !!exporting" :search="messageSearch" :exporting="exporting === 'messages'" :pending="messageSubmission" :uncertain="messageUncertain" :create="createMessage" :mark-read="markRead" :api="api" :mutate="mutate" @page="messagePage" @filter="filterMessages" @search="searchMessages" @clear="searchMessages(emptySearch(), true)" @export="exportMetadata('messages')" @content-export="exportMetadata('messages',true)" @connectors="tab = 'connectors'" />
        <DevicesPanel v-else-if="tab === 'devices'" :items="selectedDevice?devices.filter(d=>d.deviceId===selectedDevice):devices" :busy="busy" :api="api" :register="register" :enable="enable" :rename="rename" />
        <section v-else-if="tab === 'connectors'"><ConnectorsPanel :items="connectors" :busy="busy" :poll="poll" :import-comment="importComment" /><PullPanel :api="api" :busy="busy" :session="pullSession" :mutate="mutate" @messages="pullMessages" /><SourcesPanel :api="api" :devices="devices" :busy="busy" :session="sourceSession" :mutate="mutate" /></section>
        <DeliveryPanel v-else-if="tab === 'deliveries'" :data="deliveries" :api="api" :busy="busy || !!exporting" :loading="loading" :failed="readFailed" :status="deliveryStatus" :search="deliverySearch" :exporting="exporting === 'deliveries'" :retry="retry" @page="deliveryPage" @filter="filterDeliveries" @search="searchDeliveries" @clear="searchDeliveries(emptySearch(), true)" @export="exportMetadata('deliveries')" @refresh="refresh" />
        <ToolsPanel v-else-if="tab === 'tools'" :api="api" :mutate="mutate" :busy="busy" :pending="toolSubmission" :uncertain="toolUncertain" :create="createTool" />
        <PetPanel v-else-if="tab === 'pet'" :api="api" :mutate="mutate" :busy="busy" />
        <MemoryPanel v-else-if="tab === 'memory'" :api="api" />
        <OverviewPanel v-else-if="tab === 'overview'" :api="api" />
        <AccessPanel v-else-if="tab === 'access' && role === 'admin'" :session="accessSession" :api="api" :busy="busy" :mutate="mutate" />
        <TemplatesPanel v-else-if="tab === 'templates'" :api="api" :busy="busy" :mutate="mutate" :session="templateSession" />
        <RoutingPanel v-else-if="tab === 'routing'" :api="api" :devices="devices" :busy="busy" :mutate="mutate" :session="routingSession" />
        <AutomationPanel v-else :api="api" :mutate="mutate" :busy="busy" :pending="ruleSubmission" :uncertain="ruleUncertain" :create="createRule" :editor="ruleEdit" :begin-edit="beginRuleEdit" :change-edit="changeRuleEdit" :close-edit="closeRuleEdit" :save-edit="saveRuleEdit" :check-edit="checkRuleEdit" :deletion="ruleDeletion" :begin-delete="beginRuleDelete" :close-delete="closeRuleDelete" :delete-rule="deleteRule" />
      </main>
  </ConsoleShell>
</template>
