<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import { NButton } from 'naive-ui';
import { useAccess } from './access';
const { admin, operate } = useAccess();
import { onUnmounted, ref, watch } from 'vue';
import { errorNames, type Analysis, type Api } from './types';

import { readFailure } from './failure';
const props = defineProps<{ messageId: string; api: Api; busy: boolean;
  mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean> }>();
const state = ref<Analysis | null>(null), error = ref(''), loading = ref(false);
const ai = ref(false), memory = ref(false);
let generation = 0, controller: AbortController | undefined;
const names: Record<string, string> = { not_requested: '尚未分析', pending: '等待处理', processing: '正在分析', succeeded: '分析完成', failed: '分析失败', blocked: '已阻止外发' };
const categories: Record<string, string> = { question: '提问', feedback: '反馈', praise: '赞赏', other: '其他' };
const memoryNames: Record<string, string> = { off: '尚未保存', pending: '等待保存', stored: '已保存', error: '保存失败', blocked: '已阻止保存' };
async function load() {
  controller?.abort(); controller = new AbortController();
  const current = ++generation, id = props.messageId; loading.value = true;
  try {
    const [result, model, store] = await Promise.all([
      props.api<Analysis>(`messages/${id}/analysis`, 'GET', undefined, controller.signal),
      props.api<{ configured: boolean }>('ai', 'GET', undefined, controller.signal),
      props.api<{ configured: boolean }>('memory', 'GET', undefined, controller.signal),
    ]);
    if (current !== generation) return;
    state.value = result; ai.value = model.configured; memory.value = store.configured; error.value = '';
  } catch (failure) {
    if (current === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) error.value = readFailure(failure, '读取分析状态失败。');
  } finally { if (current === generation) loading.value = false; }
}
async function analyze() {
  const id = props.messageId, retry = state.value?.status === 'failed' || state.value?.status === 'blocked';
  if (await props.mutate(() => props.api(`messages/${id}/analysis${retry ? '/retry' : ''}`, 'POST'), '已加入 AI 分析队列。')) await load();
}
async function save() {
  const id = props.messageId;
  if (await props.mutate(() => props.api(`messages/${id}/memory`, 'POST'), '已加入长期记忆队列。')) await load();
}
watch(() => props.messageId, () => { state.value = null; void load(); }, { immediate: true });
const timer = setInterval(() => { if (!loading.value && !props.busy && document.visibilityState === 'visible') void load(); }, 5000);
onUnmounted(() => { generation++; controller?.abort(); clearInterval(timer); });
</script>

<template>
  <section class="analysis-section" aria-label="公开评论 AI 分析" :aria-busy="loading">
    <div class="analysis-heading"><h3>AI 分析</h3><span v-if="state" class="muted">{{ names[state.status] }}</span></div>
    <ConsoleState v-if="error" kind="error" compact>{{ error }}<template #actions><NButton attr-type="button" :disabled="loading" @click="load">重试</NButton></template></ConsoleState>
    <ConsoleState v-else-if="loading && !state" kind="loading" compact>正在读取分析…</ConsoleState>
    <ConsoleState v-if="state && !error && !ai" kind="info" compact>AI 服务未启用</ConsoleState>
    <template v-if="state?.status === 'succeeded'">
      <p>{{ state.summary }}</p>
      <div class="analysis-tags"><span class="badge">{{ categories[state.category || ''] || '其他' }}</span><span v-if="state.importance === 'high'" class="badge warning">AI 判断：重要</span></div>
      <details v-if="state.replyDraft"><summary>回复草稿 · 未发送</summary><p class="draft-text">{{ state.replyDraft }}</p></details>
      <div class="memory-row"><span>{{ memoryNames[state.memoryStatus || 'off'] }}</span>
        <NButton attr-type="button" v-if="admin && state.memoryStatus !== 'stored'" class="secondary compact" :disabled="!memory || busy || state.memoryStatus === 'pending'" @click="save">保存到长期记忆</NButton>
      </div>
      <ConsoleState v-if="!memory" kind="info" compact>长期记忆未启用</ConsoleState>
      <ConsoleState v-if="state.memoryError" kind="error" compact>{{ errorNames[state.memoryError] || '记忆保存未完成，请检查服务。' }}</ConsoleState>
    </template>
    <template v-else-if="state">
      <ConsoleState v-if="state.lastError" kind="error" compact>{{ errorNames[state.lastError] || '分析未完成，请检查 AI 服务后重试。' }}</ConsoleState>
      <NButton attr-type="button" class="secondary compact" :disabled="!operate || !ai || busy || ['pending', 'processing'].includes(state.status)" @click="analyze">{{ state.status === 'not_requested' ? '分析公开评论' : '重新核验并分析' }}</NButton>
    </template>
  </section>
</template>
