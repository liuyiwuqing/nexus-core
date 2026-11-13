<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import InboxPanel from './InboxPanel.vue';
import DevicesPanel from './DevicesPanel.vue';
import ConnectorsPanel from './ConnectorsPanel.vue';
import DeliveryPanel from './DeliveryPanel.vue';
import ToolsPanel from './ToolsPanel.vue';
import AutomationPanel from './AutomationPanel.vue';
import PetPanel from './PetPanel.vue';
import MemoryPanel from './MemoryPanel.vue';
import OverviewPanel from './OverviewPanel.vue';
import { errorNames, type Message, type MessageSubmission, type ToolSubmission, type RuleSubmission, type Device, type Connector, type Delivery, type Status, type Page, type Tab } from './types';

const token = ref(''), tokenVisible = ref(false), authenticated = ref(false), loading = ref(false), busy = ref(false);
const error = ref(''), notice = ref(''), tab = ref<Tab>('inbox');
const messages = ref<Page<Message>>({ items: [], total: 0, page: 1, size: 30 });
const deliveries = ref<Page<Delivery>>({ items: [], total: 0, page: 1, size: 30 });
const devices = ref<Device[]>([]), connectors = ref<Connector[]>([]), status = ref<Status | null>(null);
const messageSubmission = ref<MessageSubmission | null>(null), messageUncertain = ref(false);
const toolSubmission = ref<ToolSubmission | null>(null), toolUncertain = ref(false);
const ruleSubmission = ref<RuleSubmission | null>(null), ruleUncertain = ref(false);
const messageReadStatus = ref<Message['readStatus'] | ''>(''), deliveryStatus = ref(''), readFailed = ref(false);
const tabs: { id: Tab; label: string; glyph: string }[] = [{ id: 'inbox', label: '消息', glyph: '✉' }, { id: 'pet', label: '成长', glyph: '✦' }, { id: 'devices', label: '设备', glyph: '◉' }, { id: 'connectors', label: '接入', glyph: '⇄' }, { id: 'deliveries', label: '投递', glyph: '↗' }, { id: 'tools', label: '工具', glyph: '⌘' }, { id: 'automation', label: '自动化', glyph: '◷' }, { id: 'memory', label: '记忆', glyph: '◇' }, { id: 'overview', label: '概览', glyph: '◈' }];
let epoch = 0, readGeneration = 0, controller: AbortController | undefined;
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
  epoch++; readGeneration++; controller?.abort(); authenticated.value = false; token.value = '';
  messages.value = { items: [], total: 0, page: 1, size: 30 }; deliveries.value = { items: [], total: 0, page: 1, size: 30 };
  devices.value = []; connectors.value = []; status.value = null; loading.value = false; notice.value = '';
  messageSubmission.value = null; messageUncertain.value = false;
  toolSubmission.value = null; toolUncertain.value = false; ruleSubmission.value = null; ruleUncertain.value = false;
  messageReadStatus.value = ''; deliveryStatus.value = ''; readFailed.value = false;
}

async function refresh(clearFeedback = false): Promise<boolean> {
  controller?.abort(); controller = new AbortController();
  const requestId = ++readGeneration, currentEpoch = epoch, previousError = error.value;
  loading.value = true;
  try {
    const result = await Promise.all([
      api<Page<Message>>(`messages?page=${messages.value.page}${messageReadStatus.value ? '&readStatus=' + encodeURIComponent(messageReadStatus.value) : ''}`, 'GET', undefined, controller.signal),
      api<{ items: Device[] }>('devices', 'GET', undefined, controller.signal),
      api<{ items: Connector[] }>('connectors', 'GET', undefined, controller.signal),
      api<Page<Delivery>>(`deliveries?page=${deliveries.value.page}${deliveryStatus.value ? '&status=' + encodeURIComponent(deliveryStatus.value) : ''}`, 'GET', undefined, controller.signal),
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
  token.value = token.value.trim(); error.value = ''; notice.value = '';
  if (await refresh()) authenticated.value = true;
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
  if (loading.value || busy.value) return;
  messageReadStatus.value = value; messages.value = { ...messages.value, items: [], total: 0, page: 1 };
  void refresh();
}
function filterDeliveries(value: string) {
  if (loading.value || busy.value) return;
  deliveryStatus.value = value; deliveries.value = { ...deliveries.value, items: [], total: 0, page: 1 };
  void refresh();
}
onMounted(() => { interval = setInterval(() => { if (authenticated.value && !loading.value && !busy.value && document.visibilityState === 'visible') void refresh(); }, 5000); });
onUnmounted(() => { clearInterval(interval); controller?.abort(); });
</script>

<template>
  <main v-if="!authenticated" class="login-page">
    <form class="login-card" @submit.prevent="login">
      <div class="brand"><span class="pet-symbol" aria-hidden="true"><i></i><i></i></span><span>NEXUS<span class="brand-sub">PET CONSOLE</span></span></div>
      <h1>连接你的桌面伙伴</h1><p>使用本机配置中的管理令牌登录。</p>
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
      <nav aria-label="主导航"><button v-for="item in tabs" :key="item.id" :class="{ active: tab === item.id }" :aria-current="tab === item.id ? 'page' : undefined" @click="tab = item.id"><span aria-hidden="true">{{ item.glyph }}</span>{{ item.label }}</button></nav>
      <div class="sidebar-bottom"><span class="local-indicator">本机控制台</span><button class="text-button" @click="logout">退出登录</button></div>
    </aside>
    <div class="workspace">
      <header class="topbar"><span class="connection-status" :class="status?.mqtt === 'ready' ? 'connected' : 'disconnected'"><i></i>{{ status?.mqtt === 'ready' ? '消息连接正常' : '消息连接不可用' }}</span><button class="text-button" :disabled="loading" @click="refresh(true)">{{ loading ? '刷新中…' : '刷新' }}</button></header>
      <div class="content">
        <div v-if="error" class="feedback error" role="alert"><span>{{ error }}</span><button class="text-button" @click="refresh(true)">重试</button></div>
        <div v-else-if="notice" class="feedback" role="status"><span>{{ notice }}</span><button class="text-button" aria-label="关闭提示" @click="notice = ''">×</button></div>
        <InboxPanel v-if="tab === 'inbox'" :data="messages" :loading="loading" :failed="readFailed" :read-status="messageReadStatus" :busy="busy" :pending="messageSubmission" :uncertain="messageUncertain" :create="createMessage" :mark-read="markRead" :api="api" :mutate="mutate" @page="messagePage" @filter="filterMessages" @connectors="tab = 'connectors'" />
        <DevicesPanel v-else-if="tab === 'devices'" :items="devices" :busy="busy" :api="api" :register="register" :enable="enable" :rename="rename" />
        <ConnectorsPanel v-else-if="tab === 'connectors'" :items="connectors" :busy="busy" :poll="poll" :import-comment="importComment" />
        <DeliveryPanel v-else-if="tab === 'deliveries'" :data="deliveries" :api="api" :busy="busy" :loading="loading" :failed="readFailed" :status="deliveryStatus" :retry="retry" @page="deliveryPage" @filter="filterDeliveries" @refresh="refresh" />
        <ToolsPanel v-else-if="tab === 'tools'" :api="api" :mutate="mutate" :busy="busy" :pending="toolSubmission" :uncertain="toolUncertain" :create="createTool" />
        <PetPanel v-else-if="tab === 'pet'" :api="api" :mutate="mutate" :busy="busy" />
        <MemoryPanel v-else-if="tab === 'memory'" :api="api" />
        <OverviewPanel v-else-if="tab === 'overview'" :api="api" />
        <AutomationPanel v-else :api="api" :mutate="mutate" :busy="busy" :pending="ruleSubmission" :uncertain="ruleUncertain" :create="createRule" />
      </div>
    </div>
  </div>
</template>
