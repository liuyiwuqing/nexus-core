<script setup lang="ts">
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui';
import { useAccess } from './access';
const { admin }=useAccess();
import { nextTick, ref, onUnmounted } from 'vue';
import { formatTime, type Api, type Device, type SourceSession, type WebhookSource } from './types';
import StatusNote from './StatusNote.vue';
const props=defineProps<{api:Api;devices:Device[];busy:boolean;session:SourceSession;mutate:(a:()=>Promise<unknown>,n:string)=>Promise<boolean>}>();
const items=ref<WebhookSource[]>([]),loading=ref(false),error=ref(''),show=ref(false),creating=ref(false);
const id=ref(''),name=ref(''),targets=ref<string[]>([]),rotateSource=ref<WebhookSource|null>(null),confirmName=ref('');
const editing=ref<WebhookSource|null>(null);
const listHeading=ref<HTMLHeadingElement|null>(null);
/* 提示连同"放弃重试"按钮一起卸载，焦点会落到 body；这条动作叫"只读核对"，
   所以把用户送回本区标题，从那里往下读，等于一次干净的重新开始。 */
async function readOnlyCheck(){props.session.pending=null;await nextTick();listHeading.value?.focus();void load();}
let alive=true,control:AbortController|undefined,generation=0;
async function load(){control?.abort();const c=new AbortController();control=c;const g=++generation;loading.value=true;error.value='';try{const data=await props.api<{items:WebhookSource[]}>('sources','GET',undefined,c.signal);if(alive&&g===generation)items.value=data.items;}catch(e){if(alive&&g===generation&&!c.signal.aborted){items.value=[];error.value=e instanceof Error?e.message:'读取来源失败';}}finally{if(alive&&g===generation)loading.value=false;}}
async function execute(path?:string,method?:string,body?:unknown){if(props.busy)return;const command=props.session.pending||(path&&method?{path,method,body}:null);if(!command)return;props.session.pending=command;const ok=await props.mutate(async()=>{const value=await props.api<{credentialsAvailable?:boolean;token?:string;secret?:string}>(command.path,command.method,command.body);if(alive){if(value?.credentialsAvailable&&value.token&&value.secret)props.session.credentials={token:value.token,secret:value.secret};props.session.pending=null;creating.value=false;rotateSource.value=null;}},'来源操作已确认；测试入队不代表设备已展示。');if(alive){if(ok)confirmName.value='';await load();}}
function begin(source?:WebhookSource){editing.value=source||null;id.value=source?.id||'source-'+crypto.randomUUID().slice(0,8);name.value=source?.name||'';targets.value=source?[...source.deviceIds]:[];creating.value=true;}
function save(){void execute(`sources/${id.value}`,editing.value?'PATCH':'PUT',{name:name.value,deviceIds:targets.value,...(editing.value?{enabled:editing.value.enabled,expectedVersion:editing.value.version}:{})});}
function toggle(source:WebhookSource){void execute(`sources/${source.id}`,'PATCH',{name:source.name,deviceIds:source.deviceIds,enabled:!source.enabled,expectedVersion:source.version});}
function test(source:WebhookSource){void execute(`sources/${source.id}/test`,'POST',{requestKey:crypto.randomUUID(),title:`来源测试：${source.name}`,content:'这是一条管理台来源测试消息。'});}
void load();onUnmounted(()=>{alive=false;generation++;control?.abort();});
</script>
<template>
  <section class="spaced">
    <div class="section-heading">
      <div>
        <h2 ref="listHeading" tabindex="-1">Webhook 来源</h2>
        <p>独立令牌、签名与设备范围</p>
      </div>
      <div class="management-actions">
        <NButton attr-type="button" class="text-button" :disabled="busy || loading" @click="load">刷新来源</NButton>
        <NButton
          attr-type="button"
          v-if="admin"
          class="secondary"
          :disabled="busy || loading || !!error || !!session.pending || creating"
          @click="begin()"
        >
          新增来源
        </NButton>
      </div>
    </div>
    <p v-if="loading" role="status">正在读取来源…</p>
    <p v-if="error" role="alert" class="feedback error">{{ error }}</p>
    <StatusNote v-if="session.pending" tone="warn">
      操作结果尚未确认，重试保持同一请求与参数。
      <template #actions>
        <NButton attr-type="button" class="secondary" :disabled="busy || loading" @click="execute()">重试原来源操作</NButton>
        <NButton attr-type="button" class="text-button" :disabled="busy" @click="readOnlyCheck">放弃重试并只读核对</NButton>
      </template>
    </StatusNote>
    <div v-if="session.credentials" class="inline-form">
      <h3>本次凭据</h3>
      <p>仅本次显示；离开整页或退出后不能回读。请保存到发送方的私有配置中。</p>
      <ConsoleField path="field-1" :label-props="{ id: 'sourcespanel-field-1' + '-label', for: 'sourcespanel-field-1' }">
        <template #label>来源令牌</template>
        <NInput
          :type="show ? 'text' : 'password'"
          readonly
          :value="session.credentials.token"
          :input-props="{ autocomplete: 'off', id: 'sourcespanel-field-1', 'aria-label': '来源令牌' }"
        />
      </ConsoleField>
      <ConsoleField class="spaced" path="field-2" :label-props="{ id: 'sourcespanel-field-2' + '-label', for: 'sourcespanel-field-2' }">
        <template #label>签名密钥</template>
        <NInput
          :type="show ? 'text' : 'password'"
          readonly
          :value="session.credentials.secret"
          :input-props="{ autocomplete: 'off', id: 'sourcespanel-field-2', 'aria-label': '签名密钥' }"
        />
      </ConsoleField>
      <NCheckbox class="checkbox-label spaced" v-model:checked="show">显示本次凭据</NCheckbox>
      <NButton
        attr-type="button"
        class="text-button"
        @click="
          session.credentials = null;
          show = false;
        "
      >
        已保存，隐藏凭据
      </NButton>
    </div>
    <ConsoleForm v-if="creating" class="inline-form spaced" aria-label="新建来源" @submit.prevent="save">
      <div class="form-fields">
        <ConsoleField
          path="field-3"
          :rule="
            fieldRule(() => id, {
              required: true,
              pattern: '[a-z0-9][a-z0-9-]*',
              maxlength: 24,
              disabled: () => busy || !!session.pending || !!editing,
            })
          "
          :label-props="{ id: 'sourcespanel-field-3' + '-label', for: 'sourcespanel-field-3' }"
        >
          <template #label>来源标识</template>
          <NInput
            :disabled="busy || !!session.pending || !!editing"
            v-model:value="id"
            :maxlength="24"
            :input-props="{ required: true, pattern: '[a-z0-9][a-z0-9-]*', id: 'sourcespanel-field-3', 'aria-label': '来源标识' }"
          />
        </ConsoleField>
        <ConsoleField
          class="spaced"
          path="field-4"
          :rule="fieldRule(() => name, { required: true, maxlength: 80, disabled: () => busy || !!session.pending })"
          :label-props="{ id: 'sourcespanel-field-4' + '-label', for: 'sourcespanel-field-4' }"
        >
          <template #label>名称</template>
          <NInput
            :disabled="busy || !!session.pending"
            v-model:value="name"
            :maxlength="80"
            :input-props="{ required: true, id: 'sourcespanel-field-4', 'aria-label': '名称' }"
          />
        </ConsoleField>
        <ConsoleField
          class="spaced"
          path="field-5"
          :rule="fieldRule(() => targets, { required: true, multiple: true, disabled: () => busy || !!session.pending })"
          :label-props="{ id: 'sourcespanel-field-5' + '-label', for: 'sourcespanel-field-5' }"
        >
          <template #label>允许投递的设备</template>
          <NSelect
            @keydown.esc.stop
            :disabled="busy || !!session.pending"
            v-model:value="targets"
            :options="[...devices.map((d) => ({ label: d.name, value: d.deviceId }))]"
            multiple
            :max-tag-count="2"
            :input-props="{ id: 'sourcespanel-field-5', 'aria-label': '允许投递的设备' }"
          />
        </ConsoleField>
      </div>
      <div class="form-footer">
        <NButton attr-type="button" class="text-button" :disabled="busy" @click="creating = false">关闭</NButton>
        <NButton type="primary" attr-type="submit" class="primary" :disabled="busy || !!session.pending">保存来源</NButton>
      </div>
    </ConsoleForm>
    <div v-if="!loading && !error" class="record-list spaced">
      <p v-if="!items.length" class="empty-state">暂无 Webhook 来源</p>
      <article v-for="s in items" :key="s.id" class="record-card">
        <div class="record-heading">
          <h3>{{ s.name }}</h3>
          <span class="badge">{{ s.enabled ? '启用' : '停用' }}</span>
        </div>
        <p class="mono">POST /api/v1/hooks/{{ s.id }}</p>
        <p>范围：{{ s.deviceIds.join('、') }} · 已接收 {{ s.received }} 条</p>
        <p class="muted">最近事件：{{ formatTime(s.lastReceivedAt) }}</p>
        <div v-if="admin" class="management-actions">
          <NButton attr-type="button" class="text-button" :disabled="busy || !!session.pending || creating" @click="begin(s)">
            编辑来源
          </NButton>
          <NButton attr-type="button" class="text-button" :disabled="busy || !!session.pending" @click="toggle(s)">
            {{ s.enabled ? '停用来源' : '启用来源' }}
          </NButton>
          <NButton attr-type="button" class="text-button" :disabled="busy || !!session.pending" @click="test(s)">
            测试来源
          </NButton>
          <NButton
            attr-type="button"
            class="text-button"
            :disabled="busy || s.enabled || !!session.pending"
            @click="
              rotateSource = s;
              confirmName = '';
            "
          >
            轮换来源凭据
          </NButton>
        </div>
      </article>
    </div>
    <ConsoleForm
      v-if="rotateSource"
      class="inline-form spaced confirmation-panel is-destructive"
      @submit.prevent="execute(`sources/${rotateSource.id}/credentials`, 'POST', { expectedVersion: rotateSource.version })"
    >
      <p>轮换立即使旧令牌与签名失效；先保持来源停用，更新发送方后再启用。</p>
      <ConsoleField path="field-6" :label-props="{ id: 'sourcespanel-field-6' + '-label', for: 'sourcespanel-field-6' }">
        <template #label>输入来源名“{{ rotateSource.name }}”确认</template>
        <NInput
          :disabled="busy"
          v-model:value="confirmName"
          :input-props="{ id: 'sourcespanel-field-6', 'aria-label': '输入来源名“' + rotateSource.name + '”确认' }"
        />
      </ConsoleField>
      <div class="form-footer">
        <NButton attr-type="button" class="text-button" :disabled="busy" @click="rotateSource = null">取消轮换</NButton>
        <NButton type="error" attr-type="submit" class="primary" :disabled="busy || !!session.pending || confirmName !== rotateSource.name">
          确认轮换来源凭据
        </NButton>
      </div>
    </ConsoleForm>
    <p class="scope-note">
      新来源默认停用。发送方需签名原始 JSON 与时间戳；只有绑定设备与匹配路由可接收。创建响应丢失时凭据不能回读，应核对后在停用状态重新轮换。
    </p>
  </section>
</template>
