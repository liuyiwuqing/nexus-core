<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { NButton, NInputNumber } from 'naive-ui';
import ConsoleState from './ConsoleState.vue';
import { useAccess } from './access';
import { formatTime, type Api } from './types';
import { readFailure } from './failure';

// 与 CORE 台账字段一一对应；secret 只在签发/轮换响应里出现一次，刷新后必然消失。
interface DeviceCredential {
  deviceId: string;
  username: string;
  revision: number;
  status: 'none' | 'active' | 'rotating' | 'revoked';
  acl: string[];
  issuedAt: string | null;
  rotatedAt: string | null;
  previousExpiresAt: string | null;
  revokedAt: string | null;
  previousActive: boolean;
  secret?: string;
}
const props = defineProps<{ deviceId: string; api: Api; busy: boolean }>();
const { admin } = useAccess();
const base = computed(() => `devices/${encodeURIComponent(props.deviceId)}/credentials`);
const state = ref<DeviceCredential | null>(null);
const issued = ref<DeviceCredential | null>(null);
const loading = ref(false), pending = ref(false), error = ref(''), notice = ref('');
const action = ref<'' | 'issue' | 'rotate' | 'revoke'>('');
const grace = ref<number | null>(24);
const copyState = ref('');
let alive = true, generation = 0, control: AbortController | undefined;

const statusNames: Record<DeviceCredential['status'], string> = {
  none: '未签发', active: '已启用', rotating: '轮换宽限期', revoked: '已撤销',
};
const statusClass = computed(() => !state.value || state.value.status === 'none' ? ''
  : state.value.status === 'active' ? 'success' : state.value.status === 'revoked' ? 'danger' : 'warning');

async function load() {
  control?.abort();
  const current = new AbortController();
  control = current;
  const ticket = ++generation;
  loading.value = true;
  error.value = '';
  try {
    const value = await props.api<DeviceCredential>(base.value, 'GET', undefined, current.signal);
    if (alive && ticket === generation) state.value = value;
  } catch (failure) {
    if (alive && ticket === generation && !current.signal.aborted) {
      state.value = null;
      error.value = readFailure(failure, '未能读取凭据状态，请刷新重试。');
    }
  } finally {
    if (alive && ticket === generation) loading.value = false;
  }
}

// 失败时保留原状态，不凭空显示"已签发"；网络类失败提示先核对再重试。
async function run(kind: 'issue' | 'rotate' | 'revoke') {
  if (pending.value || props.busy) return;
  pending.value = true;
  notice.value = '';
  error.value = '';
  try {
    if (kind === 'issue') {
      const value = await props.api<DeviceCredential>(base.value, 'POST', { confirm: 'ISSUE_DEVICE_CREDENTIAL' });
      if (!value?.secret || value.secret.length < 32) throw new Error('unconfirmed');
      issued.value = value;
      notice.value = '凭据已签发，密钥只显示这一次。';
    } else if (kind === 'rotate') {
      const value = await props.api<DeviceCredential>(base.value + '/rotate', 'POST',
        { expectedRevision: state.value?.revision, graceHours: grace.value ?? 24, confirm: 'ROTATE_DEVICE_CREDENTIAL' });
      if (!value?.secret || value.secret.length < 32) throw new Error('unconfirmed');
      issued.value = value;
      notice.value = '新密钥已生成，旧密钥在宽限期结束后失效。';
    } else {
      await props.api(base.value + '/revoke', 'POST',
        { expectedRevision: state.value?.revision, confirm: 'REVOKE_DEVICE_CREDENTIAL' });
      issued.value = null;
      notice.value = '凭据已撤销，请同时在 Mosquitto 删除该账号。';
    }
    action.value = '';
  } catch (failure) {
    if (failure instanceof Error && failure.message === 'unconfirmed') error.value = '结果未确认，请刷新后核对当前凭据状态，再决定是否重试。';
    else error.value = readFailure(failure, '操作结果尚未确认，请刷新核对后再继续。');
  } finally {
    pending.value = false;
    if (alive) await load();
  }
}

async function copySecret() {
  const value = issued.value?.secret;
  if (!value) return;
  try {
    await navigator.clipboard.writeText(value);
    copyState.value = '已复制';
  } catch {
    copyState.value = '复制失败，请手动选择';
  }
}

onMounted(load);
onUnmounted(() => { alive = false; control?.abort(); });
watch(() => props.deviceId, () => { issued.value = null; action.value = ''; notice.value = ''; void load(); });
</script>

<template>
  <section class="management-record credential-section" aria-label="MQTT 凭据">
    <div class="record-heading">
      <h3>MQTT 凭据</h3>
      <span v-if="state" class="badge" :class="statusClass">{{ statusNames[state.status] }}</span>
      <NButton attr-type="button" class="text-button" :disabled="loading || pending" @click="load">
        {{ loading ? '刷新中…' : '刷新凭据状态' }}
      </NButton>
    </div>
    <ConsoleState v-if="error" kind="error" compact>{{ error }}</ConsoleState>
    <p v-else-if="notice" class="credential-notice">{{ notice }}</p>
    <ConsoleState v-if="loading && !state && !error" kind="loading" compact>正在读取凭据状态…</ConsoleState>
    <template v-else-if="state">
      <dl class="management-metrics">
        <div><dt>用户名</dt><dd><code>{{ state.username }}</code></dd></div>
        <div><dt>修订号</dt><dd>{{ state.revision }}</dd></div>
        <div><dt>登记时间</dt><dd>{{ state.issuedAt ? formatTime(state.issuedAt) : '—' }}</dd></div>
        <div v-if="state.rotatedAt"><dt>最近轮换</dt><dd>{{ formatTime(state.rotatedAt) }}</dd></div>
        <div v-if="state.previousExpiresAt">
          <dt>旧密钥保留至</dt>
          <dd>{{ formatTime(state.previousExpiresAt) }}{{ state.previousActive ? '' : '（已过期）' }}</dd>
        </div>
        <div v-if="state.revokedAt"><dt>撤销时间</dt><dd>{{ formatTime(state.revokedAt) }}</dd></div>
      </dl>
      <p class="management-hint">
        CORE 只维护这份台账与一次性密钥，不写 Mosquitto 配置。签发后在主机执行
        <code>mosquitto_passwd -b /etc/nexus/docker/mosquitto/config/passwd {{ state.username }} &lt;密钥&gt;</code>
        并把 ACL 追加到同一目录的 <code>acl</code> 文件，再重启 mqtt 服务；设备需重新配置同一用户名和密钥。
      </p>
      <ul class="credential-acl"><li v-for="line in state.acl" :key="line"><code>{{ line }}</code></li></ul>

      <div v-if="issued?.secret" class="credential-secret" role="status">
        <div class="record-heading">
          <strong>一次性密钥（关闭后无法再次查看）</strong>
          <NButton attr-type="button" class="text-button" @click="issued = null; copyState = ''">我已保存，关闭</NButton>
        </div>
        <code class="credential-value">{{ issued.secret }}</code>
        <NButton attr-type="button" class="text-button" @click="copySecret">{{ copyState || '复制密钥' }}</NButton>
        <p class="management-hint">丢失只能重新轮换；不要把密钥写进工单、截图或聊天记录。</p>
      </div>

      <div v-if="admin" class="management-actions credential-actions">
        <NButton v-if="state.status === 'none' || state.status === 'revoked'" attr-type="button" :disabled="pending || busy"
          @click="action = 'issue'">签发凭据</NButton>
        <template v-else>
          <NButton attr-type="button" :disabled="pending || busy" @click="action = 'rotate'">轮换</NButton>
          <NButton attr-type="button" :disabled="pending || busy" @click="action = 'revoke'">撤销</NButton>
        </template>
      </div>

      <div v-if="action" class="credential-confirm">
        <p v-if="action === 'issue'">将为 {{ state.username }} 生成一次性密钥，并写入台账第 {{ state.revision + 1 }} 修订号。尚未在 Broker 创建账号。</p>
        <p v-else-if="action === 'rotate'">新密钥立即成为当前凭据，旧密钥在下方宽限期结束后失效；用于把新值配置到设备的过渡时间。</p>
        <p v-else>撤销后该用户名不再有效，恢复需要重新签发；Broker 侧的账号和 ACL 仍需手工删除。</p>
        <label v-if="action === 'rotate'" class="credential-grace">
          旧密钥保留
          <NInputNumber v-model:value="grace" :min="1" :max="168" :step="1" size="small" style="width: 96px" />
          小时
        </label>
        <div class="management-actions">
          <NButton attr-type="button" type="primary" :disabled="pending || busy || (action === 'rotate' && !grace)"
            @click="run(action)">{{ pending ? '提交中…' : action === 'issue' ? '确认签发' : action === 'rotate' ? '确认轮换' : '确认撤销' }}</NButton>
          <NButton attr-type="button" :disabled="pending" @click="action = ''">取消</NButton>
        </div>
      </div>
      <p v-if="!admin" class="management-hint">只有管理员可以签发、轮换或撤销凭据；当前角色可查看台账状态。</p>
    </template>
  </section>
</template>

<style scoped>
.credential-section { border-bottom: 0; }
.credential-section h3 { margin: 0; font-size: var(--text-md); }
.credential-acl { margin: 0 0 var(--space-3); padding-left: 18px; color: var(--muted); font-size: var(--text-sm); }
.credential-acl code, .credential-section .management-hint code, .credential-section .management-metrics code { overflow-wrap: anywhere; word-break: break-word; }
.credential-secret { margin: var(--space-3) 0; padding: var(--space-3); border: 1px solid var(--warning-border); border-radius: var(--radius-md); background: var(--warning-soft); }
.credential-value { display: block; margin: var(--space-2) 0; word-break: break-all; font-size: var(--text-base); }
.credential-notice { margin: var(--space-2) 0; color: var(--success); font-size: var(--text-sm); }
.credential-confirm { margin-top: var(--space-3); padding: var(--space-3); border: 1px solid var(--divider); border-radius: var(--radius-md); font-size: var(--text-base); }
.credential-confirm p { margin: 0 0 var(--space-2); }
.credential-grace { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-2); font-size: var(--text-sm); }
.credential-actions { margin: var(--space-2) 0; }
</style>
