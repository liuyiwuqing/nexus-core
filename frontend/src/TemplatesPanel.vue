<script setup lang="ts">
import ConsoleDisclosure from './ConsoleDisclosure.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NInput, NSelect } from 'naive-ui';
import { nextTick, onUnmounted, ref } from 'vue';
import { useAccess } from './access';
import type { Api, MessageTemplate, TemplateDefinition, TemplateSession } from './types';
import StatusNote from './StatusNote.vue';
const {operate}=useAccess();
const props=defineProps<{api:Api;busy:boolean;session:TemplateSession;mutate:(a:()=>Promise<unknown>,n:string)=>Promise<boolean>}>();
const items=ref<MessageTemplate[]>([]),variables=ref<string[]>([]),loading=ref(false),error=ref(''),preview=ref<{title:string;summary:string;eventType:string}|null>(null),example=ref('这是一条模板预览内容。');
const deleting=ref<MessageTemplate|null>(null),deleteName=ref('');
/* 关闭草稿会整块卸载表单，焦点落到 body；把它交给"创建模板"这个重开入口。
   该按钮在只读角色下不存在，所以只在存在时转移。 */
/* NButton 不暴露 focus()，与设备页同一做法：给按钮一个 id，等渲染后按 id 取 DOM。 */
async function closeDraft(){const s=props.session;s.draft=null;s.pending=null;s.uncertain=false;await nextTick();document.getElementById('template-create-entry')?.focus();}
let alive=true,g=0,c:AbortController|undefined;
const clone=(v:TemplateDefinition):TemplateDefinition=>JSON.parse(JSON.stringify(v)) as TemplateDefinition;
async function load(){c?.abort();const control=new AbortController();c=control;const current=++g;loading.value=true;error.value='';try{const data=await props.api<{items:MessageTemplate[];variables:string[]}>('templates','GET',undefined,control.signal);if(alive&&g===current){items.value=data.items;variables.value=data.variables;}}catch(e){if(alive&&g===current&&!control.signal.aborted){items.value=[];error.value=e instanceof Error?e.message:'读取模板失败';}}finally{if(alive&&g===current)loading.value=false;}}
function edit(t?:MessageTemplate){const s=props.session;s.id=t?.templateId||crypto.randomUUID();s.version=t?.version||0;s.draft=t?clone(t.definition):{name:'',title:'{title}',summary:'{summary}',icon:'text',sound:'none',durationSeconds:8};s.pending=null;s.uncertain=false;preview.value=null;}
async function save(){const s=props.session;if(!s.draft||props.busy)return;const body=s.pending||{expectedVersion:s.version,definition:clone(s.draft)};s.pending=body;const ok=await props.mutate(async()=>{const result=await props.api<MessageTemplate>(`templates/${s.id}`,'PUT',body);if(result.templateId!==s.id||result.deleted)throw new Error('未确认模板保存结果');},'模板已保存，仅影响新投递。');if(alive){if(ok){s.draft=null;s.pending=null;s.uncertain=false;}else s.uncertain=true;await load();}}
async function check(){const s=props.session;await props.mutate(async()=>{const result=await props.api<MessageTemplate>(`templates/${s.id}`);if(s.pending&&!result.deleted&&Object.entries(s.pending.definition).every(([k,v])=>result.definition[k as keyof TemplateDefinition]===v)){if(alive){s.draft=null;s.pending=null;s.uncertain=false;}}else throw new Error('当前模板不同，请关闭草稿后重新读取。');},'已核对模板。');if(alive)await load();}
async function render(t?:MessageTemplate){const d=t?.definition||props.session.draft;if(!d)return;await props.mutate(async()=>{const result=await props.api<{title:string;summary:string;eventType:string}>('templates/preview','POST',{definition:d,message:{connectorId:'preview',source:'preview',eventId:crypto.randomUUID(),eventType:'manual.test',title:'预览标题',content:example.value,summary:example.value,sender:'示例用户'}});if(alive)preview.value=result;},'预览已生成，未发送消息。');}
async function remove(){const t=deleting.value;if(!t||deleteName.value!==t.definition.name)return;await props.mutate(async()=>{await props.api(`templates/${t.templateId}?expectedVersion=${t.version}`,'DELETE');if(alive)deleting.value=null;},'模板已删除，已有投递保持原快照。');if(alive)await load();}
void load();onUnmounted(()=>{alive=false;g++;c?.abort();});
</script>
<template>
  <section>
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">模板</h1>
      </div>
      <div class="management-actions">
        <NButton attr-type="button" class="text-button" :disabled="busy || loading" @click="load">刷新模板</NButton>
        <NButton
          id="template-create-entry"
          attr-type="button"
          v-if="operate"
          class="secondary"
          :disabled="busy || loading || !!error || !!session.draft"
          @click="edit()"
        >
          创建模板
        </NButton>
      </div>
    </div>
    <p v-if="loading" role="status">正在读取模板…</p>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <ConsoleForm v-if="session.draft" class="inline-form" aria-label="模板编辑" @submit.prevent="save">
      <h2>{{ session.version ? '编辑模板' : '创建模板' }}</h2>
      <StatusNote v-if="session.uncertain" tone="warn">保存结果未确认，重试原版本或只读核对。</StatusNote>
      <div class="form-fields">
        <ConsoleField
          path="field-1"
          :rule="
            fieldRule(() => session.draft?.name, { required: true, maxlength: 80, disabled: () => busy || loading || !!session.pending })
          "
          :label-props="{ id: 'templatespanel-field-1' + '-label', for: 'templatespanel-field-1' }"
        >
          <template #label>名称</template>
          <NInput
            :disabled="busy || loading || !!session.pending"
            v-model:value="session.draft.name"
            :maxlength="80"
            :input-props="{ required: true, id: 'templatespanel-field-1', 'aria-label': '名称' }"
          />
        </ConsoleField>
        <ConsoleField
          class="spaced"
          path="field-2"
          :rule="
            fieldRule(() => session.draft?.title, { required: true, maxlength: 512, disabled: () => busy || loading || !!session.pending })
          "
          :label-props="{ id: 'templatespanel-field-2' + '-label', for: 'templatespanel-field-2' }"
        >
          <template #label>标题模板</template>
          <NInput
            :disabled="busy || loading || !!session.pending"
            v-model:value="session.draft.title"
            :maxlength="512"
            :input-props="{ required: true, id: 'templatespanel-field-2', 'aria-label': '标题模板' }"
          />
        </ConsoleField>
        <ConsoleField
          class="spaced"
          path="field-3"
          :rule="
            fieldRule(() => session.draft?.summary, {
              required: true,
              maxlength: 8192,
              disabled: () => busy || loading || !!session.pending,
            })
          "
          :label-props="{ id: 'templatespanel-field-3' + '-label', for: 'templatespanel-field-3' }"
        >
          <template #label>摘要模板</template>
          <NInput
            :disabled="busy || loading || !!session.pending"
            v-model:value="session.draft.summary"
            type="textarea"
            :maxlength="8192"
            :input-props="{ required: true, id: 'templatespanel-field-3', 'aria-label': '摘要模板' }"
          />
        </ConsoleField>
        <ConsoleField
          class="spaced"
          path="field-4"
          :label-props="{ id: 'templatespanel-field-4' + '-label', for: 'templatespanel-field-4' }"
        >
          <template #label>卡片类型</template>
          <NSelect
            @keydown.esc.stop
            :disabled="busy || loading || !!session.pending"
            v-model:value="session.draft.icon"
            :options="[
              { label: '文本', value: 'text' },
              { label: '评论', value: 'comment' },
              { label: '告警', value: 'alert' },
              { label: '成长', value: 'growth' },
              { label: '系统', value: 'system' },
            ]"
            :input-props="{ id: 'templatespanel-field-4', 'aria-label': '卡片类型' }"
          />
        </ConsoleField>
      </div>
      <p class="scope-note">
        变量：{{ variables.map((v) => '{' + v + '}').join('、') }}。当前设备契约为无提示音、显示 8 秒，标题 96 字节、摘要 180 字节。
      </p>
      <div class="form-footer">
        <NButton attr-type="button" class="text-button" :disabled="busy" @click="closeDraft">关闭模板草稿</NButton>
        <div class="management-actions">
          <NButton attr-type="button" v-if="session.pending" class="secondary" :disabled="busy" @click="check">核对当前模板</NButton>
          <NButton type="primary" attr-type="submit" class="primary" :disabled="busy || loading || !!error">
            {{ session.uncertain ? '重试原模板保存' : '保存模板' }}
          </NButton>
        </div>
      </div>
    </ConsoleForm>
    <div v-if="!loading && !error" class="record-list spaced">
      <p v-if="!items.length" class="empty-state">暂无模板</p>
      <article v-for="t in items" :key="t.templateId" class="record-card">
        <div class="record-heading">
          <h2>{{ t.definition.name }}</h2>
          <span class="badge">版本 {{ t.version }}</span>
        </div>
        <p>{{ t.definition.title }}</p>
        <div class="management-actions">
          <NButton attr-type="button" class="text-button" :disabled="busy" @click="render(t)">预览此模板</NButton>
          <NButton attr-type="button" v-if="operate" class="text-button" :disabled="busy || !!session.draft" @click="edit(t)">
            编辑模板
          </NButton>
          <NButton
            attr-type="button"
            v-if="operate"
            class="text-button"
            :disabled="busy || !!session.draft"
            @click="
              deleting = t;
              deleteName = '';
            "
          >
            删除模板
          </NButton>
        </div>
      </article>
    </div>
    <ConsoleForm v-if="deleting" class="inline-form spaced confirmation-panel is-destructive" @submit.prevent="remove">
      <ConsoleField path="field-5" :label-props="{ id: 'templatespanel-field-5' + '-label', for: 'templatespanel-field-5' }">
        <template #label>输入模板名“{{ deleting.definition.name }}”</template>
        <NInput
          :disabled="busy"
          v-model:value="deleteName"
          :input-props="{ id: 'templatespanel-field-5', 'aria-label': '输入模板名“' + deleting.definition.name + '”' }"
        />
      </ConsoleField>
      <p>被路由引用的模板不能删除；已有投递不会重写。</p>
      <div class="form-footer">
        <NButton attr-type="button" class="text-button" :disabled="busy" @click="deleting = null">取消删除模板</NButton>
        <NButton type="error" attr-type="submit" class="primary" :disabled="busy || deleteName !== deleting.definition.name">
          确认删除模板
        </NButton>
      </div>
    </ConsoleForm>
    <ConsoleDisclosure label="变量预览" class="template-preview" :force-open="!!session.pending || !!preview">
    <div class="inline-form">
      <ConsoleField
        path="field-6"
        :rule="fieldRule(() => example, { maxlength: 8192 })"
        :label-props="{ id: 'templatespanel-field-6' + '-label', for: 'templatespanel-field-6' }"
      >
        <template #label>示例内容</template>
        <NInput
          v-model:value="example"
          type="textarea"
          :maxlength="8192"
          :input-props="{ id: 'templatespanel-field-6', 'aria-label': '示例内容' }"
        />
      </ConsoleField>
      <NButton attr-type="button" class="secondary spaced" :disabled="busy || !session.draft || !example" @click="render()">
        预览当前草稿
      </NButton>
      <article v-if="preview" class="record-card spaced" aria-label="模板预览结果">
        <h3>{{ preview.title }}</h3>
        <p style="white-space: pre-wrap">{{ preview.summary }}</p>
        <span class="muted">{{ preview.eventType }} · 仅预览，未发送</span>
      </article>
    </div>
    </ConsoleDisclosure>
  </section>
</template>
