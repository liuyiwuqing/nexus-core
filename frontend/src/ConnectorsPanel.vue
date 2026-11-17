<script setup lang="ts">
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NInput } from 'naive-ui';
import { useAccess } from './access';
const { admin } = useAccess();
import { formatTime, errorNames, type Connector } from './types';
import { ref } from 'vue';
const props = defineProps<{ items: Connector[]; busy: boolean; poll: () => Promise<boolean>; importComment: (name: string) => Promise<boolean> }>();
const commentName = ref('');
const importOpen = ref<Record<string, boolean>>({});
async function importOne() { if (await props.importComment(commentName.value.trim())) commentName.value = ''; }
const stateNames: Record<string, string> = { ready: '连接正常', not_checked: '等待同步', not_configured: '尚未配置', error: '连接异常' };
</script>
<template>
  <section>
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">接入</h1>
        <p>平台授权与同步状态</p>
      </div>
    </div>
    <article v-for="connector in items" :key="connector.id" class="connector-card">
      <div class="connector-top">
        <div class="source-icon" aria-hidden="true">H</div>
        <div>
          <h2>Halo 博客</h2>
          <p class="mono">{{ connector.id }}</p>
        </div>
        <span class="badge" :class="connector.state === 'ready' ? 'success' : connector.state === 'error' ? 'danger' : ''">
          {{ stateNames[connector.state] || connector.state }}
        </span>
      </div>
      <dl class="connector-data">
        <div>
          <dt>内容范围</dt>
          <dd>公开文章与页面下的已审核评论</dd>
        </div>
        <div>
          <dt>最近同步</dt>
          <dd>{{ formatTime(connector.lastSuccessAt) }}</dd>
        </div>
        <div>
          <dt>开始接收</dt>
          <dd>{{ formatTime(connector.since) }}</dd>
        </div>
      </dl>
      <p v-if="connector.lastError" class="inline-error">{{ errorNames['halo_' + connector.lastError] || '同步失败，请检查连接配置。' }}</p>
      <div class="connector-footer">
        <span>{{ connector.configured ? '每分钟自动同步，重复评论会自动合并。' : '在本机配置 Halo 只读令牌后启用。' }}</span>
        <NButton attr-type="button" v-if="admin" class="secondary" :disabled="busy || !connector.configured" @click="poll">
          {{ busy ? '正在同步…' : '立即同步' }}
        </NButton>
      </div>
      <div v-if="admin && connector.configured" class="import-comment">
        <NButton quaternary attr-type="button" class="import-toggle" :aria-expanded="!!importOpen[connector.id]"
          :aria-controls="`import-${connector.id}`" @click="importOpen[connector.id] = !importOpen[connector.id]">导入一条历史公开评论</NButton>
        <ConsoleForm v-show="importOpen[connector.id]" :id="`import-${connector.id}`" @submit.prevent="importOne">
          <ConsoleField
            path="field-1"
            :rule="fieldRule(() => commentName, { required: true, pattern: '[a-zA-Z0-9_.-]+', maxlength: 120, disabled: () => busy })"
            :label-props="{ id: 'connectorspanel-field-1' + '-label', for: 'connectorspanel-field-1' }"
          >
            <template #label>评论 ID</template>
            <NInput
              :disabled="busy"
              v-model:value="commentName"
              :maxlength="120"
              :input-props="{ required: true, pattern: '[a-zA-Z0-9_.-]+', id: 'connectorspanel-field-1', 'aria-label': '评论 ID' }"
            />
          </ConsoleField>
          <div class="form-footer">
            <span>重新核验公开状态后入库并提醒设备。</span>
            <NButton size="small" attr-type="submit" class="secondary compact" :disabled="busy">导入</NButton>
          </div>
        </ConsoleForm>
      </div>
    </article>
    <p class="scope-note">首次接入默认从当前时间开始接收。私信、邮件和录音保持关闭。</p>
  </section>
</template>
