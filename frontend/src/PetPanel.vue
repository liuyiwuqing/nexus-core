<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { formatTime, type Api } from './types';
import DeviceGrowthPanel from './DeviceGrowthPanel.vue';
interface Focus { sessionId: string; durationMinutes: number; state: string; startedAt: string; dueAt: string;
  remainingSeconds: number; experienceReward: number; coinReward: number }
interface Profile { level: number; levelProgress: number; experience: number; coins: number; bond: number; completedFocus: number; activeSession: Focus | null }
const props = defineProps<{ api: Api; busy: boolean; mutate: (action: () => Promise<unknown>, notice: string) => Promise<boolean> }>();
const profile = ref<Profile | null>(null), sessions = ref<Focus[]>([]), error = ref(''), minutes = ref(25), loading = ref(false);
const now = ref(Date.now()), loadedAt = ref(Date.now());
let generation = 0, controller: AbortController | undefined, requestKey = crypto.randomUUID();
const active = computed(() => profile.value?.activeSession);
const remaining = computed(() => Math.max(0, (active.value?.remainingSeconds || 0) - Math.floor((now.value-loadedAt.value)/1000)));
const countdown = computed(() => `${Math.floor(remaining.value/60).toString().padStart(2,'0')}:${(remaining.value%60).toString().padStart(2,'0')}`);
const names: Record<string,string> = { active: '进行中', completed: '已结算', cancelled: '已取消' };
async function load() {
  controller?.abort(); controller = new AbortController(); const request = ++generation; loading.value = true;
  try {
    const [pet, records] = await Promise.all([props.api<Profile>('pet','GET',undefined,controller.signal),props.api<{items: Focus[]}>('pet/focus','GET',undefined,controller.signal)]);
    if (request === generation) { profile.value = pet; sessions.value = records.items; error.value = ''; loadedAt.value = Date.now(); now.value = Date.now(); }
  } catch (failure) { if (request === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) error.value = failure instanceof Error ? failure.message : '读取成长数据失败。'; }
  finally { if (request === generation) loading.value = false; }
}
async function start() {
  if (await props.mutate(() => props.api('pet/focus','POST',{ requestKey, durationMinutes: minutes.value }), '专注已开始。')) requestKey = crypto.randomUUID();
  await load();
}
async function finish(kind: 'complete' | 'cancel') {
  if (!active.value) return;
  await props.mutate(() => props.api(`pet/focus/${active.value!.sessionId}/${kind}`,'POST'), kind === 'complete' ? '专注已结算，成长数据已保存。' : '专注已取消。');
  await load();
}
void load();
const timer = setInterval(() => { now.value = Date.now(); },1000);
const poll = setInterval(() => { if (!loading.value && !props.busy && document.visibilityState === 'visible') void load(); },15000);
onUnmounted(() => { generation++; controller?.abort(); clearInterval(timer); clearInterval(poll); });
</script>

<template>
  <section>
    <div class="section-heading"><div><h1>一起成长</h1><p>完成专注，积累经验。</p></div><button class="text-button" :disabled="loading" @click="load">刷新</button></div>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <DeviceGrowthPanel :api="api" />
    <template v-if="profile">
      <h2>CORE 专注档案</h2>
      <dl class="pet-stats"><div><dt>等级</dt><dd>{{ profile.level }}</dd></div><div><dt>经验</dt><dd>{{ profile.experience }}</dd></div><div><dt>金币</dt><dd>{{ profile.coins }}</dd></div><div><dt>亲密度</dt><dd>{{ profile.bond }}<small> / 100</small></dd></div></dl>
      <div class="growth-progress"><progress :value="profile.levelProgress" max="100" :aria-label="`距离下一级还需 ${100-profile.levelProgress} 经验`"></progress><small>距下一级 {{ 100-profile.levelProgress }} 经验 · 已完成 {{ profile.completedFocus }} 次专注</small></div>
      <article v-if="active" class="record-card focus-card">
        <div class="record-heading"><h2>{{ remaining ? '专注进行中' : '专注时间已到' }}</h2><span class="badge">{{ active.durationMinutes }} 分钟</span></div>
        <p class="focus-clock" role="timer" aria-label="剩余时间">{{ countdown }}</p>
        <div class="record-actions"><button class="primary" :disabled="busy || remaining>0" @click="finish('complete')">完成并结算</button><button class="text-button" :disabled="busy" @click="finish('cancel')">取消本次专注</button></div>
      </article>
      <form v-else class="inline-form" @submit.prevent="start"><label>专注时长（分钟）<input v-model.number="minutes" type="number" min="1" max="180" step="1" required :disabled="busy" /></label><div class="form-footer"><span>每分钟 1 经验，单次最多 120；每天最多 240。取消不结算。</span><button class="primary" :disabled="busy">开始专注</button></div></form>
      <div class="record-list"><article v-for="session in sessions.filter(s=>s.state!=='active')" :key="session.sessionId" class="record-card"><div class="record-heading"><h2>{{ session.durationMinutes }} 分钟专注</h2><span class="badge" :class="session.state==='completed' ? 'success' : ''">{{ names[session.state] }}</span></div><small>{{ formatTime(session.startedAt) }}</small><p v-if="session.state==='completed'">+{{ session.experienceReward }} 经验 · +{{ session.coinReward }} 金币</p></article></div>
    </template>
    <p v-else-if="loading" class="muted" role="status">正在读取成长档案…</p>
  </section>
</template>
