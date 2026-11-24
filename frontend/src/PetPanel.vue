<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { NButton, NSelect } from 'naive-ui';
import { useAccess } from './access';
import { ApiFailure, readFailure } from './failure';
import { formatTime, type Api, type Device } from './types';
import CoreFocusPanel from './CoreFocusPanel.vue';
import DeviceGrowthPanel from './DeviceGrowthPanel.vue';
import ConsoleState from './ConsoleState.vue';
import QitomoCabin from './QitomoCabin.vue';
import { appearanceAllowed, catalog, copyAppearance, gameNames, memoryNames, owns, requestNames, sameAppearance, themes, themeRules, type Appearance, type CompanionSpace } from './companion';
const props=defineProps<{api:Api;busy:boolean;selectedDevice:string;devices:Device[];deviceMatches:Device[];selectedDeviceName:string;deviceSearching:boolean;deviceSearchFailed:boolean;mutate:(action:()=>Promise<unknown>,notice:string)=>Promise<boolean>}>();
const emit=defineEmits<{'selectDevice':[id:string];'searchDevices':[query:string]}>();
const query=ref('');
const deviceOptions=computed(()=>{
  const rows=query.value.trim()?props.deviceMatches:props.devices;
  const options=rows.map(d=>({label:d.name,value:d.deviceId}));
  if(props.selectedDevice&&!options.some(o=>o.value===props.selectedDevice))options.unshift({label:props.selectedDeviceName||props.selectedDevice,value:props.selectedDevice});
  return options;
});
const {admin}=useAccess();
const tabs=['伙伴空间','衣橱与小舱','回忆与收藏','CORE 专注档案'];
const tab=ref(0),space=ref<CompanionSpace|null>(null),loading=ref(false),sending=ref(false),error=ref(''),notice=ref(''),wave=ref(false),slot=ref(0);
interface Edit {appearance:Appearance;revision:number;attempt?:{requestId:string;expectedRevision:number;appearance:Appearance}}
// Drafts and uncertain request identities are isolated per device for the panel lifetime.
const drafts=ref<Record<string,Edit>>({});
const snapshot=computed(()=>space.value?.snapshot);
const edit=computed(()=>drafts.value[props.selectedDevice]);
const preview=computed(()=>edit.value?.appearance??snapshot.value?.appearance??{theme:0,outfit:0,slots:[0,0,0]});
const changed=computed(()=>snapshot.value&&edit.value&&!sameAppearance(edit.value.appearance,snapshot.value.appearance));
const conflict=computed(()=>snapshot.value&&edit.value&&snapshot.value.appearanceRevision!==edit.value.revision);
const pending=computed(()=>space.value?.requests.some(r=>['pending','sent','unconfirmed'].includes(r.status)));
const available=computed(()=>admin.value&&space.value?.enabled&&space.value.online&&space.value.fresh&&snapshot.value?.writable&&!error.value);
const canApply=computed(()=>available.value&&!pending.value&&!conflict.value&&changed.value&&snapshot.value&&appearanceAllowed(preview.value,snapshot.value)&&!edit.value?.attempt&&!sending.value);
const status=computed(()=>!space.value?'等待读取':!space.value.enabled?'伙伴同步未启用':!snapshot.value?'等待首次同步':!space.value.online?'设备离线':!space.value.fresh?'数据已过期':!snapshot.value.writable?'存档不可写':'设备在线');
let generation=0,controller:AbortController|undefined,waveTimer:ReturnType<typeof setTimeout>|undefined;
async function load(){
  const device=props.selectedDevice;if(!device)return;
  const version=++generation;controller?.abort();controller=new AbortController();loading.value=true;
  try{
    const result=await props.api<CompanionSpace>(`devices/${encodeURIComponent(device)}/companion`,'GET',undefined,controller.signal);
    if(version!==generation)return;
    space.value=result;error.value='';
    const s=result.snapshot;
    if(s&&!drafts.value[device])drafts.value[device]={appearance:copyAppearance(s.appearance),revision:s.appearanceRevision};
    const draft=drafts.value[device];
    if(draft?.attempt){
      const receipt=result.requests.find(r=>r.requestId===draft.attempt?.requestId);
      if(receipt){
        notice.value=requestNames[receipt.status]??receipt.status;
        if(receipt.status==='applied'&&s&&s.appearanceRevision>=draft.attempt.expectedRevision+1){draft.appearance=copyAppearance(s.appearance);draft.revision=s.appearanceRevision;draft.attempt=undefined;}
        else if(['rejected','expired'].includes(receipt.status))draft.attempt=undefined;
      }
    }
  }catch(failure){if(version===generation&&!(failure instanceof DOMException&&failure.name==='AbortError'))error.value=readFailure(failure,'伙伴数据读取失败。');}
  finally{if(version===generation)loading.value=false;}
}
function reset(){if(!snapshot.value||edit.value?.attempt||pending.value)return;drafts.value[props.selectedDevice]={appearance:copyAppearance(snapshot.value.appearance),revision:snapshot.value.appearanceRevision};notice.value='已加载设备外观。';}
function choose(item:number){
  if(!edit.value||edit.value.attempt||pending.value||sending.value)return;
  if(item<=6)edit.value.appearance.outfit=item;
  else{edit.value.appearance.slots=edit.value.appearance.slots.map(v=>v===item?0:v);edit.value.appearance.slots[slot.value]=item;}
  notice.value='';
}
async function apply(retry=false){
  const device=props.selectedDevice,draft=edit.value;if(!draft||!available.value||sending.value)return;
  if(!retry&&!canApply.value)return;
  const body=retry?draft.attempt:{requestId:crypto.randomUUID(),expectedRevision:draft.revision,appearance:copyAppearance(draft.appearance)};
  if(!body)return;
  draft.attempt=body;sending.value=true;notice.value='正在提交…';
  try{
    await props.api(`devices/${encodeURIComponent(device)}/companion/appearance`,'POST',body);
    if(device===props.selectedDevice)notice.value='等待设备确认保存。';
    // Keep the identity until it is observed in the request log, even if GET fails.
  }catch(failure){
    if(failure instanceof ApiFailure&&failure.status<500)draft.attempt=undefined;
    if(device===props.selectedDevice){notice.value=draft.attempt?'提交结果未确认，请刷新核对或重试同一请求。':'';error.value=readFailure(failure,'提交失败。');}
  }finally{sending.value=false;if(device===props.selectedDevice)await load();}
}
function greet(){wave.value=false;clearTimeout(waveTimer);requestAnimationFrame(()=>{wave.value=true;waveTimer=setTimeout(()=>wave.value=false,1500);});}
watch(()=>props.selectedDevice,()=>{generation++;controller?.abort();space.value=null;error.value='';notice.value='';void load();},{immediate:true});
const poll=setInterval(()=>{if(!loading.value&&!sending.value&&document.visibilityState==='visible')void load();},5000);
onUnmounted(()=>{generation++;controller?.abort();clearInterval(poll);clearTimeout(waveTimer);});
</script>
<template>
<section class="companion-space" :aria-busy="loading">
  <div class="section-heading"><div><h1 tabindex="-1">栖团的伙伴空间</h1><p class="scope-note">一起玩一小会儿，也一起安静待着。</p></div><NButton :disabled="loading||!selectedDevice" @click="load">刷新伙伴</NButton></div>
  <div class="companion-device"><label for="companion-device">选择伙伴设备</label><NSelect input-id="companion-device" aria-label="选择伙伴设备" filterable remote :value="selectedDevice||null" :options="deviceOptions" :loading="deviceSearching" placeholder="按名称或 ID 搜索设备" @search="value=>{query=value;emit('searchDevices',value)}" @update:value="value=>{emit('selectDevice',value);query='';emit('searchDevices','')}"/><small v-if="deviceSearchFailed">搜索失败，请重新输入。</small></div>
  <nav class="companion-tabs" aria-label="伙伴空间分区"><NButton v-for="(name,index) in tabs" :key="name" :type="tab===index?'primary':'default'" :aria-pressed="tab===index" @click="tab=index">{{name}}</NButton></nav>
  <CoreFocusPanel v-if="tab===3" :api="api" :busy="busy" :mutate="mutate"/>
  <template v-else>
    <ConsoleState v-if="!selectedDevice" compact>请选择一位伙伴设备。</ConsoleState>
    <ConsoleState v-if="error" kind="error" compact>{{error}}</ConsoleState>
    <ConsoleState v-if="notice" kind="info" compact role="status">{{notice}}</ConsoleState>
    <template v-if="selectedDevice">
      <div class="companion-layout">
        <div class="companion-stage">
          <div class="record-heading"><h2>{{space?.name||selectedDevice}}</h2><span class="badge" :class="available?'success':''">{{status}}</span></div>
          <QitomoCabin :appearance="preview" :reaction="wave"/>
          <div class="companion-stage-footer"><div><strong>{{themes[preview.theme]}}</strong><small>{{changed?'预览草案': '设备外观'}} · {{preview.outfit?catalog[preview.outfit-1]?.name:'初始形象'}}</small></div><NButton @click="greet">和栖团招手</NButton></div>
          <p v-if="!snapshot" class="scope-note">当前为形象示意，等待设备回传真实收藏和外观。</p>
        </div>
        <div v-if="snapshot" class="companion-details">
          <template v-if="tab===0"><h2>游乐场</h2><div v-for="(name,index) in gameNames" :key="name" class="companion-record"><div><strong>{{name}}</strong><small>{{['45 秒 · 三轨接星','五轮 · 记住图形','五次 · 抓准时机'][index]}}</small></div><div><strong>{{snapshot.best[index]}} 分</strong><small>已玩 {{snapshot.played[index]}} 局</small></div></div><p class="scope-note">在设备「游乐场」开始，A 动作，B 切换，长按 B 暂停。摇杆接入并启用后可辅助控制。</p><h2>最近的共同回忆</h2><p>{{snapshot.memories[0]?memoryNames[snapshot.memories[0].kind]:'新的故事，从第一次互动开始。'}}</p><small>一起完成 {{snapshot.focusCompleted}} 次设备专注</small></template>
          <template v-else-if="tab===1">
            <h2>布置小舱</h2><label class="companion-label" for="cabin-theme">小舱主题</label><NSelect input-id="cabin-theme" :value="preview.theme" :disabled="!!pending||!!edit?.attempt||sending" :options="themes.map((label,value)=>({label:label+' · '+themeRules[value],value}))" @update:value="value=>{if(edit)edit.appearance.theme=value}"/>
            <div class="companion-slot"><label class="companion-label" for="cabin-slot">摆放位置</label><NSelect input-id="cabin-slot" v-model:value="slot" :options="['左侧','右侧','中央'].map((label,value)=>({label,value}))"/></div>
            <div class="record-actions"><NButton :disabled="!!pending||!!edit?.attempt||sending" @click="choose(0)">恢复初始穿戴</NButton><NButton :disabled="!!pending||!!edit?.attempt||sending" @click="edit && (edit.appearance.slots[slot]=0)">清空此位置</NButton></div>
            <p v-if="!appearanceAllowed(preview,snapshot)" class="scope-note">预览包含尚未拥有的内容；请先在设备上领取或兑换。</p>
            <p v-if="conflict" role="alert">设备外观已变化，请加载最新外观后重新选择。</p>
            <p v-if="!space?.online" class="scope-note">离线草案仅保留在当前页面，联网后再应用。</p>
            <p v-if="!admin" class="scope-note">当前角色可预览；仅管理员可应用外观。</p>
            <div class="record-actions"><NButton type="primary" :disabled="!canApply" @click="apply()">应用到设备</NButton><NButton :disabled="!!pending||!!edit?.attempt||sending" @click="reset">加载设备外观</NButton><NButton v-if="edit?.attempt" :disabled="!available||sending" @click="apply(true)">重试同一请求</NButton></div>
            <div v-if="space?.requests.length" class="companion-receipt" aria-live="polite"><strong>{{requestNames[space.requests[0]!.status]}}</strong><small>{{formatTime(space.requests[0]!.createdAt)}}</small></div>
          </template>
          <template v-else><h2>共同回忆</h2><ol class="companion-memories"><li v-for="memory in snapshot.memories" :key="memory.sequence"><strong>{{memoryNames[memory.kind]}}</strong><small>{{memory.at?formatTime(new Date(memory.at*1000).toISOString()):'设备当时未校时'}} · {{themes[memory.theme]}}</small></li></ol><p v-if="!snapshot.memories.length">还没有里程碑回忆。</p></template>
        </div>
      </div>
      <section v-if="snapshot && tab!==0" class="companion-collection"><div class="record-heading"><h2>{{tab===1?'衣橱与摆件':'收藏图鉴'}}</h2><span>{{catalog.filter(item=>owns(snapshot!.owned,item.id)).length}} / 12</span></div><div class="companion-items"><button v-for="item in catalog" :key="item.id" type="button" :disabled="tab!==1||!!pending||!!edit?.attempt||sending" :aria-pressed="preview.outfit===item.id||preview.slots.includes(item.id)" @click="choose(item.id)"><span class="companion-item-number">{{item.id<=6?'穿戴':'摆件'}} {{String(item.id<=6?item.id:item.id-6).padStart(2,'0')}}</span><strong>{{item.name}}</strong><small>{{owns(snapshot.owned,item.id)?'已收藏':item.rule}}</small></button></div></section>
    </template>
    <DeviceGrowthPanel v-if="tab===0" :api="api"/>
  </template>
</section>
</template>
<style scoped>
.companion-device{display:flex;align-items:center;gap:var(--space-3);max-width:480px;margin-bottom:var(--space-4)}.companion-device label{flex-shrink:0}.companion-device .n-select{min-width:0}.companion-tabs{display:flex;flex-wrap:wrap;gap:var(--space-2);margin-bottom:var(--space-6)}.companion-layout{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(280px,1fr);gap:var(--space-8);align-items:start;margin-bottom:var(--space-8)}.companion-stage{min-width:0}.companion-stage-footer{display:flex;justify-content:space-between;align-items:center;gap:var(--space-3);padding:var(--space-4) 0}.companion-stage-footer small,.companion-record small,.companion-memories small,.companion-receipt small{display:block;color:var(--muted);font-size:var(--text-sm)}.companion-details{padding-top:var(--space-2)}.companion-details h2{margin:var(--space-4) 0}.companion-record{display:flex;justify-content:space-between;gap:var(--space-4);padding:var(--space-4) 0;border-bottom:1px solid var(--divider)}.companion-record>div:last-child{text-align:right}.companion-label{display:block;margin-bottom:var(--space-2)}.companion-slot{margin:var(--space-4) 0}.companion-receipt{padding:var(--space-4) 0;border-top:1px solid var(--divider);margin-top:var(--space-4)}.companion-collection{margin-bottom:var(--space-8)}.companion-items{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:var(--space-3)}.companion-items button{display:flex;flex-direction:column;align-items:start;gap:var(--space-2);text-align:left;padding:var(--space-4);border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--surface);color:var(--ink);font:inherit;cursor:pointer}.companion-items button[aria-pressed=true]{border-color:var(--accent);background:var(--accent-soft-weak)}.companion-items button:focus-visible{outline:2px solid var(--focus);outline-offset:3px}.companion-items button:disabled{cursor:default}.companion-items small,.companion-item-number{color:var(--muted);font-size:var(--text-sm)}.companion-memories{padding-left:var(--space-5)}.companion-memories li{padding:var(--space-3) 0}.companion-space :deep(.record-actions){flex-wrap:wrap}
@media(max-width:900px){.companion-layout{grid-template-columns:minmax(0,1fr)}.companion-items{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:420px){.companion-stage-footer{align-items:start;flex-direction:column}.companion-tabs{gap:var(--space-1)}}
</style>
