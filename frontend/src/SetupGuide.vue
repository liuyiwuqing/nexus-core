<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { useAccess } from './access';
import type { Api } from './types';
const props=defineProps<{api:Api;refreshKey:number}>();
const emit=defineEmits<{navigate:[tab:'devices'|'routing'|'deliveries'];close:[]}>();
const {admin,operate}=useAccess();
const items=ref<{deviceId:string;name:string;deviceObserved:boolean;receiptObserved:boolean;displayObserved:boolean}[]>([]),loading=ref(false),error=ref(''),mqtt=ref('');
let alive=true,c:AbortController|undefined;
async function load(){c?.abort();const control=new AbortController();c=control;loading.value=true;error.value='';try{const data=await props.api<{devices:typeof items.value;mqtt:string}>('setup','GET',undefined,control.signal);if(alive){items.value=data.devices;mqtt.value=data.mqtt;}}catch(e){if(alive&&!control.signal.aborted){items.value=[];error.value=e instanceof Error?e.message:'读取引导失败';}}finally{if(alive&&!control.signal.aborted)loading.value=false;}}
watch(()=>props.refreshKey,()=>void load());void load();onUnmounted(()=>{alive=false;c?.abort();});
</script>
<template><section class="inline-form setup-guide" aria-label="首次接入引导"><div class="record-heading"><h2>接入设备</h2><div class="management-actions"><button class="text-button" :disabled="loading" @click="load">核对接入状态</button><button class="text-button" @click="emit('close')">收起引导</button></div></div><p v-if="loading" role="status">正在核对…</p><p v-if="error" class="inline-error" role="alert">{{error}}</p><template v-if="!loading&&!error"><ol><li>登记设备：{{items.length?`已登记 ${items.length} 台`:'尚无设备'}}。<button v-if="admin" class="text-button" @click="emit('navigate','devices')">查看设备登记</button><span v-else>登记和凭据维护由管理员完成。</span></li><li>使用部署端生成的专属连接配置配置设备。登记不代表 Broker 凭据已创建；当前凭据配置状态未确认。</li><li>发送定向测试，再核对收到、展示与已读回执。<button v-if="operate" class="text-button" @click="emit('navigate','routing')">打开定向测试</button></li></ol><p class="muted">CORE 与 Broker：{{mqtt==='ready'?'连接正常':'暂不可用'}}；不代表设备已联网。</p><ul><li v-for="d in items" :key="d.deviceId">{{d.name}}：{{d.displayObserved?'已有展示回执':d.receiptObserved?'已有接收回执，展示未确认':d.deviceObserved?'曾观察到设备连接，测试回执未确认':'尚未观察到设备连接'}}。</li></ul><button class="text-button" @click="emit('navigate','deliveries')">核对投递时间线</button></template></section></template>
