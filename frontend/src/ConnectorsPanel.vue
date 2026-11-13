<script setup lang="ts">
import { formatTime, errorNames, type Connector } from './types';
import { ref } from 'vue';
const props = defineProps<{ items: Connector[]; busy: boolean; poll: () => Promise<boolean>; importComment: (name: string) => Promise<boolean> }>();
const commentName = ref('');
async function importOne() { if (await props.importComment(commentName.value.trim())) commentName.value = ''; }
const stateNames: Record<string, string> = { ready: '连接正常', not_checked: '等待同步', not_configured: '尚未配置', error: '连接异常' };
</script>
<template>
  <section>
    <div class="section-heading"><div><h1>接入</h1><p>平台授权与同步状态</p></div></div>
    <article v-for="connector in items" :key="connector.id" class="connector-card">
      <div class="connector-top"><div class="source-icon" aria-hidden="true">H</div><div><h2>Halo 博客</h2><p class="mono">{{ connector.id }}</p></div><span class="badge" :class="connector.state === 'ready' ? 'success' : connector.state === 'error' ? 'danger' : ''">{{ stateNames[connector.state] || connector.state }}</span></div>
      <dl class="connector-data"><div><dt>内容范围</dt><dd>公开文章与页面下的已审核评论</dd></div><div><dt>最近同步</dt><dd>{{ formatTime(connector.lastSuccessAt) }}</dd></div><div><dt>开始接收</dt><dd>{{ formatTime(connector.since) }}</dd></div></dl>
      <p v-if="connector.lastError" class="inline-error">{{ errorNames['halo_' + connector.lastError] || '同步失败，请检查连接配置。' }}</p>
      <div class="connector-footer"><span>{{ connector.configured ? '每分钟自动同步，重复评论会自动合并。' : '在本机配置 Halo 只读令牌后启用。' }}</span><button class="secondary" :disabled="busy || !connector.configured" @click="poll">{{ busy ? '正在同步…' : '立即同步' }}</button></div>
      <details v-if="connector.configured" class="import-comment"><summary>导入一条历史公开评论</summary><form @submit.prevent="importOne"><label>评论 ID<input v-model="commentName" required maxlength="120" pattern="[a-zA-Z0-9_.-]+" :disabled="busy" /></label><div class="form-footer"><span>重新核验公开状态后入库并提醒设备。</span><button class="secondary compact" :disabled="busy">导入</button></div></form></details>
    </article>
    <p class="scope-note">首次接入默认从当前时间开始接收。私信、邮件和录音保持关闭。</p>
  </section>
</template>
