<script setup lang="ts">
import { NButton, NSwitch, NTag } from 'naive-ui';
import { Plus, Settings2, Upload } from '@lucide/vue';
import { useAccess } from './access';
const { admin } = useAccess();
import { computed, nextTick, ref } from 'vue';
import DeviceManagementPanel from './DeviceManagementPanel.vue';
import RemoteOtaPanel from './RemoteOtaPanel.vue';
import { formatTime, type Api, type Device } from './types';
const props = defineProps<{ items: Device[]; busy: boolean; api: Api; register: (id: string, name: string) => Promise<boolean>; enable: (id: string, enabled: boolean) => Promise<boolean>; rename: (id: string, name: string, expectedName: string) => Promise<boolean> }>();
const adding = ref(false), deviceId = ref(''), name = ref('');
const selectedId = ref(''), selected = computed(() => props.items.find(item => item.deviceId === selectedId.value));
const otaId = ref(''), otaDevice = computed(() => props.items.find(item => item.deviceId === otaId.value));
const renameId = ref(''), nameDraft = ref(''), originalName = ref('');
const nameSaving = ref(false);
const editingDevice = computed(() => props.items.find(item => item.deviceId === renameId.value));
const nameConflict = computed(() => !nameSaving.value && !!editingDevice.value && editingDevice.value.name !== originalName.value);
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
async function manage(id: string) { selectedId.value = id; await nextTick(); document.getElementById('device-management-panel')?.focus(); }
async function upgrade(id: string) { otaId.value = id; await nextTick(); document.getElementById('remote-ota-panel')?.focus(); }
async function closeOta() { const id = otaId.value; otaId.value = ''; await nextTick(); document.getElementById('device-ota-' + id)?.focus(); }
async function submit() {
  if (await props.register(deviceId.value, name.value)) { adding.value = false; deviceId.value = ''; name.value = ''; }
}
</script>
<template>
  <section>
    <div class="section-heading"><div><h1 tabindex="-1">设备</h1><p>{{ items.length }} 台设备 · 管理连接、提醒与固件</p></div><NButton type="primary" attr-type="button" v-if="admin" class="primary" @click="adding = !adding" :aria-expanded="adding"><template #icon><Plus :size="16" /></template>{{ adding ? '收起' : '登记设备' }}</NButton></div>
    <form v-if="adding" class="inline-form" @submit.prevent="submit">
      <div class="form-columns"><label>设备 ID<input v-model="deviceId" pattern="[a-zA-Z0-9_-]{1,40}" maxlength="40" placeholder="dodo-1" required :disabled="busy" /><small>与设备配网文件中的 ID 一致。</small></label>
        <label>名称<input v-model="name" maxlength="80" placeholder="我的桌面宠物" required :disabled="busy" /></label></div>
      <div class="form-footer"><span>登记后，还需通过 USB 为设备配置连接。</span><NButton type="primary" attr-type="submit" class="primary" :disabled="busy">登记</NButton></div>
    </form>
    <div v-if="!items.length" class="empty-state"><h2>还没有设备</h2><p>登记设备并完成本机配网后，即可接收提醒。</p></div>
    <div v-else class="device-grid">
      <article v-for="device in items" :key="device.deviceId" class="device-card">
        <div class="device-top"><span class="pet-symbol" aria-hidden="true"><i></i><i></i></span><NTag :type="device.online ? 'success' : 'default'" :bordered="false" size="small">{{ device.online ? '在线' : '离线' }}</NTag></div>
        <div class="device-name-heading"><h2>{{ device.name }}</h2><NButton quaternary attr-type="button" v-if="admin && renameId !== device.deviceId" :id="'device-rename-' + device.deviceId" class="text-button" :disabled="busy" @click="editName(device)">修改名称</NButton></div><p class="mono">{{ device.deviceId }}</p>
        <form v-if="renameId === device.deviceId" class="device-name-form" @submit.prevent="saveName">
          <label :for="'device-name-' + device.deviceId">设备名称<input :id="'device-name-' + device.deviceId" v-model="nameDraft" required maxlength="80" :disabled="busy" /></label>
          <p v-if="nameConflict" class="inline-error" role="alert">当前名称已变化，你的编辑已保留。</p>
          <div class="management-actions"><NButton size="small" attr-type="button" v-if="nameConflict" class="secondary compact" :disabled="busy" @click="loadName">加载当前名称</NButton><NButton size="small" attr-type="submit" v-else class="secondary compact" :disabled="busy || !nameDraft.trim() || nameDraft.trim() === originalName">{{ nameSaving ? '保存中…' : '保存名称' }}</NButton><NButton quaternary attr-type="button" class="text-button" :disabled="busy" @click="closeName">取消</NButton></div>
        </form>
        <dl><div><dt>最近连接</dt><dd>{{ formatTime(device.lastSeen) }}</dd></div></dl>
        <div class="switch-row"><span :id="'device-messages-' + device.deviceId">接收新消息</span><NSwitch :aria-labelledby="'device-messages-' + device.deviceId" :aria-disabled="busy || !admin" :value="device.enabled" :disabled="busy || !admin" @update:value="enable(device.deviceId, $event)" /></div>
        <div class="device-actions"><NButton attr-type="button" class="secondary" :aria-expanded="selectedId === device.deviceId" aria-controls="device-management-panel" @click="manage(device.deviceId)"><template #icon><Settings2 :size="15" /></template>设置与状态</NButton>
        <NButton quaternary attr-type="button" :id="'device-ota-' + device.deviceId" class="text-button" :aria-expanded="otaId === device.deviceId" aria-controls="remote-ota-panel" @click="upgrade(device.deviceId)"><template #icon><Upload :size="15" /></template>固件升级</NButton></div>
      </article>
    </div>
    <RemoteOtaPanel v-if="otaDevice" :key="otaDevice.deviceId" :device="otaDevice" :api="api" :busy="busy" @close="closeOta" />
    <DeviceManagementPanel v-if="selected" :key="selected.deviceId" :device="selected" :api="api" :busy="busy" @close="selectedId = ''" />
  </section>
</template>
