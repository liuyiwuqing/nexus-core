<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import DeviceManagementPanel from './DeviceManagementPanel.vue';
import { formatTime, type Api, type Device } from './types';
const props = defineProps<{ items: Device[]; busy: boolean; api: Api; register: (id: string, name: string) => Promise<boolean>; enable: (id: string, enabled: boolean) => Promise<boolean>; rename: (id: string, name: string, expectedName: string) => Promise<boolean> }>();
const adding = ref(false), deviceId = ref(''), name = ref('');
const selectedId = ref(''), selected = computed(() => props.items.find(item => item.deviceId === selectedId.value));
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
async function submit() {
  if (await props.register(deviceId.value, name.value)) { adding.value = false; deviceId.value = ''; name.value = ''; }
}
</script>
<template>
  <section>
    <div class="section-heading"><div><h1>设备</h1><p>管理宠物连接与提醒</p></div><button class="primary" @click="adding = !adding" :aria-expanded="adding">{{ adding ? '收起' : '登记设备' }}</button></div>
    <form v-if="adding" class="inline-form" @submit.prevent="submit">
      <div class="form-columns"><label>设备 ID<input v-model="deviceId" pattern="[a-zA-Z0-9_-]{1,40}" maxlength="40" placeholder="dodo-1" required :disabled="busy" /><small>与设备配网文件中的 ID 一致。</small></label>
        <label>名称<input v-model="name" maxlength="80" placeholder="我的桌面宠物" required :disabled="busy" /></label></div>
      <div class="form-footer"><span>登记后，还需通过 USB 为设备配置连接。</span><button class="primary" :disabled="busy">登记</button></div>
    </form>
    <div v-if="!items.length" class="empty-state"><h2>还没有设备</h2><p>登记设备并完成本机配网后，即可接收提醒。</p></div>
    <div v-else class="device-grid">
      <article v-for="device in items" :key="device.deviceId" class="device-card">
        <div class="device-top"><span class="pet-symbol" aria-hidden="true"><i></i><i></i></span><span class="badge" :class="device.online ? 'success' : ''">{{ device.online ? '在线' : '离线' }}</span></div>
        <div class="device-name-heading"><h2>{{ device.name }}</h2><button v-if="renameId !== device.deviceId" :id="'device-rename-' + device.deviceId" type="button" class="text-button" :disabled="busy" @click="editName(device)">修改名称</button></div><p class="mono">{{ device.deviceId }}</p>
        <form v-if="renameId === device.deviceId" class="device-name-form" @submit.prevent="saveName">
          <label :for="'device-name-' + device.deviceId">设备名称<input :id="'device-name-' + device.deviceId" v-model="nameDraft" required maxlength="80" :disabled="busy" /></label>
          <p v-if="nameConflict" class="inline-error" role="alert">当前名称已变化，你的编辑已保留。</p>
          <div class="management-actions"><button v-if="nameConflict" type="button" class="secondary compact" :disabled="busy" @click="loadName">加载当前名称</button><button v-else class="secondary compact" :disabled="busy || !nameDraft.trim() || nameDraft.trim() === originalName">{{ nameSaving ? '保存中…' : '保存名称' }}</button><button type="button" class="text-button" :disabled="busy" @click="closeName">取消</button></div>
        </form>
        <dl><div><dt>最近连接</dt><dd>{{ formatTime(device.lastSeen) }}</dd></div></dl>
        <label class="switch-row"><span>接收新消息</span><input type="checkbox" :checked="device.enabled" :disabled="busy" @change="enable(device.deviceId, ($event.target as HTMLInputElement).checked)" /></label>
        <button type="button" class="text-button spaced" :aria-expanded="selectedId === device.deviceId" aria-controls="device-management-panel" @click="manage(device.deviceId)">设置与状态</button>
      </article>
    </div>
    <DeviceManagementPanel v-if="selected" :key="selected.deviceId" :device="selected" :api="api" :busy="busy" @close="selectedId = ''" />
  </section>
</template>
