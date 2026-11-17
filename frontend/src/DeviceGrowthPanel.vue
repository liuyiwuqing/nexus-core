<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import { NButton } from 'naive-ui';
import { onUnmounted, ref } from 'vue';
import { formatTime, type Api } from './types';
import { readFailure } from './failure';
interface Growth {
  level: number; experience: number; experienceToNext: number; coins: number; intimacy: number;
  energy: number; hunger: number; health: number; state: string; timeReady: boolean;
  unsaved: boolean; storageReady: boolean; saveRecovered: boolean;
}
interface DeviceGrowth {
  deviceId: string; name: string; deviceEnabled: boolean; fresh: boolean;
  snapshot: { observedAt: number; growth: Growth } | null;
}
const props = defineProps<{ api: Api }>();
const items = ref<DeviceGrowth[]>([]), enabled = ref(false), loaded = ref(false), loading = ref(false), error = ref('');
const names: Record<string,string> = { idle: '平静', happy: '开心', excited: '兴奋', sad: '低落', sleepy: '困倦', hungry: '饥饿', sick: '不适', sleeping: '睡眠中' };
let generation = 0, controller: AbortController | undefined;
async function load() {
  controller?.abort(); controller = new AbortController(); const request = ++generation; loading.value = true;
  try {
    const result = await props.api<{enabled: boolean; items: DeviceGrowth[]}>('pet/devices','GET',undefined,controller.signal);
    if (request === generation) { enabled.value = result.enabled; items.value = result.items; loaded.value = true; error.value = ''; }
  } catch (failure) {
    if (request === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) error.value = readFailure(failure, '读取设备成长数据失败。');
  } finally { if (request === generation) loading.value = false; }
}
void load();
const poll = setInterval(() => { if (!loading.value && document.visibilityState === 'visible') void load(); },15000);
onUnmounted(() => { generation++; controller?.abort(); clearInterval(poll); });
</script>

<template>
  <section class="record-card" aria-labelledby="device-growth-title" :aria-busy="loading">
    <div class="record-heading"><h2 id="device-growth-title">设备成长</h2><NButton attr-type="button" class="text-button" :disabled="loading" @click="load">刷新设备成长</NButton></div>
    <ConsoleState v-if="error" kind="error" compact>{{ error }}</ConsoleState>
    <ConsoleState v-if="!loaded && loading && !error" kind="loading" compact>正在读取设备成长…</ConsoleState>
    <ConsoleState v-else-if="loaded && !error && !enabled" kind="info" compact>设备成长同步未启用。</ConsoleState>
    <ConsoleState v-else-if="loaded && !error && !items.length" compact>暂无已注册设备。</ConsoleState>
    <div v-else class="record-list">
      <article v-for="item in items" :key="item.deviceId" class="record-card">
        <div class="record-heading"><h3>{{ item.name }}</h3><span class="badge" :class="item.fresh && !error && item.deviceEnabled ? 'success' : ''">{{ !item.deviceEnabled ? '设备已禁用' : !item.snapshot ? '等待同步' : error ? '刷新失败' : item.fresh ? '已同步' : '数据已过期' }}</span></div>
        <template v-if="item.snapshot">
          <dl class="pet-stats"><div><dt>等级</dt><dd>{{ item.snapshot.growth.level }}</dd></div><div><dt>金币</dt><dd>{{ item.snapshot.growth.coins }}</dd></div><div><dt>亲密度</dt><dd>{{ item.snapshot.growth.intimacy }}</dd></div><div><dt>状态</dt><dd>{{ names[item.snapshot.growth.state] || '未知' }}</dd></div></dl>
          <div class="growth-progress"><progress :value="item.snapshot.growth.experience" :max="item.snapshot.growth.experienceToNext" :aria-label="`${item.name} 本级经验 ${item.snapshot.growth.experience}`"></progress><small>{{ item.snapshot.growth.level === 99 ? '已达最高等级' : `本级经验 ${item.snapshot.growth.experience} / ${item.snapshot.growth.experienceToNext}` }}</small></div>
          <p>精力 {{ item.snapshot.growth.energy }} · 饱食 {{ item.snapshot.growth.hunger }} · 健康 {{ item.snapshot.growth.health }}</p>
          <p v-if="!item.snapshot.growth.storageReady || item.snapshot.growth.saveRecovered || !item.snapshot.growth.timeReady || item.snapshot.growth.unsaved" class="scope-note">
            <span v-if="!item.snapshot.growth.storageReady">存档不可写。 </span><span v-if="item.snapshot.growth.saveRecovered">已回退至有效存档。 </span><span v-if="!item.snapshot.growth.timeReady">设备时间未就绪。 </span><span v-if="item.snapshot.growth.unsaved">包含尚未落盘的变化。</span>
          </p>
          <small>{{ formatTime(new Date(item.snapshot.observedAt * 1000).toISOString()) }}</small>
        </template>
        <ConsoleState v-else compact>尚未收到设备成长数据。</ConsoleState>
      </article>
    </div>
    <p v-if="enabled && items.length" class="scope-note">设备存档只读展示，CORE 专注奖励单独结算。</p>
  </section>
</template>
