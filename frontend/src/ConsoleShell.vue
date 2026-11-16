<script setup lang="ts">
import { CircleHelp, Menu, RefreshCw, X } from '@lucide/vue';
import { NButton, NDrawer, NDrawerContent } from 'naive-ui';
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import ConsoleNavigation from './ConsoleNavigation.vue';
import { navigationTitle } from './navigation';
import type { Role } from './access';
import type { Device, Tab } from './types';

const props = defineProps<{ tab: Tab; selectedDevice: string; devices: Device[]; role: Role; loading: boolean; connected: boolean }>();
const emit = defineEmits<{ 'update:tab': [tab: Tab]; 'update:selectedDevice': [id: string]; logout: []; guide: []; refresh: [] }>();
const title = computed(() => navigationTitle(props.tab));
const menuOpen = ref(false), menuButton = ref<InstanceType<typeof NButton> | null>(null);
let navigating = false;
const breakpoint = window.matchMedia('(min-width: 761px)');
function resize() { if (breakpoint.matches) menuOpen.value = false; }
onMounted(() => breakpoint.addEventListener('change', resize));
onUnmounted(() => breakpoint.removeEventListener('change', resize));
function focusContent() { document.querySelector<HTMLElement>('.content h1')?.focus(); }
async function navigate(tab: Tab) {
  emit('update:tab', tab);
  if (menuOpen.value) { navigating = true; menuOpen.value = false; }
  else { await nextTick(); focusContent(); }
}
async function afterLeave() {
  // The drawer's focus trap restores focus when it unmounts after the transition.
  // Wait for that render before moving keyboard users into the chosen page.
  await nextTick();
  if (navigating) { navigating = false; focusContent(); }
  else menuButton.value?.$el.focus();
}
function selectDevice(event: Event) { emit('update:selectedDevice', (event.target as HTMLSelectElement).value); void navigate('devices'); }
</script>

<template>
  <div class="app-layout">
    <ConsoleNavigation :tab="tab" :role="role" @navigate="navigate" @logout="emit('logout')" />
    <NDrawer v-model:show="menuOpen" placement="left" :width="280" :close-on-esc="true" aria-label="主导航" @after-leave="afterLeave">
      <NDrawerContent :native-scrollbar="true" body-content-style="padding: 0" class="mobile-navigation">
        <NButton class="close-navigation" quaternary circle aria-label="关闭导航" @click="menuOpen = false"><X :size="20" /></NButton>
        <ConsoleNavigation :tab="tab" :role="role" @navigate="navigate" @logout="emit('logout')" />
      </NDrawerContent>
    </NDrawer>
    <div class="workspace">
      <header class="topbar">
        <div class="workspace-heading"><NButton ref="menuButton" class="mobile-menu" quaternary circle aria-label="打开导航" :aria-expanded="menuOpen" @click="menuOpen = true"><Menu :size="20" /></NButton><span class="breadcrumb">管理台<span aria-hidden="true">/</span><strong>{{ title }}</strong></span></div>
        <div class="workspace-actions"><span class="connection-status" :class="connected ? 'connected' : 'disconnected'"><i></i>{{ connected ? '消息连接正常' : '消息连接不可用' }}</span><span class="session-role">{{ role === 'admin' ? '管理员' : role === 'operator' ? '操作员 · 消息与规则' : '只读访问' }}</span></div>
      </header>
      <div class="workspace-toolbar"><label class="device-view"><span class="sr-only">设备视图</span><select aria-label="设备视图" :value="selectedDevice" @change="selectDevice"><option value="">全部设备</option><option v-for="device in devices" :key="device.deviceId" :value="device.deviceId">{{ device.name }}</option></select></label><div class="toolbar-actions"><NButton quaternary class="text-button" @click="emit('guide')"><template #icon><CircleHelp :size="16" /></template>接入引导</NButton><NButton quaternary class="text-button" :disabled="loading" @click="emit('refresh')"><template #icon><RefreshCw :size="16" :class="{ spinning: loading }" /></template>{{ loading ? '刷新中…' : '刷新' }}</NButton></div></div>
      <slot />
    </div>
  </div>
</template>
