<script setup lang="ts">
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NInput } from 'naive-ui';
import { onUnmounted, ref, watch } from 'vue';
import { formatTime, type Api } from './types';

import { readFailure } from './failure';
interface Health { configured: boolean; storedCopies: number; pendingDeletions: number; deletionErrors: number; identityBlockedCopies?: number }
interface Hit { messageId: string; content: string; summary: string; originalUrl: string; verifiedAt: string }
const props = defineProps<{ api: Api }>();
const health = ref<Health | null>(null), query = ref(''), hits = ref<Hit[]>([]);
const loading = ref(false), checking = ref(false), searched = ref(false), partial = ref(false);
const error = ref(''), healthError = ref(''), expired = ref(false);
let generation = 0, checkGeneration = 0, controller: AbortController | undefined, checkController: AbortController | undefined;
let expiry: ReturnType<typeof setTimeout> | undefined;
function clearResults() { clearTimeout(expiry); hits.value = []; searched.value = false; partial.value = false; expired.value = false; }
function safeUrl(value: string) {
  try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : undefined; }
  catch { return undefined; }
}
async function load() {
  checkController?.abort(); checkController = new AbortController(); const current = ++checkGeneration;
  checking.value = true;
  try {
    const result = await props.api<Health>('memory', 'GET', undefined, checkController.signal);
    if (current !== checkGeneration) return;
    health.value = result; healthError.value = '';
    if (!result.configured) { generation++; controller?.abort(); loading.value = false; clearResults(); }
  } catch (failure) {
    if (current === checkGeneration && !(failure instanceof DOMException && failure.name === 'AbortError')) {
      healthError.value = readFailure(failure, '读取记忆状态失败。');
      health.value = null; generation++; controller?.abort(); loading.value = false; clearResults();
    }
  } finally { if (current === checkGeneration) checking.value = false; }
}
async function search() {
  if (loading.value || !health.value?.configured || !query.value.trim()) return;
  controller?.abort(); controller = new AbortController(); const current = ++generation;
  clearResults(); error.value = ''; loading.value = true;
  try {
    const result = await props.api<{ items: Hit[]; partial: boolean }>('memory/search', 'POST', { query: query.value.trim(), limit: 3 }, controller.signal);
    if (current !== generation) return;
    hits.value = result.items; partial.value = result.partial; searched.value = true;
    // Results describe source visibility at verification time, not a live grant.
    expiry = setTimeout(() => { clearResults(); expired.value = true; }, 60000);
  } catch (failure) {
    if (current === generation && !(failure instanceof DOMException && failure.name === 'AbortError'))
      error.value = readFailure(failure, '记忆检索失败，请重试。');
  } finally { if (current === generation) loading.value = false; }
}
watch(query, () => { clearResults(); error.value = ''; });
void load();
const timer = setInterval(() => { if (!checking.value && document.visibilityState === 'visible') void load(); }, 5000);
onUnmounted(() => { generation++; checkGeneration++; controller?.abort(); checkController?.abort(); clearTimeout(expiry); clearInterval(timer); });
</script>

<template>
  <section aria-label="长期记忆">
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">长期记忆</h1>
        <p>检索已保存的公开评论</p>
      </div>
      <NButton attr-type="button" class="text-button" :disabled="checking" @click="load">刷新记忆状态</NButton>
    </div>
    <p v-if="healthError" class="feedback error" role="alert">{{ healthError }}</p>
    <dl v-if="health" class="management-metrics memory-metrics">
      <div>
        <dt>已保存</dt>
        <dd>{{ health.storedCopies }}</dd>
      </div>
      <div>
        <dt>待删除</dt>
        <dd>{{ health.pendingDeletions }}</dd>
      </div>
      <div>
        <dt>删除待重试</dt>
        <dd>{{ health.deletionErrors }}</dd>
      </div>
    </dl>
    <p v-if="health && (health.identityBlockedCopies || 0) > 0" class="feedback" role="status">
      {{ health.identityBlockedCopies }} 条旧副本的身份需要核对，已暂停检索和删除。
    </p>
    <p v-if="health && !health.configured" class="muted">长期记忆未启用，请在 CORE 配置中启用服务。</p>
    <ConsoleForm class="inline-form memory-search" @submit.prevent="search">
      <ConsoleField
        path="field-1"
        :rule="fieldRule(() => query, { required: true, maxlength: 256, disabled: () => loading || !health?.configured })"
        :label-props="{ id: 'memorypanel-field-1' + '-label', for: 'memorypanel-field-1' }"
      >
        <template #label>检索内容</template>
        <NInput
          :disabled="loading || !health?.configured"
          v-model:value="query"
          :maxlength="256"
          :input-props="{ required: true, autocomplete: 'off', id: 'memorypanel-field-1', 'aria-label': '检索内容' }"
        />
      </ConsoleField>
      <div class="form-footer">
        <span v-if="loading" role="status">正在检索并核验来源…</span>
        <span v-else></span>
        <NButton type="primary" attr-type="submit" class="primary" :disabled="loading || !health?.configured || !query.trim()">
          {{ loading ? '检索中…' : '检索' }}
        </NButton>
      </div>
    </ConsoleForm>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <p v-if="partial" class="feedback" role="status">部分来源暂时无法核验，已隐藏相关结果。</p>
    <p v-if="expired" class="muted" role="status">结果已过期，请重新检索。</p>
    <p v-else-if="searched && !hits.length" class="muted" role="status">没有可返回的公开评论。</p>
    <div class="record-list memory-results" aria-live="polite">
      <article v-for="hit in hits" :key="hit.messageId" class="record-card">
        <div class="record-heading">
          <h2>公开评论</h2>
          <small>核验于 {{ formatTime(hit.verifiedAt) }}</small>
        </div>
        <p class="draft-text">{{ hit.content }}</p>
        <details v-if="hit.summary">
          <summary>AI 摘要 · 推断</summary>
          <p class="draft-text">{{ hit.summary }}</p>
        </details>
        <a v-if="safeUrl(hit.originalUrl)" :href="safeUrl(hit.originalUrl)" target="_blank" rel="noopener noreferrer">查看原评论</a>
      </article>
    </div>
  </section>
</template>
