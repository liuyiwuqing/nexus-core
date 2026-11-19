<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui';
import { Plus } from '@lucide/vue';
import { useAccess } from './access';
const { admin, operate } = useAccess();
import { computed, nextTick, ref, watch } from 'vue';
import { formatTime, type Api, type Device, type Message, type MessagePriority, type MessageSubmission, type Page, type ListSearch } from './types';
import AnalysisPanel from './AnalysisPanel.vue';
import ListSearchForm from './ListSearchForm.vue';
import MessageDeliveryBatch from './MessageDeliveryBatch.vue';
import MessageMultiBatch from './MessageMultiBatch.vue';
import MessageReceipts from './MessageReceipts.vue';

import StatusNote from './StatusNote.vue';
const props = defineProps<{
  data: Page<Message>; loading: boolean; busy: boolean; failed: boolean; readStatus: Message['readStatus'] | '';
  search: ListSearch; exporting: boolean;
  pending: MessageSubmission | null; uncertain: boolean; devices: Device[];
  create: (title: string, content: string, priority: MessagePriority, devices: string[]) => Promise<string | null>;
  markRead: (id: string) => Promise<boolean>;
  api: Api; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean>;
}>();
const emit = defineEmits<{ page: [page: number]; filter: [value: Message['readStatus'] | '']; search: [value: ListSearch]; clear: []; export: []; contentExport: []; connectors: [] }>();
function filterChanged(value: string) { emit('filter', value as Message['readStatus'] | ''); }
const selectedId = ref('');
const selected = computed(() => props.data.items.find(message => message.messageId === selectedId.value));
// 跨消息批量只覆盖当前页：换页、筛选或消息消失后，旧选择不能悄悄作用到别的消息上。
const MAX_SELECTION = 20;
const selectedIds = ref<string[]>([]);
const batchOpen = ref(false);
const selectionFull = computed(() => selectedIds.value.length >= MAX_SELECTION);
watch(() => props.data.page, () => {
  selectedIds.value = [];
  batchOpen.value = false;
});
watch(() => props.data.items, items => {
  const visible = new Set(items.map(message => message.messageId));
  const kept = selectedIds.value.filter(id => visible.has(id));
  if (kept.length !== selectedIds.value.length) selectedIds.value = kept;
  if (!kept.length) batchOpen.value = false;
});
function toggleSelect(id: string, checked: boolean) {
  if (checked) {
    if (selectionFull.value || selectedIds.value.includes(id)) return;
    selectedIds.value = [...selectedIds.value, id];
  } else selectedIds.value = selectedIds.value.filter(selected => selected !== id);
}
function clearSelection() {
  selectedIds.value = [];
  batchOpen.value = false;
}
const compose = ref(false);
const bodyExport=ref(false),bodyConfirm=ref(false);
const title = ref('');
const content = ref('');
const priority = ref<MessagePriority>('normal');
const priorities: { label: string; value: MessagePriority }[] = [
  { label: '低', value: 'low' },
  { label: '普通', value: 'normal' },
  { label: '高', value: 'high' },
  { label: '紧急', value: 'critical' },
];
const targets = ref<string[]>([]);
// 空选择表示不发送 devices 字段，保持既有路由（默认广播）。
const enabledDevices = computed(() => props.devices.filter(device => device.enabled));
const targetSummary = computed(() => targets.value.length
  ? `仅发送到 ${targets.value.length} 台设备：${targets.value.map(id => enabledDevices.value.find(device => device.deviceId === id)?.name ?? id).join('、')}。`
  : '未选择设备时按当前路由发送。');
watch(() => props.pending, submission => {
  if (submission) {
    title.value = submission.title; content.value = submission.content; priority.value = submission.priority;
    targets.value = [...submission.devices]; compose.value = true;
  }
}, { immediate: true });
const note = ref<InstanceType<typeof StatusNote> | null>(null);
const receiptId = ref('');
async function send() {
  const sent = await props.create(title.value, content.value, priority.value, targets.value);
  if (sent) {
    receiptId.value = sent;
    title.value = ''; content.value = ''; priority.value = 'normal'; targets.value = []; compose.value = false; return;
  }
  // 失败/未确认时按钮已 disabled 过一轮，焦点在 body；把它交回提示，键盘用户不必从头 Tab。
  await nextTick(); note.value?.focus();
}
</script>

<template>
  <section>
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">消息</h1>
        <p :aria-hidden="loading || failed">{{ loading || failed ? '\u00a0' : `${data.total} 条消息` }}</p>
      </div>
      <NButton
        type="primary"
        attr-type="button"
        v-if="operate"
        class="secondary"
        :disabled="!!pending"
        @click="compose = !compose"
        :aria-expanded="compose"
      >
        <template #icon><Plus :size="16" /></template>
        {{ compose ? '收起' : '发送测试消息' }}
      </NButton>
    </div>
    <ListSearchForm
      kind="messages"
      :filters="search"
      :status-active="!!readStatus"
      :disabled="loading || busy"
      :failed="failed"
      :total="data.total"
      :exporting="exporting"
      @apply="emit('search', $event)"
      @clear="emit('clear')"
      @export="emit('export')"
    >
      <ConsoleField path="field-1" :label-props="{ id: 'inboxpanel-field-1' + '-label', for: 'inboxpanel-field-1' }">
        <template #label>消息状态</template>
        <NSelect
          @keydown.esc.stop
          aria-label="消息状态"
          :disabled="loading || busy"
          :value="readStatus"
          @update:value="filterChanged"
          :options="[
            { label: '全部', value: '' },
            { label: '未读', value: 'unread' },
            { label: '已读', value: 'read' },
          ]"
          :input-props="{ id: 'inboxpanel-field-1', 'aria-label': '消息状态' }"
        />
      </ConsoleField>
      <template #extra-actions="{ blocked }">
        <NButton
          attr-type="button"
          v-if="admin"
          class="text-button"
          :disabled="blocked"
          @click="
            bodyExport = !bodyExport;
            bodyConfirm = false;
          "
        >
          导出正文…
        </NButton>
        <div v-if="admin && bodyExport" class="inline-form">
          <p>仅导出当前已应用筛选中，最近七天未清理的正文、标题、摘要、发送者和原始链接；最多 1000 条。文件只在本次下载生成。</p>
          <NCheckbox class="checkbox-label" v-model:checked="bodyConfirm" :disabled="blocked" :aria-disabled="blocked">确认下载包含消息正文的文件</NCheckbox>
          <NButton
            type="primary"
            attr-type="button"
            class="primary spaced"
            :disabled="blocked || !bodyConfirm"
            @click="
              emit('contentExport');
              bodyConfirm = false;
            "
          >
            确认导出正文
          </NButton>
        </div>
      </template>
    </ListSearchForm>
    <ConsoleForm v-if="compose" class="inline-form" @submit.prevent="send">
      <ConsoleField
        path="field-2"
        :rule="fieldRule(() => title, { required: true, maxlength: 160, disabled: () => busy })"
        :label-props="{ id: 'inboxpanel-field-2' + '-label', for: 'inboxpanel-field-2' }"
      >
        <template #label>标题</template>
        <NInput
          v-model:value="title"
          :maxlength="160"
          :input-props="{ required: true, id: 'inboxpanel-field-2', 'aria-label': '标题' }"
          :disabled="busy"
          :readonly="!!pending"
        />
      </ConsoleField>
      <ConsoleField
        path="field-3"
        :rule="fieldRule(() => content, { required: true, maxlength: 8192, disabled: () => busy })"
        :label-props="{ id: 'inboxpanel-field-3' + '-label', for: 'inboxpanel-field-3' }"
      >
        <template #label>内容</template>
        <NInput
          v-model:value="content"
          type="textarea"
          :maxlength="8192"
          :rows="3"
          :input-props="{ required: true, id: 'inboxpanel-field-3', 'aria-label': '内容' }"
          :disabled="busy"
          :readonly="!!pending"
        />
      </ConsoleField>
      <ConsoleField
        path="field-4"
        :label-props="{ id: 'inboxpanel-field-4' + '-label', for: 'inboxpanel-field-4' }"
      >
        <template #label>优先级</template>
        <NSelect
          class="priority-select"
          :value="priority"
          :options="priorities"
          :disabled="busy || !!pending"
          :input-props="{ id: 'inboxpanel-field-4', 'aria-label': '优先级' }"
          @update:value="(value: MessagePriority) => (priority = value)"
        />
      </ConsoleField>
      <ConsoleField
        path="field-5"
        :label-props="{ id: 'inboxpanel-field-5' + '-label', for: 'inboxpanel-field-5' }"
      >
        <template #label>目标设备</template>
        <NSelect
          class="device-select"
          :value="targets"
          :options="enabledDevices.map(device => ({ label: `${device.name}（${device.deviceId}）`, value: device.deviceId }))"
          multiple
          clearable
          :max-tag-count="2"
          placeholder="所有已启用设备"
          :disabled="busy || !!pending"
          :input-props="{ id: 'inboxpanel-field-5', 'aria-label': '目标设备' }"
          @update:value="(value: string[] | null) => (targets = value ?? [])"
        />
      </ConsoleField>
      <StatusNote v-if="uncertain" ref="note" tone="warn">发送结果尚未确认。请重试同一消息，确认前无法修改内容。</StatusNote>
      <div class="form-footer">
        <span>{{ targetSummary }}低/普通不打扰，高亮屏不发声，紧急正常提醒。</span>
        <NButton type="primary" attr-type="submit" class="primary" :disabled="busy">
          {{ busy ? '正在发送…' : uncertain ? '重试同一消息' : '发送' }}
        </NButton>
      </div>
    </ConsoleForm>
    <MessageReceipts
      v-if="receiptId"
      id="last-send-receipt"
      :key="receiptId"
      :message-id="receiptId"
      :api="api"
      :busy="busy"
      title="刚发送消息的投递状态"
      closable
      @close="receiptId = ''"
    />
    <ConsoleState v-if="loading && !data.items.length && !failed" kind="loading">正在加载消息…</ConsoleState>
    <ConsoleState v-else-if="!failed && !data.items.length && (readStatus || Object.values(search).some(Boolean))" title="没有符合条件的消息" />
    <ConsoleState v-else-if="!failed && !data.items.length" title="暂无消息">
      接入来源后，新消息会显示在这里。
      <template #actions><NButton attr-type="button" @click="emit('connectors')">查看接入</NButton></template>
    </ConsoleState>
    <div v-if="operate && selectedIds.length" class="message-select-bar" role="group" aria-label="跨消息批量投递操作">
      <span>{{ selectionFull ? `已选 ${MAX_SELECTION} 条（上限）` : `已选 ${selectedIds.length} 条` }}</span>
      <div class="message-select-actions">
        <NButton attr-type="button" class="text-button" :disabled="busy || loading" @click="clearSelection">清空选择</NButton>
        <NButton
          attr-type="button"
          class="secondary compact"
          :disabled="busy"
          :aria-expanded="batchOpen"
          @click="batchOpen = !batchOpen"
        >
          {{ batchOpen ? '收起批量投递操作' : '批量投递操作…' }}
        </NButton>
      </div>
    </div>
    <MessageMultiBatch
      v-if="operate && batchOpen && selectedIds.length"
      :message-ids="selectedIds"
      :api="api"
      :busy="busy"
      :mutate="mutate"
    />
    <div v-if="!failed && data.items.length" class="inbox-layout" :class="{ 'has-selection': !!selected, 'short-list': data.items.length <= 4 }" :aria-busy="loading">
      <div class="message-list" aria-label="消息列表">
        <div v-for="message in data.items" :key="message.messageId" class="message-select-row">
          <NCheckbox
            v-if="operate"
            class="message-select"
            :checked="selectedIds.includes(message.messageId)"
            :disabled="busy || loading || (!selectedIds.includes(message.messageId) && selectionFull)"
            :aria-label="`选择消息：${message.contentPurgedAt ? '内容已清除' : message.title}`"
            @update:checked="toggleSelect(message.messageId, $event)"
          />
          <NButton
            class="message-row"
            attr-type="button" block
            :type="selectedId === message.messageId ? 'primary' : 'default'"
            :secondary="selectedId === message.messageId" :quaternary="selectedId !== message.messageId"
            :class="{ active: selectedId === message.messageId, unread: message.readStatus === 'unread' }"
            :aria-pressed="selectedId === message.messageId"
            @click="selectedId = message.messageId"
          >
            <span class="message-meta">
              <span>{{ message.source === 'halo' ? 'Halo' : message.source === 'manual' ? '本机' : message.source }}</span>
              <time :datetime="message.receivedAt">{{ formatTime(message.receivedAt) }}</time>
            </span>
            <span class="message-title">
              <i v-if="message.readStatus === 'unread'" class="unread-dot" aria-label="未读"></i>
              {{ message.contentPurgedAt ? '内容已按保留期限清除' : message.title }}
            </span>
            <span class="message-preview">{{ message.summary }}</span>
          </NButton>
        </div>
        <div class="pagination">
          <NButton attr-type="button" :disabled="data.page <= 1 || loading || busy" @click="emit('page', data.page - 1)">
            上一页
          </NButton>
          <span>{{ data.page }} / {{ Math.max(1, Math.ceil(data.total / data.size)) }}</span>
          <NButton
            attr-type="button"
            :disabled="data.page * data.size >= data.total || loading || busy"
            @click="emit('page', data.page + 1)"
          >
            下一页
          </NButton>
        </div>
      </div>
      <article v-if="selected" class="message-detail">
        <NButton attr-type="button" class="mobile-back text-button" @click="selectedId = ''">← 返回消息</NButton>
        <div class="detail-top">
          <span class="eyebrow">
            {{ selected.source === 'halo' ? 'HALO' : selected.source === 'manual' ? '本机消息' : selected.source }}
          </span>
          <span v-if="selected.priority === 'high' || selected.priority === 'critical'" class="badge warning">重要</span>
        </div>
        <h2>{{ selected.contentPurgedAt ? '内容已按保留期限清除' : selected.title }}</h2>
        <p class="detail-byline">
          {{ selected.sender || '消息中心' }}
          <span>·</span>
          <time :datetime="selected.createdAt">{{ formatTime(selected.createdAt) }}</time>
        </p>
        <div class="message-body">
          {{ selected.contentPurgedAt ? `已于 ${formatTime(selected.contentPurgedAt)} 清除正文和分析结果。` : selected.content }}
        </div>
        <AnalysisPanel
          v-if="selected.dataClass === 'public_comment' && !selected.contentPurgedAt"
          :key="selected.messageId"
          :message-id="selected.messageId"
          :api="api"
          :mutate="mutate"
          :busy="busy"
        />
        <div class="detail-actions">
          <NButton
            type="primary"
            attr-type="button"
            v-if="operate && selected.readStatus === 'unread'"
            class="primary"
            :disabled="busy"
            @click="markRead(selected.messageId)"
          >
            标记已读
          </NButton>
          <span v-else class="read-label">✓ 已读</span>
          <a v-if="selected.originalUrl" :href="selected.originalUrl" target="_blank" rel="noopener noreferrer">查看原文 ↗</a>
        </div>
        <MessageReceipts
          :key="'receipt-' + selected.messageId"
          :message-id="selected.messageId"
          :api="api"
          :busy="busy"
        />
        <MessageDeliveryBatch
          v-if="operate"
          :key="selected.messageId"
          :message-id="selected.messageId"
          :api="api"
          :busy="busy"
          :mutate="mutate"
        />
      </article>
      <ConsoleState v-else compact class="detail-placeholder">选择一条消息，查看完整内容</ConsoleState>
    </div>
  </section>
</template>
