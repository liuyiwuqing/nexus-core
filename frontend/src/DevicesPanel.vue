<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NInput, NSelect, NSwitch, NTag } from 'naive-ui';
import { Plus, Search, Settings2, Upload } from '@lucide/vue';
import { useAccess } from './access';
const { admin } = useAccess();
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import DeviceManagementPanel from './DeviceManagementPanel.vue';
import DeviceQueuePanel from './DeviceQueuePanel.vue';
import RemoteOtaPanel from './RemoteOtaPanel.vue';
import { formatRelative, formatTime, type Api, type Device } from './types';
const props = defineProps<{ items: Device[]; total: number; matched: number; onlineCount: number; page: number; size: number; state: string; q: string; sort: string; order: string; busy: boolean; loading: boolean; failed: boolean; api: Api; register: (id: string, name: string) => Promise<boolean>; enable: (id: string, enabled: boolean) => Promise<boolean>; rename: (id: string, name: string, expectedName: string) => Promise<boolean>; remove: (id: string) => Promise<boolean> }>();
const emit = defineEmits<{ filterState: [value: string]; search: [value: string]; sort: [sort: string, order: string]; size: [size: number]; page: [page: number]; deliveries: [deviceId: string]; queueChanged: [] }>();
// 搜索输入本地防抖 300ms 后才请求；排序与页大小立即生效并回到第一页。
const searchDraft = ref(props.q);
watch(() => props.q, value => { if (value !== searchDraft.value) searchDraft.value = value; });
let searchTimer: ReturnType<typeof setTimeout> | undefined;
function applySearch() {
  clearTimeout(searchTimer);
  const value = searchDraft.value.trim();
  if (value !== props.q) emit('search', value);
}
function scheduleSearch() {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(applySearch, 300);
}
onBeforeUnmount(() => clearTimeout(searchTimer));
const sortValue = computed(() => `${props.sort}_${props.order}`);
const sortOptions = [
  { label: '创建时间：早的在前', value: 'created_asc' },
  { label: '创建时间：晚的在前', value: 'created_desc' },
  { label: '名称：A→Z', value: 'name_asc' },
  { label: '名称：Z→A', value: 'name_desc' },
  { label: '最近连接：近的在前', value: 'last_seen_desc' },
  { label: '最近连接：远的在前', value: 'last_seen_asc' },
];
function updateSort(value: string) {
  const index = value.lastIndexOf('_');
  emit('sort', value.slice(0, index), value.slice(index + 1));
}
const sizeOptions = [12, 24, 48, 96].map(value => ({ label: `每页 ${value} 台`, value }));
const adding = ref(false), deviceId = ref(''), name = ref('');
const selectedId = ref('');
const otaId = ref('');
const queueId = ref('');
const renameId = ref(''), nameDraft = ref(''), originalName = ref('');
const nameSaving = ref(false);
const deleteId = ref(''), deleteDraft = ref(''), deleteSaving = ref(false);
const editingDevice = computed(() => props.items.find(item => item.deviceId === renameId.value));
const deletingDevice = computed(() => props.items.find(item => item.deviceId === deleteId.value));
const nameConflict = computed(() => !nameSaving.value && !!editingDevice.value && editingDevice.value.name !== originalName.value);
// 状态、总数与在线数都由服务端按整张设备表统计；筛选与翻页后端完成，这里不再二次过滤。
const stateNames: Record<string, string> = { online: '在线', offline: '离线', disabled: '已停用' };
const lastPage = computed(() => Math.max(1, Math.ceil(props.matched / props.size)));
function loadName() { if (editingDevice.value) nameDraft.value = originalName.value = editingDevice.value.name; }
async function editName(device: Device) {
  deleteId.value = ''; queueId.value = '';
  renameId.value = device.deviceId; loadName(); await nextTick();
  document.getElementById('device-name-' + device.deviceId)?.focus();
}
async function closeName() {
  const id = renameId.value; renameId.value = ''; await nextTick();
  document.getElementById('device-rename-' + id)?.focus();
}
async function saveName() {
  if (props.busy || nameConflict.value || !nameDraft.value.trim()) return;
  nameSaving.value = true;
  try { if (await props.rename(renameId.value, nameDraft.value, originalName.value)) await closeName(); }
  finally { nameSaving.value = false; }
}
// 设置与升级互斥：同开会让 Tab 从升级按钮串进设置面板，卡内还会出现两组同名的刷新/收起按钮。
async function manage(id: string) {
  deleteId.value = ''; queueId.value = '';
  if (selectedId.value === id) { await closeManagement(id); return; }
  otaId.value = ''; selectedId.value = id; await nextTick(); document.getElementById('device-management-panel')?.focus();
}
async function upgrade(id: string) {
  deleteId.value = ''; queueId.value = '';
  if (otaId.value === id) { await closeOta(); return; }
  selectedId.value = ''; otaId.value = id; await nextTick(); document.getElementById('remote-ota-panel')?.focus();
}
// 撤销待发与设置/升级/删除互斥，避免同一张卡里出现多组撤销确认。
async function openQueue(device: Device) {
  selectedId.value = ''; otaId.value = ''; deleteId.value = '';
  if (queueId.value === device.deviceId) { await closeQueue(); return; }
  queueId.value = device.deviceId; await nextTick(); document.getElementById('device-queue-panel')?.focus();
}
async function closeQueue() {
  const id = queueId.value; queueId.value = ''; await nextTick();
  document.getElementById('device-queue-' + id)?.focus();
}
async function closeOta() { const id = otaId.value; otaId.value = ''; await nextTick(); document.getElementById('device-ota-' + id)?.focus(); }
async function closeManagement(id: string) { selectedId.value = ''; await nextTick(); document.getElementById('device-manage-' + id)?.focus(); }
// 删除是不可逆操作：必须输入完整设备名称，且只对从未使用、未被引用的设备开放。
async function askDelete(device: Device) {
  selectedId.value = ''; otaId.value = ''; queueId.value = '';
  deleteId.value = device.deviceId; deleteDraft.value = ''; await nextTick();
  document.getElementById('devicespanel-field-4')?.focus();
}
async function closeDelete() {
  const id = deleteId.value; deleteId.value = ''; deleteDraft.value = ''; await nextTick();
  document.getElementById('device-delete-' + id)?.focus();
}
async function confirmDelete() {
  const device = deletingDevice.value;
  if (!device || props.busy || deleteSaving.value || deleteDraft.value !== device.name) return;
  deleteSaving.value = true;
  try { if (await props.remove(device.deviceId)) await closeDelete(); }
  finally { deleteSaving.value = false; }
}
async function submit() {
  if (await props.register(deviceId.value, name.value)) { adding.value = false; deviceId.value = ''; name.value = ''; }
}
</script>
<template>
  <section>
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">设备</h1>
        <p>{{ state || q ? `显示 ${matched} / 共 ${total} 台` : `${total} 台设备` }} · {{ onlineCount }} 台在线 · 连接、提醒与固件</p>
      </div>
      <div class="management-actions">
        <NButton type="primary" attr-type="button" v-if="admin" class="primary" @click="adding = !adding" :aria-expanded="adding">
          <template #icon><Plus :size="16" /></template>
          {{ adding ? '收起' : '登记设备' }}
        </NButton>
      </div>
    </div>
    <div class="device-filters">
      <NInput
        class="device-search"
        :value="searchDraft"
        :disabled="failed"
        :maxlength="80"
        clearable
        placeholder="搜索名称或设备 ID"
        :input-props="{ type: 'search', 'aria-label': '搜索设备', spellcheck: 'false', id: 'devicespanel-search' }"
        @update:value="searchDraft = $event; scheduleSearch()"
        @keydown.enter.prevent="applySearch"
        @clear="searchDraft = ''; applySearch()"
      >
        <template #prefix><Search :size="15" aria-hidden="true" /></template>
      </NInput>
      <NSelect
        class="device-state-filter"
        aria-label="设备状态"
        :disabled="loading || failed"
        :value="state"
        :options="[
          { label: '全部', value: '' },
          { label: '在线', value: 'online' },
          { label: '离线', value: 'offline' },
          { label: '已停用', value: 'disabled' },
        ]"
        :input-props="{ id: 'devicespanel-state', 'aria-label': '设备状态' }"
        @update:value="emit('filterState', $event)"
      />
      <NSelect
        class="device-sort-filter"
        aria-label="设备排序"
        :disabled="loading || failed"
        :value="sortValue"
        :options="sortOptions"
        :input-props="{ id: 'devicespanel-sort', 'aria-label': '设备排序' }"
        @update:value="updateSort"
      />
      <NSelect
        class="device-size-filter"
        aria-label="每页设备数"
        :disabled="loading || failed"
        :value="size"
        :options="sizeOptions"
        :input-props="{ id: 'devicespanel-size', 'aria-label': '每页设备数' }"
        @update:value="emit('size', $event)"
      />
    </div>
    <ConsoleForm v-if="adding" class="inline-form" @submit.prevent="submit">
      <div class="form-columns">
        <ConsoleField
          path="field-1"
          :rule="fieldRule(() => deviceId, { required: true, pattern: '[a-zA-Z0-9_-]{1,40}', maxlength: 40, disabled: () => busy })"
          :label-props="{ id: 'devicespanel-field-1' + '-label', for: 'devicespanel-field-1' }"
        >
          <template #label>设备 ID</template>
          <NInput
            placeholder="dodo-1"
            :disabled="busy"
            v-model:value="deviceId"
            :maxlength="40"
            :input-props="{ required: true, pattern: '[a-zA-Z0-9_-]{1,40}', id: 'devicespanel-field-1', 'aria-label': '设备 ID' }"
          />
          <small>与设备配网文件中的 ID 一致。</small>
        </ConsoleField>
        <ConsoleField
          path="field-2"
          :rule="fieldRule(() => name, { required: true, maxlength: 80, disabled: () => busy })"
          :label-props="{ id: 'devicespanel-field-2' + '-label', for: 'devicespanel-field-2' }"
        >
          <template #label>名称</template>
          <NInput
            placeholder="我的桌面宠物"
            :disabled="busy"
            v-model:value="name"
            :maxlength="80"
            :input-props="{ required: true, id: 'devicespanel-field-2', 'aria-label': '名称' }"
          />
        </ConsoleField>
      </div>
      <div class="form-footer">
        <span>登记后，还需通过 USB 为设备配置连接。</span>
        <NButton type="primary" attr-type="submit" class="primary" :disabled="busy">登记</NButton>
      </div>
    </ConsoleForm>
    <ConsoleState v-if="loading && !items.length && !failed" kind="loading">正在读取设备…</ConsoleState>
    <ConsoleState v-else-if="!failed && !items.length && (q || state)" :title="q ? `没有匹配“${q}”的设备` : `没有${stateNames[state] || '符合条件的'}设备`">
      <template #actions>
        <NButton v-if="q" attr-type="button" class="text-button" @click="searchDraft = ''; applySearch()">清空搜索</NButton>
        <NButton v-if="state" attr-type="button" class="text-button" @click="emit('filterState', '')">查看全部设备</NButton>
      </template>
    </ConsoleState>
    <ConsoleState v-else-if="!failed && !items.length && total > 0" title="本页没有设备">刷新后会回到最近的可用页。</ConsoleState>
    <ConsoleState v-else-if="!failed && !items.length" title="还没有设备">登记设备并完成本机配网后，即可接收提醒。</ConsoleState>
    <div v-else-if="!failed" class="device-grid" :aria-busy="loading">
      <article
        v-for="device in items"
        :key="device.deviceId"
        class="device-card"
        :data-state="device.online ? 'online' : 'offline'"
        :class="{ expanded: selectedId === device.deviceId || otaId === device.deviceId || deleteId === device.deviceId || queueId === device.deviceId }"
      >
        <div class="device-top">
          <span class="pet-symbol" aria-hidden="true">
            <i></i>
            <i></i>
          </span>
          <div class="device-identity">
            <div class="device-name-heading">
              <h2>{{ device.name }}</h2>
            </div>
            <p class="mono device-id">{{ device.deviceId }}</p>
          </div>
          <NTag :type="device.online ? 'success' : 'default'" :bordered="false" size="small">
            <i class="status-dot" aria-hidden="true"></i>
            {{ device.online ? '在线' : '离线' }}
          </NTag>
        </div>
        <p class="device-seen">
          <span>最近连接</span>
          <strong>{{ formatRelative(device.lastSeen) }}</strong>
          <span v-if="device.lastSeen">{{ formatTime(device.lastSeen) }}</span>
        </p>
        <p v-if="device.wifiSsid || device.wifiIp" class="device-seen">
          <span>Wi-Fi</span>
          <strong>{{ device.wifiSsid || device.wifiIp }}</strong>
          <span v-if="device.wifiSsid && device.wifiIp">{{ device.wifiIp }}</span>
          <span v-if="device.wifiRssi !== null && device.wifiRssi !== undefined">{{ device.wifiRssi }} dBm</span>
          <span v-if="device.telemetryAt">· {{ formatRelative(device.telemetryAt) }}</span>
        </p>
        <p v-if="device.firmwareVersion" class="device-seen">
          <span>固件</span>
          <strong>{{ device.firmwareVersion }}</strong>
        </p>
        <p v-if="device.waitingDeliveries" class="device-seen">
          <span>待接收</span>
          <NButton
            attr-type="button"
            class="text-button device-waiting"
            :aria-label="`查看 ${device.name} 待接收的投递`"
            @click="emit('deliveries', device.deviceId)"
          >
            {{ device.waitingDeliveries }} 条
          </NButton>
          <NButton
            v-if="admin"
            attr-type="button"
            :id="'device-queue-' + device.deviceId"
            class="text-button device-queue"
            :aria-expanded="queueId === device.deviceId"
            aria-controls="device-queue-panel"
            @click="openQueue(device)"
          >
            撤销待发…
          </NButton>
        </p>
        <ConsoleForm v-if="renameId === device.deviceId" class="device-name-form" @submit.prevent="saveName">
          <ConsoleField
            path="field-3"
            :rule="fieldRule(() => nameDraft, { required: true, maxlength: 80, disabled: () => busy })"
            :label-props="{ id: 'devicespanel-field-3' + '-label', for: 'devicespanel-field-3' }"
          >
            <template #label>设备名称</template>
            <NInput
              :disabled="busy"
              v-model:value="nameDraft"
              :maxlength="80"
              :input-props="{ required: true, id: 'devicespanel-field-3', 'aria-label': '设备名称' }"
            />
          </ConsoleField>
          <ConsoleState v-if="nameConflict" kind="error" compact>当前名称已变化，你的编辑已保留。</ConsoleState>
          <div class="management-actions">
            <NButton attr-type="button" v-if="nameConflict" class="secondary compact" :disabled="busy" @click="loadName">
              加载当前名称
            </NButton>
            <NButton
              attr-type="submit"
              v-else
              class="secondary compact"
              :disabled="busy || !nameDraft.trim() || nameDraft.trim() === originalName"
            >
              {{ nameSaving ? '保存中…' : '保存名称' }}
            </NButton>
            <NButton attr-type="button" class="text-button" :disabled="busy" @click="closeName">取消</NButton>
          </div>
        </ConsoleForm>
        <div class="switch-row">
          <span :id="'device-messages-' + device.deviceId">接收新消息</span>
          <NSwitch
            :aria-labelledby="'device-messages-' + device.deviceId"
            :aria-disabled="busy || !admin"
            :value="device.enabled"
            :disabled="busy || !admin"
            @update:value="enable(device.deviceId, $event)"
          />
        </div>
        <div class="device-actions">
          <NButton
            attr-type="button"
            :id="'device-manage-' + device.deviceId"
            class="secondary"
            :aria-expanded="selectedId === device.deviceId"
            aria-controls="device-management-panel"
            @click="manage(device.deviceId)"
          >
            <template #icon><Settings2 :size="15" /></template>
            设置与状态
          </NButton>
          <NButton
            attr-type="button"
            :id="'device-ota-' + device.deviceId"
            class="text-button"
            :aria-expanded="otaId === device.deviceId"
            aria-controls="remote-ota-panel"
            @click="upgrade(device.deviceId)"
          >
            <template #icon><Upload :size="15" /></template>
            固件升级
          </NButton>
          <NButton
            attr-type="button"
            v-if="admin && renameId !== device.deviceId"
            :id="'device-rename-' + device.deviceId"
            class="text-button action-end"
            :disabled="busy"
            @click="editName(device)"
          >
            修改名称
          </NButton>
          <NButton
            attr-type="button"
            v-if="admin && renameId !== device.deviceId"
            :id="'device-delete-' + device.deviceId"
            class="text-button"
            :disabled="busy"
            :aria-expanded="deleteId === device.deviceId"
            aria-controls="device-delete-form"
            @click="askDelete(device)"
          >
            删除设备
          </NButton>
        </div>
        <ConsoleForm v-if="deleteId === device.deviceId" id="device-delete-form" class="device-name-form" @submit.prevent="confirmDelete">
          <p class="device-delete-hint">仅可删除从未产生消息、遥测、配置、升级、成长或凭据历史，且未被路由/来源引用的设备；有历史请停用。</p>
          <ConsoleField
            path="field-4"
            :rule="fieldRule(() => deleteDraft, { required: true, maxlength: 80, disabled: () => busy || deleteSaving })"
            :label-props="{ id: 'devicespanel-field-4' + '-label', for: 'devicespanel-field-4' }"
          >
            <template #label>输入设备名称确认</template>
            <NInput
              :disabled="busy || deleteSaving"
              v-model:value="deleteDraft"
              :maxlength="80"
              :placeholder="device.name"
              :input-props="{ required: true, id: 'devicespanel-field-4', 'aria-label': '输入设备名称确认', spellcheck: 'false' }"
            />
          </ConsoleField>
          <div class="management-actions">
            <NButton
              type="error"
              attr-type="submit"
              class="primary"
              :disabled="busy || deleteSaving || deleteDraft !== device.name"
            >
              {{ deleteSaving ? '正在删除…' : '确认删除' }}
            </NButton>
            <NButton attr-type="button" class="text-button" :disabled="busy || deleteSaving" @click="closeDelete">取消</NButton>
          </div>
        </ConsoleForm>
        <DeviceQueuePanel
          v-if="queueId === device.deviceId"
          :key="'queue-' + device.deviceId"
          :device="device"
          :api="api"
          :busy="busy"
          @close="closeQueue"
          @changed="emit('queueChanged')"
        />
        <DeviceManagementPanel
          v-if="selectedId === device.deviceId"
          :key="'manage-' + device.deviceId"
          :device="device"
          :api="api"
          :busy="busy"
          @close="closeManagement(device.deviceId)"
        />
        <RemoteOtaPanel
          v-if="otaId === device.deviceId"
          :key="'ota-' + device.deviceId"
          :device="device"
          :api="api"
          :busy="busy"
          @close="closeOta"
        />
      </article>
    </div>
    <div v-if="!failed && items.length && matched > size" class="pagination device-pagination">
      <NButton attr-type="button" class="text-button" :disabled="loading || busy || page <= 1" @click="emit('page', page - 1)">上一页</NButton>
      <span>{{ page }} / {{ lastPage }}</span>
      <NButton attr-type="button" class="text-button" :disabled="loading || busy || page >= lastPage" @click="emit('page', page + 1)">下一页</NButton>
    </div>
  </section>
</template>
