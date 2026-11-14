<script setup lang="ts">
import {ref,onUnmounted} from 'vue';
import {useAccess} from './access';
import {formatTime,errorNames,type Api,type PullSource,type PullSession} from './types';
const {admin}=useAccess();
const props=defineProps<{api:Api;busy:boolean;session:PullSession;mutate:(a:()=>Promise<unknown>,n:string)=>Promise<boolean>}>();
const items=ref<PullSource[]>([]),loading=ref(false),error=ref('');
const preview=ref<{name:string;items:number;samples:{title:string;content:string}[]}|null>(null);
const emit=defineEmits<{messages:[source:string]}>();
const names:Record<string,string>={disabled:'停用',not_checked:'等待首次同步',ready:'同步正常',error:'同步异常'};
let alive=true,generation=0,control:AbortController|undefined;
async function load(){control?.abort();const c=new AbortController();control=c;const g=++generation;loading.value=true;error.value='';try{const data=await props.api<{items:PullSource[]}>('connectors/pull','GET',undefined,c.signal);if(alive&&generation===g)items.value=data.items;}catch(e){if(alive&&generation===g&&!c.signal.aborted){items.value=[];error.value=e instanceof Error?e.message:'读取拉取来源失败';}}finally{if(alive&&generation===g)loading.value=false;}}
async function toggle(source?:PullSource){
 if(!admin.value||props.busy||loading.value||error.value)return;
 const command=props.session.pending||(source?{id:source.id,expectedVersion:source.version,configuration:source.configuration,enabled:!source.enabled}:null);if(!command)return;
 props.session.pending=command;
 await props.mutate(async()=>{const saved=await props.api<PullSource>(`connectors/pull/${command.id}/enabled`,'PUT',{expectedVersion:command.expectedVersion,configuration:command.configuration,enabled:command.enabled});if(saved.id!==command.id||saved.configuration!==command.configuration||saved.enabled!==command.enabled)throw new Error('来源状态未确认');props.session.pending=null;},'来源状态已确认。');if(alive)await load();
}
async function probe(source:PullSource){
 if(!admin.value||props.busy||loading.value||error.value||props.session.pending)return;
 preview.value=null;
 await props.mutate(async()=>{const result=await props.api<{dryRun:boolean;items:number;samples:{title:string;content:string}[]}>(`connectors/pull/${source.id}/probe`,'POST');if(result.dryRun!==true||!Array.isArray(result.samples))throw new Error('连接测试响应无效');if(alive)preview.value={name:source.name,items:result.items,samples:result.samples};},'连接测试完成，没有写入消息或改变同步基线。');
}
async function poll(source:PullSource){
 if(!admin.value||props.busy||loading.value||error.value||props.session.pending||props.session.uncertainPoll)return;
 const session=props.session;session.uncertainPoll={id:source.id,at:new Date().toISOString()};
 await props.mutate(async()=>{await props.api(`connectors/pull/${source.id}/poll`,'POST',{configuration:source.configuration,expectedVersion:source.version});session.uncertainPoll=null;},source.baseline?'同步已确认，请核对消息和投递。':'首次同步已建立基线，没有补发历史内容。');if(alive)await load();
}
async function check(kind:'poll'|'toggle'){
 if(props.busy)return;
 await props.mutate(async()=>{await load();if(error.value)throw new Error(error.value);if(kind==='poll')props.session.uncertainPoll=null;else props.session.pending=null;},'已读取最新来源状态，请按最近成功时间和消息核对。');
}
void load();onUnmounted(()=>{alive=false;generation++;control?.abort();});
</script>
<template>
 <section class="spaced pull-panel"><div class="section-heading"><div><h2>RSS / 天气</h2><p>服务端固定来源与设备范围</p></div><button class="text-button" :disabled="busy||loading" @click="load">刷新拉取来源</button></div>
 <p v-if="loading" role="status">正在读取拉取来源…</p><p v-if="error" class="feedback error" role="alert">{{error}}</p>
 <div v-if="session.pending" class="scope-note" role="status">来源状态尚未确认，导航保留原版本与目标状态。<button class="secondary" :disabled="busy||loading||!!error" @click="toggle()">重试原启停操作</button><button class="text-button" :disabled="busy" @click="check('toggle')">只读核对状态</button></div>
 <div v-if="session.uncertainPoll" class="scope-note" role="status">{{session.uncertainPoll.id}} 的同步未确认（{{formatTime(session.uncertainPoll.at)}}），请核对最近同步与消息，不会自动重试。<button class="text-button" :disabled="busy" @click="check('poll')">核对最近同步</button></div>
 <div v-if="preview" class="inline-form pull-preview spaced"><div class="record-heading"><h3>{{preview.name}} · 连接测试</h3><button class="text-button" @click="preview=null">关闭连接测试</button></div><p>读取 {{preview.items}} 条；未创建消息、投递或同步基线。</p><article v-for="(sample,i) in preview.samples" :key="i"><h4>{{sample.title}}</h4><p class="rule-readback-content">{{sample.content}}</p></article></div>
 <div v-if="!loading&&!error" class="record-list spaced"><p v-if="!items.length" class="empty-state">尚未配置 RSS 或天气来源。在服务端配置订阅地址或室外天气坐标后重启 CORE。</p><article v-for="source in items" :key="source.id" class="record-card"><div class="record-heading"><h3>{{source.name}}</h3><span class="badge" :class="source.state==='error'?'danger':source.state==='ready'?'success':''">{{names[source.state]||source.state}}</span></div><p>{{source.kind==='weather'?'室外天气 · Open-Meteo':'RSS / Atom'}} · {{source.host}}</p><p>每 {{source.intervalMinutes}} 分钟 · 设备 {{source.deviceIds.join('、')}}</p><p class="muted">最近尝试 {{formatTime(source.lastAttemptAt)}} · 最近成功 {{formatTime(source.lastSuccessAt)}} · 新增 {{source.lastReceived}} 条</p><p v-if="source.lastError" class="inline-error" role="alert">{{errorNames[source.lastError]||'拉取失败，请检查服务端配置与供应商响应。'}}</p><p><button class="text-button" :disabled="busy" @click="emit('messages',source.kind+'.'+source.id)">查看接收消息</button></p><p v-if="!source.baseline" class="scope-note">首次成功同步只建立基线，从后续新条目开始接收。</p><div v-if="admin" class="management-actions"><button class="text-button" :disabled="busy||!!session.pending" @click="toggle(source)">{{source.enabled?'停用拉取':'启用拉取'}}</button><button class="text-button" :disabled="busy||!!session.pending" @click="probe(source)">测试连接</button><button class="text-button" :disabled="busy||!source.enabled||!!session.pending||!!session.uncertainPoll" @click="poll(source)">立即拉取</button></div></article></div>
 <p class="scope-note">新来源默认停用。手动同步和连接测试各限每分钟一次；天气消息属于室外数据，设备温湿度仍以本地传感器为准。</p>
 </section>
</template>
