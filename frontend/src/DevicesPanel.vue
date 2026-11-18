<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NInput, NSelect, NSwitch, NTag } from 'naive-ui';
import { Plus, Settings2, Upload } from '@lucide/vue';
import { useAccess } from './access';
const { admin } = useAccess();
import { computed, nextTick, ref } from 'vue';
import DeviceManagementPanel from './DeviceManagementPanel.vue';
import RemoteOtaPanel from './RemoteOtaPanel.vue';
import { formatRelative, formatTime, type Api, type Device } from './types';
const props = defineProps<{ items: Device[]; state: string; busy: boolean; loading: boolean; failed: boolean; api: Api; register: (id: string, name: string) => Promise<boolean>; enable: (id: string, enabled: boolean) => Promise<boolean>; rename: (id: string, name: string, expectedName: string) => Promise<boolean> }>();
const emit = defineEmits<{ filterState: [value: string] }>();
const adding = ref(false), deviceId = ref(''), name = ref('');
const selectedId = ref('');
const otaId = ref('');
const renameId = ref(''), nameDraft = ref(''), originalName = ref('');
const nameSaving = ref(false);
const editingDevice = computed(() => props.items.find(item => item.deviceId === renameId.value));
const nameConflict = computed(() => !nameSaving.value && !!editingDevice.value && editingDevice.value.name !== originalName.value);
// 在线数放在页头副标题里：列表卡片的在线标记只说明单台，用户还要知道"整体几台在线"。
const onlineCount = computed(() => props.items.filter(device => device.online).length);
// 与概览 devices_online 同一口径：离线只看启用设备，停用的单独一类。
const stateNames: Record<string, string> = { online: '在线', offline: '离线', disabled: '已停用' };
const filtered = computed(() => {
  if (props.state === 'online') return props.items.filter(device => device.online);
  if (props.state === 'offline') return props.items.filter(device => device.enabled && !device.online);
  if (props.state === 'disabled') return props.items.filter(device => !device.enabled);
  return props.items;
});
function loadName() { if (editingDevice.value) nameDraft.value = originalName.value = editingDevice.value.name; }
async function editName(device: Device) {
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
  if (selectedId.value === id) { await closeManagement(id); return; }
  otaId.value = ''; selectedId.value = id; await nextTick(); document.getElementById('device-management-panel')?.focus();
}
async function upgrade(id: string) {
  if (otaId.value === id) { await closeOta(); return; }
  selectedId.value = ''; otaId.value = id; await nextTick(); document.getElementById('remote-ota-panel')?.focus();
}
async function closeOta() { const id = otaId.value; otaId.value = ''; await nextTick(); document.getElementById('device-ota-' + id)?.focus(); }
async function closeManagement(id: string) { selectedId.value = ''; await nextTick(); document.getElementById('device-manage-' + id)?.focus(); }
async function submit() {
  if (await props.register(deviceId.value, name.value)) { adding.value = false; deviceId.value = ''; name.value = ''; }
}
</script>
<template>
  <section>
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">设备</h1>
        <p>{{ state ? `显示 ${filtered.length} / 共 ${items.length} 台` : `${items.length} 台设备` }} · {{ onlineCount }} 台在线 · 连接、提醒与固件</p>
      </div>
      <div class="management-actions">
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
        <NButton type="primary" attr-type="button" v-if="admin" class="primary" @click="adding = !adding" :aria-expanded="adding">
          <template #icon><Plus :size="16" /></template>
          {{ adding ? '收起' : '登记设备' }}
        </NButton>
      </div>
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
    <ConsoleState v-else-if="!failed && !items.length" title="还没有设备">登记设备并完成本机配网后，即可接收提醒。</ConsoleState>
    <ConsoleState v-else-if="!failed && !filtered.length" :title="`没有${stateNames[state] || '符合条件的'}设备`">
      <template #actions>
        <NButton attr-type="button" class="text-button" @click="emit('filterState', '')">查看全部设备</NButton>
      </template>
    </ConsoleState>
    <div v-else-if="!failed" class="device-grid" :aria-busy="loading">
      <article
        v-for="device in filtered"
        :key="device.deviceId"
        class="device-card"
        :data-state="device.online ? 'online' : 'offline'"
        :class="{ expanded: selectedId === device.deviceId || otaId === device.deviceId }"
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
        </div>
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
  </section>
</template>
