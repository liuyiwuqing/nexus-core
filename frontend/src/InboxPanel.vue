<script setup lang="ts">
import { useAccess } from './access';
const { admin, operate } = useAccess();
import { computed, ref, watch } from 'vue';
import { formatTime, type Api, type Message, type MessageSubmission, type Page, type ListSearch } from './types';
import AnalysisPanel from './AnalysisPanel.vue';
import ListSearchForm from './ListSearchForm.vue';

const props = defineProps<{
  data: Page<Message>; loading: boolean; busy: boolean; failed: boolean; readStatus: Message['readStatus'] | '';
  search: ListSearch; exporting: boolean;
  pending: MessageSubmission | null; uncertain: boolean;
  create: (title: string, content: string) => Promise<boolean>;
  markRead: (id: string) => Promise<boolean>;
  api: Api; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean>;
}>();
const emit = defineEmits<{ page: [page: number]; filter: [value: Message['readStatus'] | '']; search: [value: ListSearch]; clear: []; export: []; contentExport: []; connectors: [] }>();
function filterChanged(event: Event) { emit('filter', (event.target as HTMLSelectElement).value as Message['readStatus'] | ''); }
const selectedId = ref('');
const selected = computed(() => props.data.items.find(message => message.messageId === selectedId.value));
const compose = ref(false);
const bodyExport=ref(false),bodyConfirm=ref(false);
const title = ref('');
const content = ref('');
watch(() => props.pending, submission => {
  if (submission) { title.value = submission.title; content.value = submission.content; compose.value = true; }
}, { immediate: true });
async function send() {
  if (await props.create(title.value, content.value)) {
    title.value = ''; content.value = ''; compose.value = false;
  }
}
</script>

<template>
  <section>
    <div class="section-heading"><div><h1>消息</h1><p>{{ loading ? '正在加载…' : failed ? '未能读取消息' : `${data.total} 条消息` }}</p></div>
      <button v-if="operate" class="secondary" :disabled="!!pending" @click="compose = !compose" :aria-expanded="compose">{{ compose ? '收起' : '发送测试消息' }}</button>
    </div>
    <ListSearchForm kind="messages" :filters="search" :status-active="!!readStatus" :disabled="loading || busy" :failed="failed" :total="data.total" :exporting="exporting" @apply="emit('search', $event)" @clear="emit('clear')" @export="emit('export')"><label>消息状态<select aria-label="消息状态" :value="readStatus" :disabled="loading || busy" @change="filterChanged"><option value="">全部</option><option value="unread">未读</option><option value="read">已读</option></select></label><template #extra-actions="{blocked}"><button v-if="admin" type="button" class="text-button" :disabled="blocked" @click="bodyExport=!bodyExport;bodyConfirm=false">导出正文…</button><div v-if="admin&&bodyExport" class="inline-form"><p>仅导出当前已应用筛选中，最近七天未清理的正文、标题、摘要、发送者和原始链接；最多 1000 条。文件只在本次下载生成。</p><label class="checkbox-label"><input v-model="bodyConfirm" type="checkbox" :disabled="blocked">确认下载包含消息正文的文件</label><button type="button" class="primary spaced" :disabled="blocked||!bodyConfirm" @click="emit('contentExport');bodyConfirm=false">确认导出正文</button></div></template></ListSearchForm>
    <form v-if="compose" class="inline-form" @submit.prevent="send">
      <label>标题<input v-model="title" maxlength="160" required :disabled="busy" :readonly="!!pending" /></label>
      <label>内容<textarea v-model="content" maxlength="8192" rows="3" required :disabled="busy" :readonly="!!pending"></textarea></label>
      <p v-if="uncertain" class="inline-error" role="status">发送结果尚未确认。请重试同一消息，确认前无法修改内容。</p>
      <div class="form-footer"><span>发送到所有已启用的设备。</span><button class="primary" :disabled="busy">{{ busy ? '正在发送…' : uncertain ? '重试同一消息' : '发送' }}</button></div>
    </form>
    <div v-if="loading && !data.items.length" class="empty-state" role="status">正在加载消息…</div>
    <div v-else-if="failed" class="empty-state"><h2>消息读取失败</h2></div>
    <div v-else-if="!data.items.length && (readStatus || Object.values(search).some(Boolean))" class="empty-state"><h2>没有符合条件的消息</h2></div>
    <div v-else-if="!data.items.length" class="empty-state">
      <div class="empty-envelope" aria-hidden="true">✉</div><h2>消息会从这里开始</h2>
      <p>接入 Halo 后，新的公开评论会自动汇入。</p><button class="secondary" @click="emit('connectors')">查看接入</button>
    </div>
    <div v-else class="inbox-layout" :class="{ 'has-selection': !!selected }" :aria-busy="loading">
      <div class="message-list" aria-label="消息列表">
        <button v-for="message in data.items" :key="message.messageId" class="message-row"
          :class="{ active: selectedId === message.messageId, unread: message.readStatus === 'unread' }"
          :aria-pressed="selectedId === message.messageId" @click="selectedId = message.messageId">
          <span class="message-meta"><span>{{ message.source === 'halo' ? 'Halo' : message.source === 'manual' ? '本机' : message.source }}</span><time :datetime="message.receivedAt">{{ formatTime(message.receivedAt) }}</time></span>
          <span class="message-title"><i v-if="message.readStatus === 'unread'" class="unread-dot" aria-label="未读"></i>{{ message.contentPurgedAt ? '内容已按保留期限清除' : message.title }}</span>
          <span class="message-preview">{{ message.summary }}</span>
        </button>
        <div class="pagination">
          <button :disabled="data.page <= 1 || loading || busy" @click="emit('page', data.page - 1)">上一页</button>
          <span>{{ data.page }} / {{ Math.max(1, Math.ceil(data.total / data.size)) }}</span>
          <button :disabled="data.page * data.size >= data.total || loading || busy" @click="emit('page', data.page + 1)">下一页</button>
        </div>
      </div>
      <article v-if="selected" class="message-detail">
        <button class="mobile-back text-button" @click="selectedId = ''">← 返回消息</button>
        <div class="detail-top"><span class="eyebrow">{{ selected.source === 'halo' ? 'HALO' : selected.source === 'manual' ? '本机消息' : selected.source }}</span><span v-if="selected.priority === 'high' || selected.priority === 'critical'" class="badge warning">重要</span></div>
        <h2>{{ selected.contentPurgedAt ? '内容已按保留期限清除' : selected.title }}</h2>
        <p class="detail-byline">{{ selected.sender || '消息中心' }}<span> · </span><time :datetime="selected.createdAt">{{ formatTime(selected.createdAt) }}</time></p>
        <div class="message-body">{{ selected.contentPurgedAt ? `已于 ${formatTime(selected.contentPurgedAt)} 清除正文和分析结果。` : selected.content }}</div>
        <AnalysisPanel v-if="selected.dataClass === 'public_comment' && !selected.contentPurgedAt" :key="selected.messageId" :message-id="selected.messageId" :api="api" :mutate="mutate" :busy="busy" />
        <div class="detail-actions">
          <button v-if="operate && selected.readStatus === 'unread'" class="primary" :disabled="busy" @click="markRead(selected.messageId)">标记已读</button>
          <span v-else class="read-label">✓ 已读</span>
          <a v-if="selected.originalUrl" :href="selected.originalUrl" target="_blank" rel="noopener noreferrer">查看原文 ↗</a>
        </div>
      </article>
      <div v-else class="detail-placeholder"><span>选择一条消息，查看完整内容</span></div>
    </div>
  </section>
</template>
