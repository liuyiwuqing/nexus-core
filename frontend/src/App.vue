<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import ConsoleField from './ConsoleField.vue';
import ConsoleForm from './ConsoleForm.vue';
import { fieldRule } from './formRules';
import { NButton, NCheckbox, NInput } from 'naive-ui';
import { provide } from 'vue';
import ConsoleShell from './ConsoleShell.vue';
import { onMounted, onUnmounted, ref, watch, computed } from 'vue';
import InboxPanel from './InboxPanel.vue';
import { asyncPanel, prefetch, prefetchTab } from './panels';
import type { RoutingSession, SourceSession, AccessSession, TemplateSession } from './types';
import type {PullSession} from './types';
import { watchConsoleEvents } from './eventStream';
import { ApiFailure, readFailure } from './failure';
import type { Role,ConsoleSession as SessionInfo } from './access';
import { type Message, type MessagePriority, type MessageSubmission, type MessageResendResult, type ResendSubmission, type ToolSubmission, type RuleSubmission, type AutomationRule, type RuleEditInput, type RuleEditSession, type RuleDeletion, type Device, type DevicePage, type Connector, type Delivery, type Status, type Page, type Tab, type ListSearch, type OverviewAlertTarget } from './types';
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
// 顶栏设备选择器：默认清单可能不含搜索选中的设备，单独保留记录，避免整表刷新把选择清空。
const selectedDeviceRecord=ref<Device|null>(null),deviceMatches=ref<Device[]>([]),deviceSearching=ref(false),deviceSearchFailed=ref(false);
let deviceSearchTimer:ReturnType<typeof setTimeout>|undefined,deviceSearchController:AbortController|undefined,deviceSearchGeneration=0;
let stream:AbortController|undefined,reconnect:ReturnType<typeof setTimeout>|undefined,streamGeneration=0;
function stopEvents(){streamGeneration++;stream?.abort();clearTimeout(reconnect);eventsConnected.value=false;}
function startEvents(){stopEvents();const generation=streamGeneration;let attempts=0;const connect=()=>{if(!authenticated.value||generation!==streamGeneration)return;const control=new AbortController();stream=control;void watchConsoleEvents(token.value,control.signal,event=>{if(generation!==streamGeneration)return;if(event==='unauthorized'){logout();return;}if(event==='connected'){eventsConnected.value=true;attempts=0;}if(event==='invalidate'&&document.visibilityState==='visible'&&!loading.value&&!busy.value&&!exporting.value)void refresh(false,invalidateScope());}).catch(()=>{}).finally(()=>{if(generation!==streamGeneration||control.signal.aborted)return;eventsConnected.value=false;reconnect=setTimeout(connect,Math.min(30000,1000*2**Math.min(attempts++,5)));});};connect();}
const token = ref(''), tokenVisible = ref(false), authenticated = ref(false), loading = ref(false), busy = ref(false);
const remember=ref(true),loggingOut=ref(false),restoring=ref(true),restoreFailed=ref(false),csrfToken=ref(''),cookieSession=ref(false);
const signedOutKey='nexus.console.signed-out';
function signedOut(){try{return sessionStorage.getItem(signedOutKey)==='1';}catch{return false;}}
function rememberSignOut(value:boolean){try{if(value)sessionStorage.setItem(signedOutKey,'1');else sessionStorage.removeItem(signedOutKey);}catch{/* Storage can be disabled; credentials are never stored here. */}}
function authHeaders():Record<string,string>{return {...(token.value?{Authorization:'Bearer '+token.value}:{}),...(csrfToken.value?{'X-Nexus-CSRF':csrfToken.value}:{})};}
function useSession(session:SessionInfo,newToken=token.value){
  if(!['admin','operator','viewer'].includes(session.role))throw new Error('Invalid session');
  if(session.persisted&&(!session.csrfToken||session.csrfToken.length<32))throw new Error('Invalid session');
  role.value=session.role;cookieSession.value=!!session.persisted;csrfToken.value=session.csrfToken||'';token.value=session.persisted?'':newToken;
}
const error = ref(''), notice = ref(''), tab = ref<Tab>('inbox');
const messages = ref<Page<Message>>({ items: [], total: 0, page: 1, size: 30 });
const deliveries = ref<Page<Delivery>>({ items: [], total: 0, page: 1, size: 30 });
const devices = ref<Device[]>([]), connectors = ref<Connector[]>([]), status = ref<Status | null>(null);
// 设备页单独分页：外壳下拉仍用上面的完整清单，筛选和翻页只影响设备页本身。
const devicePage = ref<DevicePage>({ items: [], total: 0, matched: 0, page: 1, size: 24, state: '', q: '', sort: 'created', order: 'asc', counts: { online: 0, offline: 0, disabled: 0 } });
const messageSubmission = ref<MessageSubmission | null>(null), messageUncertain = ref(false);
const resendSubmission = ref<ResendSubmission | null>(null), resendUncertain = ref(false);
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
const messageReadStatus = ref<Message['readStatus'] | ''>(''), deliveryStatus = ref(''), deliveryReason = ref(''), readFailed = ref(false);
const emptySearch = (): ListSearch => ({ identity: '', receivedFrom: '', receivedBefore: '' });
const messageSearch = ref<ListSearch>(emptySearch()), deliverySearch = ref<ListSearch>(emptySearch());
const exporting = ref<'messages' | 'deliveries' | null>(null);
let epoch = 0, readGeneration = 0, controller: AbortController | undefined;
let exportGeneration = 0, exportController: AbortController | undefined;
let interval: ReturnType<typeof setInterval> | undefined;
let authChanges:BroadcastChannel|undefined;
function announceSession(type:'login'|'logout'){authChanges?.postMessage({type});}
function replaceSession(session:SessionInfo,newToken:string){useSession(session,newToken);announceSession(cookieSession.value?'login':'logout');}

function discardStaleSession(path:string,value:unknown){
  if(path!=='session'&&path!=='access/admin-token')return;
  const session=value as Partial<SessionInfo>|null;
  // A late Set-Cookie must not resurrect a session after logout. The CSRF proof
  // targets only that obsolete session, never a newer login with another proof.
  if(session?.persisted&&typeof session.csrfToken==='string'&&session.csrfToken.length>=32)
    void fetch('/api/v1/session/logout',{method:'POST',cache:'no-store',credentials:'same-origin',headers:{'X-Nexus-CSRF':session.csrfToken}}).catch(()=>{});
}
async function api<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  const currentEpoch=epoch;
  const response = await fetch('/api/v1/' + path, { method, signal, cache: 'no-store', credentials:'same-origin',
    headers: { ...authHeaders(), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if(currentEpoch!==epoch){if(response.ok&&(path==='session'||path==='access/admin-token'))void response.json().then(value=>discardStaleSession(path,value)).catch(()=>{});throw new DOMException('Stale session','AbortError');}
  const currentRole=response.headers.get('X-Nexus-Role');if(currentRole&&['admin','operator','viewer'].includes(currentRole))role.value=currentRole as Role;
  if (!response.ok) {
    const problem = await response.json().catch(() => ({ error: '' })) as { error?: string };
    if(currentEpoch!==epoch)throw new DOMException('Stale session','AbortError');
    if (response.status === 401) logout();
    throw new ApiFailure(response.status, problem.error || '');
  }
  const content = await response.text();
  if(currentEpoch!==epoch){try{discardStaleSession(path,JSON.parse(content));}catch{/* No successful session payload. */}throw new DOMException('Stale session','AbortError');}
  return (content ? JSON.parse(content) : undefined) as T;
}

function logout() {
  rememberSignOut(true);csrfToken.value='';cookieSession.value=false;restoring.value=false;restoreFailed.value=false;busy.value=false;
  stopEvents();showGuide.value=false;selectedDevice.value='';selectedDeviceRecord.value=null;clearDeviceSearch();
  cancelExport();
  epoch++; readGeneration++; controller?.abort(); authenticated.value = false; token.value = '';
  messages.value = { items: [], total: 0, page: 1, size: 30 }; deliveries.value = { items: [], total: 0, page: 1, size: 30 };
  devices.value = []; connectors.value = []; status.value = null; loading.value = false; notice.value = '';
  devicePage.value = { items: [], total: 0, matched: 0, page: 1, size: 24, state: '', q: '', sort: 'created', order: 'asc', counts: { online: 0, offline: 0, disabled: 0 } };
  messageSubmission.value = null; messageUncertain.value = false; resendSubmission.value = null; resendUncertain.value = false;
  toolSubmission.value = null; toolUncertain.value = false; ruleSubmission.value = null; ruleUncertain.value = false;
  ruleEdit.value = null;
  ruleDeletion.value = null;
  routingSession.value=newRoutingSession();
  sourceSession.value={pending:null,credentials:null};pullSession.value={pending:null,uncertainPoll:null};accessSession.value={issued:'',pendingIssue:false};templateSession.value={id:'',version:0,draft:null,pending:null,uncertain:false};role.value='viewer';
  messageReadStatus.value = ''; deliveryStatus.value = ''; deliveryReason.value = ''; readFailed.value = false;
  messageSearch.value = emptySearch(); deliverySearch.value = emptySearch();
}

async function signOut(){
  if(loggingOut.value)return;
  const headers=authHeaders();announceSession('logout');logout();error.value='';loggingOut.value=true;
  const currentEpoch=epoch;
  try{
    const response=await fetch('/api/v1/session/logout',{method:'POST',cache:'no-store',credentials:'same-origin',headers,signal:AbortSignal.timeout(10000)});
    if(!response.ok&&response.status!==401)throw new Error('Logout unconfirmed');
  }catch{if(currentEpoch===epoch)error.value='退出结果未确认，请连接恢复后重新登录并退出。';}
  finally{loggingOut.value=false;}
}
async function restoreSession(){
  if(signedOut()){restoring.value=false;return;}
  const currentEpoch=epoch;restoreFailed.value=false;error.value='';
  try{
    const response=await fetch('/api/v1/session',{cache:'no-store',credentials:'same-origin'});
    if(currentEpoch!==epoch)return;
    if(response.status===401||response.status===404)return;
    if(!response.ok){const problem=await response.json().catch(()=>({error:''})) as {error?:string};throw new ApiFailure(response.status,problem.error||'');}
    const session=await response.json() as SessionInfo;
    if(currentEpoch!==epoch||!session.persisted)return;
    useSession(session);authenticated.value=true;
    await refresh(false,allResources);if(currentEpoch===epoch&&authenticated.value)startEvents();
  }catch(failure){if(currentEpoch===epoch){restoreFailed.value=true;error.value=readFailure(failure,'暂时无法恢复登录，请重试。');}}
  finally{if(currentEpoch===epoch)restoring.value=false;}
}
function querySuffix(kind: 'messages' | 'deliveries'): string {
  const search = kind === 'messages' ? messageSearch.value : deliverySearch.value;
  const state = kind === 'messages' ? messageReadStatus.value : deliveryStatus.value;
  const query = new URLSearchParams();
  // 「待接收」是 pending+sent 的聚合，服务端用 waiting=true 表达，不能塞进单值 status。
  if (kind === 'deliveries' && state === 'waiting') query.set('waiting', 'true');
  else if (state) query.set(kind === 'messages' ? 'readStatus' : 'status', state);
  if (search.identity) query.set(kind === 'messages' ? 'source' : 'deviceId', search.identity);
  // 最近错误只在投递页存在：做成同一条 querySuffix，列表与元数据导出才会筛到同一批。
  if (kind === 'deliveries' && deliveryReason.value) query.set('lastError', deliveryReason.value);
  if (search.receivedFrom) query.set('receivedFrom', new Date(search.receivedFrom).toISOString());
  if (search.receivedBefore) query.set('receivedBefore', new Date(search.receivedBefore).toISOString());
  return query.size ? '&' + query.toString() : '';
}

type Resource = 'messages' | 'deliveries' | 'devices' | 'devicePage' | 'connectors' | 'status';
const allResources: readonly Resource[] = ['messages', 'devices', 'devicePage', 'connectors', 'deliveries', 'status'];
// 每个标签页实际渲染的集合。轮询只取当前页需要的接口，其余集合沿用上一次的值；
// 设备页轮询读分页结果，完整清单只在登录、主动刷新与失效事件时补齐，供外壳下拉使用。
const tabResources: Record<Tab, readonly Resource[]> = {
  inbox: ['messages', 'status'],
  deliveries: ['deliveries', 'status'],
  devices: ['devicePage', 'status'],
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

// 设备页的筛选条件由这里统一编码；外壳下拉仍读不带参数的完整清单。
function deviceQuery(page: number) {
  const params = new URLSearchParams({ page: String(page), size: String(devicePage.value.size) });
  if (devicePage.value.state) params.set('state', devicePage.value.state);
  if (devicePage.value.q) params.set('q', devicePage.value.q);
  params.set('sort', devicePage.value.sort);
  params.set('order', devicePage.value.order);
  return params.toString();
}

async function refresh(clearFeedback = false, resources?: readonly Resource[]): Promise<boolean> {
  controller?.abort(); controller = new AbortController();
  const requestId = ++readGeneration, currentEpoch = epoch, previousError = error.value;
  const need = resources ?? tabResources[tab.value], signal = controller.signal;
  // 纯状态探测不改变任何可见集合，不占用 loading，避免顶栏每隔几秒闪一次"刷新中"。
  const track = need.some(resource => resource !== 'status');
  if (track) loading.value = true;
  try {
    const [messagePage, deviceList, connectorList, deliveryPage, devicePageResult, liveStatus] = await Promise.all([
      need.includes('messages') ? api<Page<Message>>(`messages?page=${messages.value.page}${querySuffix('messages')}`, 'GET', undefined, signal) : undefined,
      need.includes('devices') ? api<{ items: Device[] }>('devices', 'GET', undefined, signal) : undefined,
      need.includes('connectors') ? api<{ items: Connector[] }>('connectors', 'GET', undefined, signal) : undefined,
      need.includes('deliveries') ? api<Page<Delivery>>(`deliveries?page=${deliveries.value.page}${querySuffix('deliveries')}`, 'GET', undefined, signal) : undefined,
      need.includes('devicePage') ? api<DevicePage>(`devices?${deviceQuery(devicePage.value.page)}`, 'GET', undefined, signal) : undefined,
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
    if (devicePageResult) {
      const size = devicePageResult.size || devicePage.value.size;
      const matched = devicePageResult.matched ?? devicePageResult.items.length;
      const page = devicePageResult.page ?? devicePage.value.page;
      const deviceLast = Math.max(1, Math.ceil(matched / size));
      if (page > deviceLast) { devicePage.value.page = deviceLast; repaged = true; }
      else devicePage.value = {
        ...devicePage.value, items: devicePageResult.items, page, size, matched,
        total: devicePageResult.total ?? devicePageResult.items.length,
        counts: devicePageResult.counts ?? {
          online: devicePageResult.items.filter(device => device.online).length,
          offline: devicePageResult.items.filter(device => device.enabled && !device.online).length,
          disabled: devicePageResult.items.filter(device => !device.enabled).length,
        },
      };
    }
    if (repaged) return await refresh(clearFeedback, resources);
    if (deviceList) {
      devices.value = deviceList.items;
      const current = devices.value.find(d => d.deviceId === selectedDevice.value);
      if (current) selectedDeviceRecord.value = current;
      // 搜索选中的设备可能不在默认前 100 台；有记录就保留，只有既不在清单也没有记录时才清空。
      else if (selectedDevice.value && selectedDeviceRecord.value?.deviceId !== selectedDevice.value) selectedDevice.value = '';
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
  if(loading.value||loggingOut.value)return;
  const currentEpoch=epoch;loading.value=true;
  token.value = token.value.trim(); error.value = ''; notice.value = '';
  try {
    const session=await api<SessionInfo>('session','POST',{remember:remember.value});
    if(!['admin','operator','viewer'].includes(session.role))throw new Error('Invalid session');
    if(currentEpoch!==epoch)return;
    useSession(session);rememberSignOut(false);announceSession(cookieSession.value?'login':'logout');
    const ready=await refresh(false,allResources);
    if(currentEpoch===epoch&&(ready||cookieSession.value)){authenticated.value=true;showGuide.value=ready&&devices.value.length===0;startEvents();}
  } catch(failure){if(currentEpoch===epoch||failure instanceof ApiFailure&&failure.status===401)error.value=readFailure(failure,'登录失败：请确认 CORE 正在运行。');}
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

async function createMessage(title: string, content: string, priority: MessagePriority, devices: string[], templateId: string | null): Promise<string | null> {
  if (busy.value) return null;
  const currentEpoch = epoch;
  // HTTP 结果未知时，重试必须保持同一逻辑提交；导航不能丢掉去重身份、目标设备或模板。
  const submission = messageSubmission.value ?? { eventId: crypto.randomUUID(), title, content, priority, devices: [...devices], templateId };
  messageSubmission.value = submission;
  let acceptedId = '';
  const done = await mutate(async () => {
    try {
      const { devices: targets, templateId: template, ...message } = submission;
      const accepted = await api<{ messageId: string }>('messages', 'POST', {
        connectorId: 'manual', source: 'manual', eventType: 'manual.test', ...message,
        ...(targets.length ? { devices: targets } : {}),
        ...(template ? { templateId: template } : {}),
      });
      if (!accepted || typeof accepted.messageId !== 'string' || !accepted.messageId)
        throw new Error('Invalid message acknowledgment');
      acceptedId = accepted.messageId;
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
  return done ? acceptedId : null;
}

async function resendMessage(messageId: string): Promise<MessageResendResult | null> {
  if (busy.value) return null;
  if (resendUncertain.value && resendSubmission.value && resendSubmission.value.messageId !== messageId) {
    error.value = '上一笔重发结果尚未确认；请先在原消息上重试，再重发其他消息。';
    return null;
  }
  const currentEpoch = epoch;
  const submission = resendUncertain.value && resendSubmission.value?.messageId === messageId
    ? resendSubmission.value
    : { messageId, eventId: crypto.randomUUID() };
  resendSubmission.value = submission;
  let result: MessageResendResult | null = null;
  const done = await mutate(async () => {
    try {
      const accepted = await api<MessageResendResult>(`messages/${messageId}/resend`, 'POST', { eventId: submission.eventId });
      if (!accepted || typeof accepted.messageId !== 'string' || !accepted.messageId ||
          typeof accepted.queued !== 'number' || typeof accepted.skipped !== 'number')
        throw new Error('Invalid resend acknowledgment');
      result = { messageId: accepted.messageId, queued: accepted.queued, skipped: accepted.skipped };
      if (currentEpoch === epoch) { resendSubmission.value = null; resendUncertain.value = false; }
    } catch (failure) {
      if (currentEpoch === epoch) {
        // 与新建消息相同：408/5xx/连接失败可能已落库，重试必须沿用同一 eventId。
        const rejected = failure instanceof ApiFailure && failure.status >= 400 && failure.status < 500 && failure.status !== 408;
        if (rejected) resendSubmission.value = null;
        resendUncertain.value = !rejected;
      }
      throw failure;
    }
  }, '重发已创建新的测试消息。');
  return done ? result : null;
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
const register = async (deviceId: string, name: string) => {
  const ok = await mutate(() => api('devices', 'POST', { deviceId, name }), '设备已登记。');
  // 当前页只保留筛选后的分页结果，外壳下拉要立刻能选到新设备。
  if (ok) await refresh(false, ['devices']);
  return ok;
};
const rename = (id: string, name: string, expectedName: string) => mutate(async () => {
  const updated = await api<{ deviceId: string; name: string }>(`devices/${encodeURIComponent(id)}/name`, 'PATCH', { name, expectedName });
  devices.value = devices.value.map(device => device.deviceId === updated.deviceId ? { ...device, name: updated.name } : device);
  if (selectedDeviceRecord.value?.deviceId === updated.deviceId) selectedDeviceRecord.value = { ...selectedDeviceRecord.value, name: updated.name };
  // 设备页读的是分页结果，改名后立刻回写，冲突提示才不会把刚保存的名字当成他人修改。
  devicePage.value = { ...devicePage.value, items: devicePage.value.items.map(device => device.deviceId === updated.deviceId ? { ...device, name: updated.name } : device) };
}, '设备名称已更新。');
const enable = (id: string, enabled: boolean) => mutate(() => api(`devices/${id}/enabled`, 'PUT', { enabled }), enabled ? '已启用提醒。' : '已停用提醒。');
const removeDevice = async (id: string) => {
  const ok = await mutate(async () => {
    await api(`devices/${encodeURIComponent(id)}`, 'DELETE');
    // 删除后先清掉本地视图与壳层选择，避免随后整表刷新把已删除设备重新当成当前选择。
    devices.value = devices.value.filter(device => device.deviceId !== id);
    deviceMatches.value = deviceMatches.value.filter(device => device.deviceId !== id);
    devicePage.value = { ...devicePage.value, items: devicePage.value.items.filter(device => device.deviceId !== id) };
    if (selectedDevice.value === id) {
      selectedDevice.value = ''; selectedDeviceRecord.value = null; clearDeviceSearch();
    } else if (selectedDeviceRecord.value?.deviceId === id) selectedDeviceRecord.value = null;
  }, '设备已删除。');
  return ok;
};
function pullMessages(source:string){tab.value='inbox';void searchMessages({...emptySearch(),identity:source});}
/** 设备卡片的「待接收」钻取：按该设备的 pending+sent 过滤，卡片计数与列表口径一致。 */
function drillDeviceDeliveries(deviceId: string) {
  deliverySearch.value = { ...emptySearch(), identity: deviceId };
  deliveryStatus.value = 'waiting';
  deliveryReason.value = '';
  deliveries.value = { ...deliveries.value, items: [], total: 0, page: 1 };
  if (tab.value === 'deliveries') void refresh(); else tab.value = 'deliveries';
}
/**
 * 告警钻取：先备好筛选再切页签，切页条的 watch 会带着新条件读一次，不会先读一遍全量。
 * 上一次的搜索条件必须清掉，否则"查看等待投递"会被旧筛选悄悄收窄成别的结果集。
 */
function drillAlert(target: OverviewAlertTarget) {
  if (target.tab === 'devices') {
    // 壳层的单设备筛选与旧搜索会和状态筛选叠加出空列表；钻取要看到的是这一类，不是某台或某个名字。
    selectedDevice.value = ''; selectedDeviceRecord.value = null; clearDeviceSearch();
    devicePage.value = { ...devicePage.value, state: target.status || '', q: '', items: [], page: 1 };
    tab.value = 'devices'; return;
  }
  deliverySearch.value = emptySearch();
  deliveryStatus.value = target.status;
  deliveryReason.value = target.error ?? '';
  deliveries.value = { ...deliveries.value, items: [], total: 0, page: 1 };
  if (tab.value === 'deliveries') void refresh(); else tab.value = 'deliveries';
}
function cancelDeviceSearch() {
  deviceSearchGeneration++;
  clearTimeout(deviceSearchTimer);
  deviceSearchController?.abort(); deviceSearchController = undefined;
  deviceSearching.value = false; deviceSearchFailed.value = false;
}
function clearDeviceSearch() { cancelDeviceSearch(); deviceMatches.value = []; }
// 顶栏输入按服务端搜索：300ms 防抖，旧查询的响应由代数丢弃；空查询恢复默认清单。
// 清空查询只取消请求、保留上一批结果，选中时才能从结果里取到设备记录。
function searchDeviceMatches(query: string) {
  cancelDeviceSearch();
  const q = query.trim();
  if (!q) return;
  const generation = deviceSearchGeneration;
  deviceMatches.value = []; deviceSearching.value = true;
  deviceSearchTimer = setTimeout(() => {
    const current = new AbortController(); deviceSearchController = current;
    void api<{ items: Device[] }>(`devices?size=50&sort=name&order=asc&q=${encodeURIComponent(q)}`, 'GET', undefined, current.signal)
      .then(result => {
        if (generation !== deviceSearchGeneration) return;
        deviceMatches.value = result.items; deviceSearching.value = false;
      })
      .catch(failure => {
        if (generation !== deviceSearchGeneration) return;
        if (failure instanceof DOMException && failure.name === 'AbortError') return;
        deviceMatches.value = []; deviceSearching.value = false; deviceSearchFailed.value = true;
      });
  }, 300);
}
// 从壳层选具体设备时回到"全部"状态：单设备与状态筛选叠加只会得到空列表。
function pickDevice(id: string) {
  selectedDevice.value = id;
  selectedDeviceRecord.value = id
    ? devices.value.find(device => device.deviceId === id) ?? deviceMatches.value.find(device => device.deviceId === id) ?? null
    : null;
  clearDeviceSearch();
  const reset = devicePage.value.state !== '' || devicePage.value.q !== '' || devicePage.value.page !== 1;
  devicePage.value = { ...devicePage.value, state: '', q: '', page: 1 };
  if (reset) void refresh();
}
// 单设备视图直接取完整清单或选择时的记录，避免它不在默认清单/当前页时卡片消失。
const visibleDevices = computed(() => {
  if (!selectedDevice.value) return devicePage.value.items;
  const current = devices.value.find(device => device.deviceId === selectedDevice.value);
  if (current) return [current];
  return selectedDeviceRecord.value?.deviceId === selectedDevice.value ? [selectedDeviceRecord.value] : [];
});
const selectedDeviceName = computed(() => {
  if (!selectedDevice.value) return '';
  const current = devices.value.find(device => device.deviceId === selectedDevice.value) ?? selectedDeviceRecord.value;
  return current?.deviceId === selectedDevice.value ? current.name : '';
});
const deviceStats = computed(() => {
  if (selectedDevice.value) {
    const online = visibleDevices.value.filter(device => device.online).length;
    return { total: visibleDevices.value.length, matched: visibleDevices.value.length, online };
  }
  const items = devicePage.value.items;
  return {
    total: devicePage.value.total ?? items.length,
    matched: devicePage.value.matched ?? items.length,
    online: devicePage.value.counts?.online ?? items.filter(device => device.online).length,
  };
});
// 搜索、筛选、排序或页大小变化都回到第一页，避免停在一个已不属于当前条件的深页。
function refilterDevices(change: Partial<DevicePage>) {
  if (busy.value || exporting.value) return;
  devicePage.value = { ...devicePage.value, ...change, items: [], page: 1 };
  void refresh();
}
function filterDevices(value: string) { refilterDevices({ state: value }); }
function searchDevices(value: string) { refilterDevices({ q: value }); }
function sortDevices(sort: string, order: string) { refilterDevices({ sort, order }); }
function deviceSizeChange(size: number) { refilterDevices({ size }); }
function devicePageChange(page: number) {
  if (busy.value || exporting.value) return;
  devicePage.value.page = page;
  void refresh();
}
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
  deliveryStatus.value = value;
  // 换状态时清掉原因：原因筛选可以在任意状态下重新选择，但保留一个与当前状态不相干的值更容易被误读。
  if (value !== 'failed') deliveryReason.value = '';
  deliveries.value = { ...deliveries.value, items: [], total: 0, page: 1 };
  void refresh();
}
function filterReason(value: string) {
  if (busy.value || exporting.value) return;
  deliveryReason.value = value;
  deliveries.value = { ...deliveries.value, items: [], total: 0, page: 1 };
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
  if (clear) { deliveryStatus.value = ''; deliveryReason.value = ''; }
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
    const response = await fetch(content?`/api/v1/messages/content-export?confirm=EXPORT_MESSAGE_CONTENT${querySuffix('messages')}`:`/api/v1/${kind}/export${query}`, { signal: exportController.signal, cache: 'no-store', credentials:'same-origin',headers: authHeaders() });
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
onMounted(() => {
  if(typeof BroadcastChannel!=='undefined'){authChanges=new BroadcastChannel('nexus.console.auth');authChanges.onmessage=event=>{if(event.data?.type!=='login'&&event.data?.type!=='logout')return;logout();if(event.data.type==='login'){rememberSignOut(false);restoring.value=true;void restoreSession();}};}
  void restoreSession();document.addEventListener('visibilitychange',visibleRefresh);interval = setInterval(() => { if(eventsConnected.value&&Date.now()-lastPeriodic<15000)return;lastPeriodic=Date.now();if (authenticated.value && !loading.value && !busy.value && !exporting.value && document.visibilityState === 'visible') void refresh(); }, 5000); });
onUnmounted(() => { authChanges?.close(); document.removeEventListener('visibilitychange',visibleRefresh);stopEvents();clearInterval(interval); controller?.abort(); cancelExport(); });
</script>

<template>
  <main v-if="restoring && !authenticated" class="login-page"><ConsoleState kind="loading">正在恢复登录…</ConsoleState></main>
  <main v-else-if="!authenticated" class="login-page">
    <div class="login-intro">
      <div class="brand">
        <span class="pet-symbol" aria-hidden="true">
          <i></i>
          <i></i>
        </span>
        <span>
          NEXUS
          <span class="brand-sub">桌面伙伴管理台</span>
        </span>
      </div>
      <div class="pet-illustration" aria-hidden="true">
        <span class="pet-symbol">
          <i></i>
          <i></i>
        </span>
      </div>
    </div>
    <ConsoleForm class="login-card" @submit.prevent="login">
      <h1 tabindex="-1">连接你的桌面伙伴</h1>
      <p>使用管理员或独立角色令牌登录。</p>
      <ConsoleField
        class="token-label"
        path="field-1"
        :rule="fieldRule(() => token, { required: true })"
        :label-props="{ id: 'app-field-1' + '-label', for: 'app-field-1' }"
      >
        <template #label>管理令牌</template>
        <NInput
          v-model:value="token"
          :type="tokenVisible ? 'text' : 'password'"
          :input-props="{ autocomplete: 'off', spellcheck: false, required: true, id: 'app-field-1', 'aria-label': '管理令牌' }"
          :disabled="loading"
        />
      </ConsoleField>
      <div class="form-actions"><NCheckbox class="checkbox-label" v-model:checked="remember" :disabled="loading">保持登录 8 小时</NCheckbox><NCheckbox class="checkbox-label" v-model:checked="tokenVisible">显示令牌</NCheckbox></div>
      <ConsoleState v-if="error" kind="error" compact class="inline-error">{{ error }}</ConsoleState>
      <NButton type="primary" attr-type="submit" class="primary full-width" :disabled="loading || loggingOut">
        {{ loggingOut ? '正在退出…' : loading ? '正在连接…' : '进入消息中心' }}
      </NButton>
      <small>未提交内容仅保留在当前页面。</small>
      <NButton v-if="restoreFailed" attr-type="button" class="text-button" :disabled="loading" @click="restoring=true;restoreSession()">重试恢复登录</NButton>
    </ConsoleForm>
  </main>
  <ConsoleShell
    v-else
    v-model:tab="tab"
    :selected-device="selectedDevice"
    :selected-device-name="selectedDeviceName"
    @update:selected-device="pickDevice"
    :devices="devices"
    :device-matches="deviceMatches"
    :device-search-loading="deviceSearching"
    :device-search-failed="deviceSearchFailed"
    @search-devices="searchDeviceMatches"
    :role="role"
    :loading="loading"
    :connected="status?.mqtt === 'ready'"
    @logout="signOut"
    @guide="showGuide = !showGuide"
    @refresh="fullRefresh(true)"
  >
    <main id="main-content" class="content" tabindex="-1">
      <SetupGuide v-if="showGuide" :api="api" :refresh-key="devices.length" @close="showGuide = false" @navigate="tab = $event" />
      <ConsoleState v-if="error" kind="error" compact class="global-feedback">
        {{ error }}
        <template #actions><NButton attr-type="button" :disabled="loading" @click="fullRefresh(true)">重试</NButton></template>
      </ConsoleState>
      <ConsoleState v-else-if="notice" kind="success" compact class="global-feedback">
        {{ notice }}
        <template #actions><NButton attr-type="button" aria-label="关闭提示" @click="notice = ''">×</NButton></template>
      </ConsoleState>
      <InboxPanel
        v-if="tab === 'inbox'"
        :data="messages"
        :loading="loading"
        :failed="readFailed"
        :read-status="messageReadStatus"
        :busy="busy || !!exporting"
        :search="messageSearch"
        :exporting="exporting === 'messages'"
        :pending="messageSubmission"
        :uncertain="messageUncertain"
        :resend-pending="resendSubmission"
        :resend-uncertain="resendUncertain"
        :devices="devices"
        :create="createMessage"
        :resend="resendMessage"
        :mark-read="markRead"
        :api="api"
        :mutate="mutate"
        @page="messagePage"
        @filter="filterMessages"
        @search="searchMessages"
        @clear="searchMessages(emptySearch(), true)"
        @export="exportMetadata('messages')"
        @content-export="exportMetadata('messages', true)"
        @connectors="tab = 'connectors'"
      />
      <DevicesPanel
        v-else-if="tab === 'devices'"
        :items="visibleDevices"
        :total="deviceStats.total"
        :matched="deviceStats.matched"
        :online-count="deviceStats.online"
        :page="devicePage.page"
        :size="devicePage.size"
        :state="devicePage.state"
        :q="devicePage.q"
        :sort="devicePage.sort"
        :order="devicePage.order"
        :busy="busy"
        :loading="loading"
        :failed="readFailed"
        :api="api"
        :register="register"
        :enable="enable"
        :rename="rename"
        :remove="removeDevice"
        @filter-state="filterDevices"
        @search="searchDevices"
        @sort="sortDevices"
        @size="deviceSizeChange"
        @page="devicePageChange"
        @deliveries="drillDeviceDeliveries"
        @queue-changed="fullRefresh(true)"
      />
      <section v-else-if="tab === 'connectors'">
        <ConnectorsPanel :items="connectors" :busy="busy" :loading="loading" :failed="readFailed" :poll="poll" :import-comment="importComment" />
        <PullPanel :api="api" :busy="busy" :session="pullSession" :mutate="mutate" @messages="pullMessages" />
        <SourcesPanel :api="api" :devices="devices" :busy="busy" :session="sourceSession" :mutate="mutate" />
      </section>
      <DeliveryPanel
        v-else-if="tab === 'deliveries'"
        :data="deliveries"
        :api="api"
        :busy="busy || !!exporting"
        :loading="loading"
        :failed="readFailed"
        :status="deliveryStatus"
        :reason="deliveryReason"
        :search="deliverySearch"
        :exporting="exporting === 'deliveries'"
        :retry="retry"
        @page="deliveryPage"
        @filter="filterDeliveries"
        @reason="filterReason"
        @search="searchDeliveries"
        @clear="searchDeliveries(emptySearch(), true)"
        @export="exportMetadata('deliveries')"
        @refresh="refresh"
        @messages="tab = 'inbox'"
      />
      <ToolsPanel
        v-else-if="tab === 'tools'"
        :api="api"
        :mutate="mutate"
        :busy="busy"
        :pending="toolSubmission"
        :uncertain="toolUncertain"
        :create="createTool"
      />
      <PetPanel v-else-if="tab === 'pet'" :api="api" :mutate="mutate" :busy="busy" :selected-device="selectedDevice" :devices="devices" :device-matches="deviceMatches" :selected-device-name="selectedDeviceName" :device-searching="deviceSearching" :device-search-failed="deviceSearchFailed" @select-device="pickDevice" @search-devices="searchDeviceMatches" />
      <MemoryPanel v-else-if="tab === 'memory'" :api="api" />
      <OverviewPanel v-else-if="tab === 'overview'" :api="api" @drill="drillAlert" />
      <AccessPanel v-else-if="tab === 'access' && role === 'admin'" :session="accessSession" :api="api" :busy="busy" :mutate="mutate" :replace-session="replaceSession" />
      <TemplatesPanel v-else-if="tab === 'templates'" :api="api" :busy="busy" :mutate="mutate" :session="templateSession" />
      <RoutingPanel v-else-if="tab === 'routing'" :api="api" :devices="devices" :busy="busy" :mutate="mutate" :session="routingSession" />
      <AutomationPanel
        v-else
        :api="api"
        :mutate="mutate"
        :busy="busy"
        :pending="ruleSubmission"
        :uncertain="ruleUncertain"
        :create="createRule"
        :editor="ruleEdit"
        :begin-edit="beginRuleEdit"
        :change-edit="changeRuleEdit"
        :close-edit="closeRuleEdit"
        :save-edit="saveRuleEdit"
        :check-edit="checkRuleEdit"
        :deletion="ruleDeletion"
        :begin-delete="beginRuleDelete"
        :close-delete="closeRuleDelete"
        :delete-rule="deleteRule"
      />
    </main>
  </ConsoleShell>
</template>
