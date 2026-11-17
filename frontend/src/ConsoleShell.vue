<script setup lang="ts">
import { CircleHelp, Menu, RefreshCw, X } from '@lucide/vue';
import { NButton, NDrawer, NDrawerContent, NSelect } from 'naive-ui';
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import ConsoleNavigation from './ConsoleNavigation.vue';
import ThemeToggle from './ThemeToggle.vue';
import { navigationTitle } from './navigation';
import type { Role } from './access';
import type { Device, Tab } from './types';

const props = defineProps<{ tab: Tab; selectedDevice: string; devices: Device[]; role: Role; loading: boolean; connected: boolean }>();
const emit = defineEmits<{ 'update:tab': [tab: Tab]; 'update:selectedDevice': [id: string]; logout: []; guide: []; refresh: [] }>();
const title = computed(() => navigationTitle(props.tab));
const roleName = computed(() => props.role === 'admin' ? '管理员' : props.role === 'operator' ? '操作员 · 消息与规则' : '只读访问');
const menuOpen = ref(false), menuButton = ref<InstanceType<typeof NButton> | null>(null);
// 侧栏折叠是个人工作区的偏好，跟随浏览器保存；隐私模式写入失败时只影响本次会话。
const COLLAPSE_KEY = 'nexus-sidebar-collapsed';
const collapsed = ref(readCollapsed());
function readCollapsed() { try { return localStorage.getItem(COLLAPSE_KEY) === '1'; } catch { return false; } }
function toggleCollapse() {
  collapsed.value = !collapsed.value;
  try { localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0'); } catch { /* 无持久化时保持内存状态 */ }
}
let navigating = false;
const breakpoint = window.matchMedia('(min-width: 761px)');
function resize() { if (breakpoint.matches) menuOpen.value = false; }
onMounted(() => breakpoint.addEventListener('change', resize));
onUnmounted(() => breakpoint.removeEventListener('change', resize));
// 面板是异步 chunk，标题可能晚一拍才挂载；按有限次轮询等它出现，同时用 token 让后一次导航作废前一次。
let focusToken = 0;
async function focusContent() {
  const token = ++focusToken;
  for (let attempt = 0; attempt < 60; attempt++) {
    if (token !== focusToken) return;
    const heading = document.querySelector<HTMLElement>('.content h1');
    if (heading) { heading.focus(); return; }
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}
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
function selectDevice(value: string) { emit('update:selectedDevice', value); void navigate('devices'); }
</script>

<template>
  <div class="app-layout" :class="{ 'is-collapsed': collapsed }">
    <a class="skip-link" href="#main-content">跳到主内容</a>
    <ConsoleNavigation
      :tab="tab"
      :role="role"
      :collapsed="collapsed"
      @navigate="navigate"
      @logout="emit('logout')"
      @toggle-collapse="toggleCollapse"
    />
    <NDrawer v-model:show="menuOpen" placement="left" :width="280" :close-on-esc="true" aria-label="主导航" @after-leave="afterLeave">
      <NDrawerContent :native-scrollbar="true" body-content-style="padding: 0" class="mobile-navigation">
        <NButton class="close-navigation" square aria-label="关闭导航" @click="menuOpen = false"><X :size="20" /></NButton>
        <ConsoleNavigation :tab="tab" :role="role" @navigate="navigate" @logout="emit('logout')" />
      </NDrawerContent>
    </NDrawer>
    <div class="workspace">
      <!-- 单层页头：左侧是位置，右侧是设备视图与全局动作。页面自己的主操作留在页面标题右侧。 -->
      <header class="topbar">
        <div class="workspace-heading">
          <NButton
            ref="menuButton"
            class="mobile-menu" square
            aria-label="打开导航"
            :aria-expanded="menuOpen"
            @click="menuOpen = true"
          >
            <Menu :size="20" />
          </NButton>
          <span class="breadcrumb">
            管理台
            <span aria-hidden="true">/</span>
            <strong>{{ title }}</strong>
          </span>
        </div>
        <div class="topbar-tools">
          <div class="device-view">
            <NSelect
              @keydown.esc.stop
              size="small"
              aria-label="设备视图"
              :value="selectedDevice"
              @update:value="selectDevice"
              :options="[{ label: '全部设备', value: '' }, ...devices.map((device) => ({ label: device.name, value: device.deviceId }))]"
            />
          </div>
          <span class="topbar-divider" aria-hidden="true"></span>
          <NButton class="text-button" @click="emit('guide')">
            <template #icon><CircleHelp :size="16" /></template>
            接入引导
          </NButton>
          <NButton
            class="text-button"
            :disabled="loading"
            :aria-busy="loading"
            :aria-label="loading ? '正在刷新' : '刷新'"
            @click="emit('refresh')"
          >
            <template #icon><RefreshCw :size="16" :class="{ spinning: loading }" /></template>
            刷新
          </NButton>
          <span class="topbar-divider connection-divider" aria-hidden="true"></span>
          <span class="connection-status" :class="connected ? 'connected' : 'disconnected'">
            <i></i>
            {{ connected ? '消息连接正常' : '消息连接不可用' }}
          </span>
          <span class="session-role">{{ roleName }}</span>
          <ThemeToggle />
        </div>
      </header>
      <slot />
    </div>
  </div>
</template>
