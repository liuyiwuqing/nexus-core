<script setup lang="ts">
import ConsoleDisclosure from './ConsoleDisclosure.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NCheckbox, NInput, NSelect, NUpload, type UploadFileInfo } from 'naive-ui';
import { useAccess } from './access';
const { admin } = useAccess();
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { errorNames, formatTime, type Api, type Device, type OtaRelease, type OtaView } from './types';
import StatusNote from './StatusNote.vue';
const props = defineProps<{ device: Device; api: Api; busy: boolean }>();
const emit = defineEmits<{ close: [] }>();
const data = ref<OtaView | null>(null), releases = ref<OtaRelease[]>([]), releaseId = ref('');
const loading = ref(false), sending = ref(false), loadError = ref(''), error = ref(''), notice = ref('');
const adding = ref(false), catalogId = ref(''), manifest = ref<unknown>(null), fileError = ref('');
const confirming = ref<{ requestId: string; bootId: string } | null>(null), confirmed = ref(false);
type Pending = { kind: 'install' | 'reboot'; path: string; requestId: string; body: Record<string, unknown> };
const pending = ref<Pending | null>(null);
type Audit = { sequence: number; actor: string; event: string; detail: string; createdAt: string };
const auditId = ref(''), audit = ref<Audit[]>([]), auditLoading = ref(false), auditError = ref('');
const names: Record<string, string> = { pending: '等待下发', sent: '等待设备接收', accepted: '命令已接收，等待设备进度', installing: '正在安装', pending_reboot: '安装完成，等待确认重启', rebooting: '等待重启结果', verifying: '正在核验新镜像', healthy: '新镜像已确认健康', failed: '安装失败', rejected: '设备拒绝', expired: '请求已到期', interrupted: '安装中断', rolled_back: '镜像已回退', unconfirmed: '结果未确认', unavailable: '设备暂不可升级', idle: '空闲' };
const events: Record<string, string> = { created: '提交安装', reboot_created: '确认重启', publish_attempt: '尝试下发', publish_unconfirmed: '下发结果未确认', command_accepted: '设备接收命令', command_rejected: '设备拒绝命令', command_expired: '命令到期', command_unconfirmed: '等待设备回读' };
const path = `devices/${encodeURIComponent(props.device.deviceId)}/ota`;
const compatible = computed(() => releases.value.filter(item => {
  const identity = data.value?.observation?.identity;
  return identity && Object.entries(item.identity).every(([key, value]) => identity[key] === value) &&
    item.target.releaseSequence > (data.value?.observation?.reservedSequence ?? 0);
}));

// 误装版本是这类操作最贵的错误：提交点必须自报"装哪个版本、装到哪台设备"。
const selectedVersion = computed(() => compatible.value.find(item => item.releaseId === releaseId.value)?.target.version ?? '');
// 版本进按钮（选错版本最贵），设备进页脚（面板标题已写明设备，避免长名字撑破按钮）。
const installLabel = computed(() => sending.value ? '正在提交…' : selectedVersion.value ? `安装 ${selectedVersion.value}` : '安装所选版本');const blocked = computed(() => !admin.value || props.busy || sending.value || !!pending.value || !!loadError.value);
/* 阶段只由设备上报的状态推导：安装失败或结果未确认时不猜测进度，条带整体不渲染。 */
const phases = ['准备', '安装', '重启', '核验', '完成'];
const phaseByState: Record<string, number> = { pending: 0, sent: 0, accepted: 0, installing: 1, pending_reboot: 2, rebooting: 2, verifying: 3, healthy: 4 };
const phase = computed(() => data.value?.observation ? phaseByState[data.value.observation.state] ?? -1 : -1);
const phaseLabel = computed(() => data.value?.observation ? names[data.value.observation.state] || '准备' : '准备');
// 写入比例用设备上报的字节数与已登记镜像大小，两者都缺失时不画进度条。
const writtenPercent = computed(() => {
  const view = data.value, observation = view?.observation;
  if (!view || !observation || observation.state !== 'installing' || observation.writtenBytes <= 0) return null;
  const total = observation.target?.imageSize ?? view.deployments.find(item => item.requestId === observation.requestId)?.target.imageSize ?? 0;
  return total > 0 ? Math.min(100, Math.floor(observation.writtenBytes / total * 100)) : null;
});
let alive = true, generation = 0, timer: ReturnType<typeof setInterval> | undefined, read: AbortController | undefined;
async function refresh() {
  if (loading.value) return;
  read?.abort(); read = new AbortController(); const current = ++generation; loading.value = true;
  try {
    const [view, catalog] = await Promise.all([
      props.api<OtaView>(path, 'GET', undefined, read.signal),
      props.api<{ enabled: boolean; items: OtaRelease[] }>('ota/releases', 'GET', undefined, read.signal),
    ]);
    if (!alive || current !== generation) return;
    data.value = view; releases.value = catalog.items; loadError.value = '';
    if (confirming.value && (!view.canReboot || confirming.value.bootId !== view.observation?.bootId ||
      confirming.value.requestId !== view.observation?.requestId)) { confirming.value = null; confirmed.value = false; }
    if (pending.value && view.deployments.some(item => pending.value!.kind === 'install' ? item.requestId === pending.value!.requestId :
      item.commands.some(command => command.commandId === pending.value!.requestId))) {
      pending.value = null; error.value = ''; notice.value = '已找到请求记录，请查看设备进度。';
    }
  } catch (e) {
    if (alive && !(e instanceof DOMException && e.name === 'AbortError'))
      loadError.value = e instanceof Error && 'status' in e ? e.message : '无法读取升级状态，请稍后刷新。';
  } finally { if (alive && current === generation) loading.value = false; }
}
let manifestRead = 0;
async function readManifest({ file: upload }: { file: UploadFileInfo }) {
  const current = ++manifestRead;
  manifest.value = null; fileError.value = '';
  const file = upload.status === 'removed' ? null : upload.file; if (!file) return;
  if (file.size > 7000) { fileError.value = 'manifest 文件超过 7000 字节。'; return; }
  try {
    const value = JSON.parse(await file.text()) as unknown;
    if (alive && current === manifestRead) manifest.value = value;
  } catch { if (alive && current === manifestRead) fileError.value = '无法解析 manifest JSON。'; }
}
async function register() {
  if (blocked.value || !manifest.value || !catalogId.value) return;
  sending.value = true; error.value = ''; notice.value = '';
  try {
    await props.api('ota/releases', 'POST', { releaseId: catalogId.value, manifest: manifest.value });
    if (!alive) return; adding.value = false; notice.value = '版本已登记；设备安装时仍会核验签名和镜像。'; await refresh();
  } catch (e) { if (alive) error.value = e instanceof Error && 'status' in e ? e.message : '登记结果未确认，可使用相同目录和 manifest 重试。'; }
  finally { if (alive) sending.value = false; }
}
async function send(kind: 'install' | 'reboot') {
  if (sending.value || props.busy) return;
  if (!pending.value) {
    const view = data.value; if (!view || blocked.value) return;
    const requestId = crypto.randomUUID();
    if (kind === 'install') {
      if (!view.canInstall || !view.observation || !compatible.value.some(item => item.releaseId === releaseId.value)) return;
      pending.value = { kind, path: path + '/deployments', requestId, body: { requestId, releaseId: releaseId.value, bootId: view.observation.bootId } };
    } else {
      if (!view.canReboot || !confirming.value || !confirmed.value) return;
      pending.value = { kind, path: path + '/deployments/' + encodeURIComponent(confirming.value.requestId) + '/reboot', requestId,
        body: { requestId, bootId: confirming.value.bootId, confirm: 'REBOOT_DEVICE' } };
    }
  }
  sending.value = true; error.value = ''; notice.value = '';
  try {
    await props.api(pending.value.path, 'POST', pending.value.body);
    if (!alive) return; pending.value = null; confirming.value = null; confirmed.value = false;
    notice.value = '请求已保存，等待设备回读确认结果。'; await refresh();
  } catch (e) {
    if (!alive) return;
    const status = e instanceof Error && 'status' in e ? Number(e.status) : 0;
    if (status >= 400 && status < 500) pending.value = null;
    error.value = status ? (e as Error).message : '连接中断，提交结果未确认。请刷新记录或重试同一请求。'; await refresh();
  } finally { if (alive) sending.value = false; }
}
async function showAudit(id: string) {
  if (auditLoading.value) return;
  if (auditId.value === id) { auditId.value = ''; return; }
  auditId.value = id; audit.value = []; auditLoading.value = true; auditError.value = '';
  try { const result = await props.api<{ items: Audit[] }>(`${path}/deployments/${encodeURIComponent(id)}/audit`); if (alive && auditId.value === id) audit.value = result.items; }
  catch (e) { if (alive) auditError.value = e instanceof Error && 'status' in e ? e.message : '无法读取操作记录。'; }
  finally { if (alive) auditLoading.value = false; }
}
onMounted(() => { void refresh(); timer = setInterval(() => { if (document.visibilityState === 'visible' && !sending.value) void refresh(); }, 3000); });
onUnmounted(() => { alive = false; generation++; clearInterval(timer); read?.abort(); });
</script>
<template>
  <section id="remote-ota-panel" class="management-panel" tabindex="-1" @keydown.esc="emit('close')" :aria-label="`${device.name}固件升级`">
    <div class="record-heading">
      <h2>固件升级</h2>
      <div class="management-actions">
        <NButton attr-type="button" class="text-button" :disabled="loading" @click="refresh">
          {{ loading ? '刷新中…' : '刷新状态' }}
        </NButton>
        <NButton attr-type="button" class="text-button" @click="emit('close')">收起</NButton>
      </div>
    </div>
    <p v-if="loadError" class="inline-error" role="alert">{{ loadError }}</p>
    <p v-else-if="loading && !data" role="status">正在读取升级状态…</p>
    <template v-if="data">
      <p v-if="!data.enabled" class="management-hint">远程升级尚未启用。</p>
      <template v-else>
        <p v-if="!data.observation" class="management-hint">尚未收到设备升级状态。</p>
        <div v-else class="management-status">
          <span class="badge" :class="data.fresh ? 'success' : ''">{{ data.fresh ? '状态已更新' : '状态已过期' }}</span>
          <span>{{ names[data.observation.state] || '等待核对' }}</span>
          <span>{{ formatTime(new Date(data.observation.observedAt * 1000).toISOString()) }}</span>
        </div>
        <template v-if="data.observation && phase >= 0">
          <ol class="ota-phases" :aria-label="`升级阶段：${phaseLabel}`">
            <li
              v-for="(label, index) in phases"
              :key="label"
              :class="{ done: index < phase, current: index === phase }"
              :aria-current="index === phase ? 'step' : undefined"
            >
              <i aria-hidden="true"></i>
              {{ label }}
            </li>
          </ol>
          <div
            v-if="writtenPercent !== null"
            class="ota-progress"
            role="progressbar"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="writtenPercent"
            :aria-label="`镜像写入 ${writtenPercent}%`"
          >
            <span :style="{ width: writtenPercent + '%' }"></span>
          </div>
        </template>
        <p v-if="error && !pending" class="inline-error" role="alert">{{ error }}</p>
        <p v-else-if="notice && !pending" class="management-hint" role="status">{{ notice }}</p>
        <p v-if="data.installBlock" class="management-hint" role="status">
          {{ errorNames[data.installBlock] || '请核对设备状态后再安装。' }}
        </p>
        <ConsoleForm class="management-form" @submit.prevent="send('install')">
          <ConsoleField path="field-1" :label-props="{ id: 'remoteotapanel-field-1' + '-label', for: 'remoteotapanel-field-1' }">
            <template #label>可安装版本</template>
            <NSelect
              @keydown.esc.stop
              :disabled="blocked || !data.canInstall"
              v-model:value="releaseId"
              :options="[
                { label: '请选择版本', value: '' },
                ...compatible.map((item) => ({ label: item.target.version + ' · ' + item.releaseId, value: item.releaseId })),
              ]"
              :input-props="{ id: 'remoteotapanel-field-1', 'aria-label': '可安装版本' }"
            />
          </ConsoleField>
          <p v-if="!compatible.length" class="management-hint">没有匹配设备身份且序号更高的版本。</p>
          <StatusNote v-if="pending" tone="warn">
            提交结果未确认，保留原请求，等待核对。
            <template #actions>
              <NButton attr-type="button" class="secondary" :disabled="sending || busy" @click="send(pending.kind)">重试同一请求</NButton>
            </template>
          </StatusNote>
          <div class="form-footer">
            <span>将安装到 {{ device.name }}；安装完成后，需要另行确认重启。</span>
            <NButton
              type="primary"
              attr-type="submit"
              class="primary"
              :disabled="blocked || !data.canInstall || !compatible.some((item) => item.releaseId === releaseId)"
            >
              {{ installLabel }}
            </NButton>
          </div>
        </ConsoleForm>
        <NButton
          attr-type="button"
          class="text-button spaced"
          :disabled="blocked"
          :aria-expanded="adding"
          @click="adding = !adding"
        >
          {{ adding ? '收起版本登记' : '登记版本' }}
        </NButton>
        <ConsoleForm v-if="adding" class="inline-form" @submit.prevent="register">
          <ConsoleField
            path="field-2"
            :rule="fieldRule(() => catalogId, { required: true, pattern: '[a-zA-Z0-9_-]{1,64}', maxlength: 64, disabled: () => blocked })"
            :label-props="{ id: 'remoteotapanel-field-2' + '-label', for: 'remoteotapanel-field-2' }"
          >
            <template #label>发布目录</template>
            <NInput
              :disabled="blocked"
              v-model:value="catalogId"
              :maxlength="64"
              :input-props="{ required: true, pattern: '[a-zA-Z0-9_-]{1,64}', id: 'remoteotapanel-field-2', 'aria-label': '发布目录' }"
            />
          </ConsoleField>
          <ConsoleField
            path="field-3"
            :rule="fieldRule(() => manifest, { required: true, disabled: () => blocked })"
            :label-props="{ id: 'remoteotapanel-field-3' + '-label', for: 'remoteotapanel-field-3' }"
          >
            <template #label>签名 manifest</template>
            <NUpload accept="application/json,.json" :max="1" :default-upload="false" :disabled="blocked" @change="readManifest">
              <NButton attr-type="button" :disabled="blocked">选择 manifest 文件</NButton>
            </NUpload>
          </ConsoleField>
          <p v-if="fileError" class="inline-error" role="alert">{{ fileError }}</p>
          <p class="management-hint">先将签名包部署到设备配置的 HTTPS 发布根，登记后目录不可换包。</p>
          <div class="form-footer">
            <span></span>
            <NButton attr-type="submit" class="secondary" :disabled="blocked || !manifest || !catalogId">登记</NButton>
          </div>
        </ConsoleForm>
        <p v-if="!data.deployments.length" class="management-hint">暂无升级记录。</p>
        <ConsoleDisclosure v-if="data.deployments.length" label="升级记录" class="ota-history" :level="3"
          :summary="`${data.deployments.length} 条`" :force-open="!!pending || !!confirming || data.deployments.some(item => item.status !== 'healthy')">
        <div v-for="item in data.deployments" :key="item.requestId" class="management-record">
          <div class="record-heading">
            <strong>{{ item.target.version }} · {{ item.releaseId }}</strong>
            <span class="badge" :class="item.status === 'healthy' ? 'success' : ''">{{ names[item.status] || '等待核对' }}</span>
          </div>
          <p>
            {{ formatTime(item.createdAt) }}
            <span v-if="data.observation?.requestId === item.requestId && data.observation.writtenBytes">
              · 已写入 {{ Math.floor(data.observation.writtenBytes / 1024) }} / {{ Math.ceil(item.target.imageSize / 1024) }} KB
            </span>
          </p>
          <p v-if="item.lastError" class="management-hint">{{ errorNames[item.lastError] || '请核对设备状态与操作记录。' }}</p>
          <NButton
            attr-type="button"
            v-if="data.canReboot && data.observation?.requestId === item.requestId && !confirming"
            class="secondary compact"
            :disabled="blocked"
            @click="
              confirming = { requestId: item.requestId, bootId: data.observation.bootId };
              confirmed = false;
            "
          >
            重启设备…
          </NButton>
          <ConsoleForm
            v-if="confirming?.requestId === item.requestId"
            class="inline-form confirmation-panel"
            @submit.prevent="send('reboot')"
            @keydown.esc="
              confirming = null;
              confirmed = false;
            "
          >
            <p>重启将中断设备当前任务，随后核验新镜像。</p>
            <NCheckbox class="checkbox-label" v-model:checked="confirmed" :disabled="blocked" :aria-disabled="blocked">确认现在重启 {{ device.name }}</NCheckbox>
            <div class="management-actions">
              <NButton type="primary" attr-type="submit" class="primary" :disabled="blocked || !confirmed || !data.canReboot">
                确认重启
              </NButton>
              <NButton
                attr-type="button"
                class="text-button"
                :disabled="sending"
                @click="
                  confirming = null;
                  confirmed = false;
                "
              >
                取消
              </NButton>
            </div>
          </ConsoleForm>
          <NButton attr-type="button" class="text-button spaced" :disabled="auditLoading" @click="showAudit(item.requestId)">
            {{ auditId === item.requestId ? '收起操作记录' : '查看操作记录' }}
          </NButton>
          <template v-if="auditId === item.requestId">
            <p v-if="auditLoading" role="status">正在读取…</p>
            <p v-if="auditError" class="inline-error" role="alert">{{ auditError }}</p>
            <ol v-else class="audit-list">
              <li v-for="entry in audit" :key="entry.sequence">
                {{ formatTime(entry.createdAt) }} · {{ events[entry.event] || names[entry.event] || '状态更新' }}
                <span v-if="errorNames[entry.detail]">· {{ errorNames[entry.detail] }}</span>
              </li>
            </ol>
          </template>
        </div>
        </ConsoleDisclosure>
      </template>
    </template>
  </section>
</template>
