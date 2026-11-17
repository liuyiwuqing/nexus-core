<script setup lang="ts">
import { nextTick, onUnmounted, ref, useId } from 'vue';
import { NButton, NCheckbox, NInput } from 'naive-ui';
import ConsoleField from './ConsoleField.vue';
import ConsoleForm from './ConsoleForm.vue';
import ConsoleState from './ConsoleState.vue';
import StatusNote from './StatusNote.vue';
import { fieldRule } from './formRules';
import { ApiFailure, readFailure } from './failure';
import type { Api } from './types';
import type { Role,ConsoleSession } from './access';
const props=defineProps<{api:Api;busy:boolean;mutate:(action:()=>Promise<unknown>,notice:string)=>Promise<boolean>;replaceSession:(session:ConsoleSession,token:string)=>void}>();
const id=useId(),open=ref(false),loading=ref(false),error=ref(''),uncertain=ref(false);
interface AdminMetadata {mode:string;editable:boolean;revision?:number}
const metadata=ref<AdminMetadata|null>(null);
const current=ref(''),replacement=ref(''),confirmation=ref(''),revealed=ref(false),saved=ref(false);
const first=ref<InstanceType<typeof NInput>|null>(null);
let alive=true,generation=0,control:AbortController|undefined;
onUnmounted(()=>{alive=false;generation++;control?.abort();});
async function load(){
  control?.abort();control=new AbortController();const g=++generation;loading.value=true;error.value='';metadata.value=null;
  try{const value=await props.api<AdminMetadata>('access/admin-token','GET',undefined,control.signal);if(!value||!['file','environment'].includes(value.mode)||typeof value.editable!=='boolean'||value.editable&&!Number.isSafeInteger(value.revision))throw new Error('Invalid credential metadata');if(alive&&g===generation)metadata.value=value;}
  catch(failure){if(alive&&g===generation&&!control.signal.aborted)error.value=readFailure(failure,'读取管理员令牌配置失败。');}
  finally{if(alive&&g===generation)loading.value=false;}
}
async function show(){open.value=true;await load();await nextTick();first.value?.focus();}
function generate(){const bytes=crypto.getRandomValues(new Uint8Array(48));replacement.value=btoa(String.fromCharCode(...bytes)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');confirmation.value='';saved.value=false;}
async function change(){
  const revision=metadata.value?.revision;if(props.busy||uncertain.value||!saved.value||!Number.isSafeInteger(revision)||replacement.value!==confirmation.value)return;
  const next=replacement.value;uncertain.value=true;
  const ok=await props.mutate(async()=>{
    try{
      const value=await props.api<{revision:number;role:Role;persisted?:boolean;csrfToken?:string}>('access/admin-token','PUT',{currentToken:current.value,newToken:next,expectedRevision:revision});
      if(value.revision!==revision!+1||value.role!=='admin'||value.persisted&&(!value.csrfToken||value.csrfToken.length<32))throw new Error('Unconfirmed credential change');
      props.replaceSession(value,next);
      if(alive){uncertain.value=false;current.value='';replacement.value='';confirmation.value='';saved.value=false;revealed.value=false;}
    }catch(failure){if(alive&&failure instanceof ApiFailure&&failure.status>=400&&failure.status<500&&failure.status!==408)uncertain.value=false;throw failure;}
  },'管理员令牌已修改，旧凭据已失效。');
  if(alive&&ok){open.value=false;metadata.value=null;}
}
async function recheck(){uncertain.value=false;current.value='';confirmation.value='';saved.value=false;await load();}
</script>
<template>
  <div class="admin-token-management">
    <NButton v-if="!open" attr-type="button" class="text-button" :aria-expanded="false" :aria-controls="id" :disabled="busy" @click="show">修改管理员令牌</NButton>
    <div v-else :id="id" class="inline-form spaced">
      <div class="section-heading"><h2>修改管理员令牌</h2><NButton attr-type="button" class="text-button" :disabled="busy || uncertain" @click="open=false;current='';replacement='';confirmation='';saved=false">收起</NButton></div>
      <ConsoleState v-if="loading" kind="loading" compact>正在读取…</ConsoleState>
      <ConsoleState v-if="error" kind="error" compact>{{ error }}<template #actions><NButton attr-type="button" :disabled="loading" @click="load">重试读取配置</NButton></template></ConsoleState>
      <ConsoleState v-if="metadata && !metadata.editable" kind="info" compact>{{ metadata.mode==='environment' ? '当前令牌由环境变量管理。请先在服务端启用受保护凭据文件，再通过此处修改。' : '凭据文件不可写，请在服务端更新。' }}</ConsoleState>
      <ConsoleForm v-if="metadata?.editable" @submit.prevent="change">
        <ConsoleField path="current" label="当前管理员令牌" :rule="fieldRule(()=>current,{required:true,disabled:()=>busy||uncertain})">
          <NInput ref="first" v-model:value="current" type="password" :disabled="busy || uncertain" :input-props="{autocomplete:'off','aria-label':'当前管理员令牌'}" />
        </ConsoleField>
        <div class="form-columns spaced">
          <ConsoleField path="replacement" label="新管理员令牌" :rule="fieldRule(()=>replacement,{required:true,pattern:'[!-~]{32,256}',disabled:()=>busy||uncertain})">
            <NInput v-model:value="replacement" :type="revealed?'text':'password'" :disabled="busy || uncertain" :maxlength="256" @update:value="saved=false" :input-props="{autocomplete:'off',spellcheck:false,'aria-label':'新管理员令牌'}" />
          </ConsoleField>
          <ConsoleField path="confirmation" label="确认新令牌" :rule="{validator:()=>busy||uncertain||confirmation===replacement ? true : new Error('两次输入的令牌不一致。')}">
            <NInput v-model:value="confirmation" :type="revealed?'text':'password'" :disabled="busy || uncertain" :maxlength="256" :input-props="{autocomplete:'off','aria-label':'确认新令牌'}" />
          </ConsoleField>
        </div>
        <div class="form-actions spaced"><NButton attr-type="button" :disabled="busy || uncertain" @click="generate">生成随机令牌</NButton><NCheckbox v-model:checked="revealed">显示新令牌</NCheckbox></div>
        <p class="muted">32 至 256 位，不含空白。修改后旧管理员凭据和其他登录会话立即失效；当前会话会更新。</p>
        <NCheckbox v-model:checked="saved" :disabled="busy || uncertain">我已保存新令牌，确认替换旧令牌</NCheckbox>
        <StatusNote v-if="uncertain && !busy" tone="warn" class="spaced">修改结果未确认，请保存新令牌并核对；不要重复提交。<template #actions><NButton attr-type="button" :disabled="busy" @click="recheck">已核对，重新读取配置</NButton></template></StatusNote>
        <div class="form-footer spaced"><span></span><NButton type="primary" attr-type="submit" :disabled="busy || uncertain || !saved || !Number.isSafeInteger(metadata.revision)">保存管理员令牌</NButton></div>
      </ConsoleForm>
    </div>
  </div>
</template>
