<script setup lang="ts">
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NCheckbox, NInput, NSelect, NTimePicker } from 'naive-ui';
import { useAccess } from './access';
const { operate }=useAccess();
import { onUnmounted, ref } from 'vue';
import type { Api, Device, RoutingSession, RouteRule, RouteDefinition, MessageTemplate } from './types';
import StatusNote from './StatusNote.vue';
const props=defineProps<{api:Api;busy:boolean;devices:Device[];session:RoutingSession;mutate:(action:()=>Promise<unknown>,notice:string)=>Promise<boolean>}>();
const templates=ref<MessageTemplate[]>([]);
const rules=ref<RouteRule[]>([]),policy=ref({mode:'broadcast',version:1}),loading=ref(false),error=ref(''),preview=ref<string[]|null>(null);
const testSource=ref('manual'),testTitle=ref('测试标题'),testContent=ref('测试内容'),testType=ref('manual.test');
const selected=ref<string[]>(props.session.test?.deviceIds||[]),sent=ref('');
const testTemplate=ref<string|null>(props.session.test?.templateId||null),testPriority=ref(props.session.test?.priority||'normal');
const deleting=ref<RouteRule|null>(null),deleteName=ref(''),confirmPolicy=ref(false);
let alive=true,generation=0,control:AbortController|undefined;
const clone=(value:RouteDefinition):RouteDefinition=>JSON.parse(JSON.stringify(value)) as RouteDefinition;
async function load(){control?.abort();const c=new AbortController();control=c;const g=++generation;loading.value=true;error.value='';try{const [result,cards]=await Promise.all([props.api<{policy:{mode:string;version:number};items:RouteRule[]}>('routing','GET',undefined,c.signal),props.api<{items:MessageTemplate[]}>('templates','GET',undefined,c.signal)]);templates.value=cards.items;if(alive&&g===generation){policy.value=result.policy;rules.value=result.items;}}catch(e){if(alive&&g===generation&&!c.signal.aborted){rules.value=[];error.value=e instanceof Error?e.message:'读取路由失败';}}finally{if(alive&&g===generation)loading.value=false;}}
function edit(rule?:RouteRule){props.session.id=rule?.ruleId||crypto.randomUUID();props.session.version=rule?.version||0;props.session.draft=rule?clone(rule.definition):{name:'',enabled:false,source:'*',eventType:'*',match:'all',expression:'',deviceIds:[],priority:'',from:'',before:'',zone:'Asia/Shanghai',templateId:null};props.session.pending=null;props.session.uncertain=false;}
function close(){if(!props.busy){props.session.draft=null;props.session.pending=null;props.session.uncertain=false;}}
async function save(){if(props.busy||loading.value||error.value||!props.session.draft)return;const s=props.session;const body=s.pending||{expectedVersion:s.version,definition:clone(s.draft!)};s.pending=body;const ok=await props.mutate(async()=>{try{const current=await props.api<RouteRule>(`routing/rules/${s.id}`,'PUT',body);if(current.ruleId!==s.id||current.deleted||current.version<body.expectedVersion)throw new Error('未确认路由保存结果');}catch(e){if(alive)s.uncertain=true;throw e;}},'路由规则已保存。');if(alive){if(ok){s.draft=null;s.pending=null;s.uncertain=false;}await load();}}
async function check(){const s=props.session;await props.mutate(async()=>{const current=await props.api<RouteRule>(`routing/rules/${s.id}`);if(alive&&s.pending&&JSON.stringify(current.definition)===JSON.stringify(s.pending.definition)&&!current.deleted){s.draft=null;s.pending=null;s.uncertain=false;}else throw new Error('当前定义不同，请关闭草稿后重新读取。');},'已核对当前路由。');if(alive)await load();}
async function remove(){const r=deleting.value;if(!r||deleteName.value!==r.definition.name)return;await props.mutate(async()=>{await props.api(`routing/rules/${r.ruleId}?expectedVersion=${r.version}`,'DELETE');if(alive)deleting.value=null;},'路由已删除；已有投递不变。');if(alive)await load();}
async function switchPolicy(){const target=props.session.policy||{mode:policy.value.mode==='broadcast'?'rules':'broadcast',expectedVersion:policy.value.version};props.session.policy=target;await props.mutate(async()=>{await props.api('routing/policy','PUT',target);if(alive){confirmPolicy.value=false;props.session.policy=null;}},'路由模式已更新，仅影响新消息。');if(alive)await load();}
async function test(){await props.mutate(async()=>{const result=await props.api<{targets:{deviceId:string}[]}>('routing/preview','POST',{connectorId:'manual',source:testSource.value,eventId:crypto.randomUUID(),eventType:testType.value,title:testTitle.value,content:testContent.value});if(alive)preview.value=result.targets.map(v=>v.deviceId);},'预演完成，未创建消息或投递。');}
async function send(){if(props.busy||(!props.session.test&&(!selected.value.length||!testTitle.value.trim()||!testContent.value.trim())))return;const body=props.session.test||{requestKey:crypto.randomUUID(),deviceIds:[...selected.value],title:testTitle.value,content:testContent.value,priority:testPriority.value,templateId:testTemplate.value};props.session.test=body;await props.mutate(async()=>{const result=await props.api<{messageId:string}>('routing/test','POST',body);if(!result?.messageId)throw new Error('发送结果未确认，请重试同一请求。');if(alive){sent.value=result.messageId;props.session.test=null;}},'测试消息已保存并进入所选设备的投递队列。');}
void load();onUnmounted(()=>{alive=false;generation++;control?.abort();});
</script>
<template>
  <section>
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">路由</h1>
        <p>新消息的设备选择与条件</p>
      </div>
      <div class="management-actions">
        <NButton quaternary attr-type="button" class="text-button" :disabled="busy || loading" @click="load">刷新</NButton>
        <NButton
          attr-type="button"
          v-if="operate"
          class="secondary"
          :disabled="busy || loading || !!error || !!session.draft"
          @click="edit()"
        >
          创建路由
        </NButton>
      </div>
    </div>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取路由…</p>
    <div v-if="!loading && !error" class="inline-form">
      <div class="record-heading">
        <h2>{{ policy.mode === 'broadcast' ? '默认广播' : '按规则路由' }}</h2>
        <NButton
          attr-type="button"
          v-if="operate"
          class="secondary"
          :disabled="!operate || busy || !!session.draft"
          @click="confirmPolicy = true"
        >
          切换模式
        </NButton>
      </div>
      <p>{{ policy.mode === 'broadcast' ? '发给全部启用设备，兼容既有行为。' : '只发给匹配规则选中的启用设备；无匹配则只保存消息。' }}</p>
      <div v-if="confirmPolicy || session.policy" class="spaced">
        <p>仅影响新消息；全部规则停用不会自动切回广播。待确认时重试固定目标，不反向切换。</p>
        <div class="management-actions">
          <NButton quaternary attr-type="button" class="text-button" :disabled="busy" @click="confirmPolicy = false">取消</NButton>
          <NButton type="primary" attr-type="button" class="primary" :disabled="busy" @click="switchPolicy">
            {{ session.policy ? '重试原目标模式' : '确认切换模式' }}
          </NButton>
        </div>
      </div>
    </div>
    <ConsoleForm v-if="session.draft" class="inline-form spaced" aria-label="路由编辑" @submit.prevent="save">
      <h2>{{ session.version ? '编辑路由' : '创建路由' }}</h2>
      <StatusNote v-if="session.uncertain" tone="warn">保存结果未确认；重试保持原版本和定义，或只读核对。</StatusNote>
      <div class="form-fields">
        <ConsoleField
          path="field-1"
          :rule="
            fieldRule(() => session.draft?.name, {
              required: true,
              maxlength: 80,
              disabled: () => busy || loading || !!error || !!session.pending,
            })
          "
          :label-props="{ id: 'routingpanel-field-1' + '-label', for: 'routingpanel-field-1' }"
        >
          <template #label>名称</template>
          <NInput
            :disabled="busy || loading || !!error || !!session.pending"
            v-model:value="session.draft.name"
            :maxlength="80"
            :input-props="{ required: true, id: 'routingpanel-field-1', 'aria-label': '名称' }"
          />
        </ConsoleField>
        <div class="form-columns spaced">
          <ConsoleField
            path="field-2"
            :rule="
              fieldRule(() => session.draft?.source, {
                required: true,
                maxlength: 32,
                disabled: () => busy || loading || !!error || !!session.pending,
              })
            "
            :label-props="{ id: 'routingpanel-field-2' + '-label', for: 'routingpanel-field-2' }"
          >
            <template #label>来源（* 为全部）</template>
            <NInput
              :disabled="busy || loading || !!error || !!session.pending"
              v-model:value="session.draft.source"
              :maxlength="32"
              :input-props="{ required: true, id: 'routingpanel-field-2', 'aria-label': '来源（* 为全部）' }"
            />
          </ConsoleField>
          <ConsoleField
            path="field-3"
            :rule="
              fieldRule(() => session.draft?.eventType, {
                required: true,
                maxlength: 64,
                disabled: () => busy || loading || !!error || !!session.pending,
              })
            "
            :label-props="{ id: 'routingpanel-field-3' + '-label', for: 'routingpanel-field-3' }"
          >
            <template #label>事件类型（* 为全部）</template>
            <NInput
              :disabled="busy || loading || !!error || !!session.pending"
              v-model:value="session.draft.eventType"
              :maxlength="64"
              :input-props="{ required: true, id: 'routingpanel-field-3', 'aria-label': '事件类型（* 为全部）' }"
            />
          </ConsoleField>
        </div>
        <div class="form-columns spaced">
          <ConsoleField path="field-4" :label-props="{ id: 'routingpanel-field-4' + '-label', for: 'routingpanel-field-4' }">
            <template #label>匹配</template>
            <NSelect
              @keydown.esc.stop
              :disabled="busy || loading || !!error || !!session.pending"
              v-model:value="session.draft.match"
              :options="[
                { label: '全部', value: 'all' },
                { label: '包含关键词', value: 'keyword' },
                { label: '受限正则', value: 'regex' },
              ]"
              :input-props="{ id: 'routingpanel-field-4', 'aria-label': '匹配' }"
            />
          </ConsoleField>
          <ConsoleField
            v-if="session.draft.match !== 'all'"
            path="field-5"
            :rule="
              fieldRule(() => session.draft?.expression, {
                required: true,
                maxlength: 160,
                disabled: () => busy || loading || !!error || !!session.pending,
              })
            "
            :label-props="{ id: 'routingpanel-field-5' + '-label', for: 'routingpanel-field-5' }"
          >
            <template #label>表达式</template>
            <NInput
              :disabled="busy || loading || !!error || !!session.pending"
              v-model:value="session.draft.expression"
              :maxlength="160"
              :input-props="{ required: true, id: 'routingpanel-field-5', 'aria-label': '表达式' }"
            />
          </ConsoleField>
        </div>
        <p v-if="session.draft.match === 'regex'" class="scope-note">支持文字、首尾锚点及一次 .*；不支持分组、回溯或其他量词。</p>
        <ConsoleField
          class="spaced"
          path="field-6"
          :rule="
            fieldRule(() => session.draft?.deviceIds, {
              required: true,
              multiple: true,
              disabled: () => busy || loading || !!error || !!session.pending,
            })
          "
          :label-props="{ id: 'routingpanel-field-6' + '-label', for: 'routingpanel-field-6' }"
        >
          <template #label>目标设备</template>
          <NSelect
            @keydown.esc.stop
            :disabled="busy || loading || !!error || !!session.pending"
            v-model:value="session.draft.deviceIds"
            :options="[
              ...devices.map((d) => ({ label: d.name + '（' + d.deviceId + (d.enabled ? '' : ' · 停用') + '）', value: d.deviceId })),
            ]"
            multiple
            :max-tag-count="2"
            :input-props="{ id: 'routingpanel-field-6', 'aria-label': '目标设备' }"
          />
        </ConsoleField>
        <ConsoleField class="spaced" path="field-7" :label-props="{ id: 'routingpanel-field-7' + '-label', for: 'routingpanel-field-7' }">
          <template #label>消息模板</template>
          <NSelect
            @keydown.esc.stop
            :disabled="busy || loading || !!error || !!session.pending"
            v-model:value="session.draft.templateId"
            :options="[...templates.map((t) => ({ label: t.definition.name, value: t.templateId }))]"
            clearable
            :placeholder="'保留来源文本与卡片'"
            :input-props="{ id: 'routingpanel-field-7', 'aria-label': '消息模板' }"
          />
        </ConsoleField>
        <ConsoleField class="spaced" path="field-8" :label-props="{ id: 'routingpanel-field-8' + '-label', for: 'routingpanel-field-8' }">
          <template #label>优先级</template>
          <NSelect
            @keydown.esc.stop
            :disabled="busy || loading || !!error || !!session.pending"
            v-model:value="session.draft.priority"
            :options="[
              { label: '保留消息优先级', value: '' },
              { label: '低', value: 'low' },
              { label: '普通', value: 'normal' },
              { label: '高', value: 'high' },
              { label: '紧急', value: 'critical' },
            ]"
            :input-props="{ id: 'routingpanel-field-8', 'aria-label': '优先级' }"
          />
        </ConsoleField>
        <div class="form-columns spaced">
          <ConsoleField path="field-9" :label-props="{ id: 'routingpanel-field-9' + '-label', for: 'routingpanel-field-9' }">
            <template #label>时间窗开始</template>
            <NTimePicker
              @keydown.esc.stop
              :disabled="busy || loading || !!error || !!session.pending"
              :formatted-value="session.draft.from || null"
              @update:formatted-value="session.draft.from = $event || ''"
              format="HH:mm"
              value-format="HH:mm"
              clearable
            />
          </ConsoleField>
          <ConsoleField path="field-10" :label-props="{ id: 'routingpanel-field-10' + '-label', for: 'routingpanel-field-10' }">
            <template #label>截止（不包含）</template>
            <NTimePicker
              @keydown.esc.stop
              :disabled="busy || loading || !!error || !!session.pending"
              :formatted-value="session.draft.before || null"
              @update:formatted-value="session.draft.before = $event || ''"
              format="HH:mm"
              value-format="HH:mm"
              clearable
            />
          </ConsoleField>
        </div>
        <ConsoleField
          class="spaced"
          path="field-11"
          :rule="fieldRule(() => session.draft?.zone, { required: true, disabled: () => busy || loading || !!error || !!session.pending })"
          :label-props="{ id: 'routingpanel-field-11' + '-label', for: 'routingpanel-field-11' }"
        >
          <template #label>时区</template>
          <NInput
            :disabled="busy || loading || !!error || !!session.pending"
            v-model:value="session.draft.zone"
            :input-props="{ required: true, id: 'routingpanel-field-11', 'aria-label': '时区' }"
          />
        </ConsoleField>
        <NCheckbox
          class="checkbox-label spaced"
          v-model:checked="session.draft.enabled"
          :disabled="busy || loading || !!error || !!session.pending" :aria-disabled="busy || loading || !!error || !!session.pending"
        >
          启用规则
        </NCheckbox>
      </div>
      <div class="form-footer">
        <NButton quaternary attr-type="button" class="text-button" :disabled="busy" @click="close">关闭草稿</NButton>
        <div class="management-actions">
          <NButton attr-type="button" v-if="session.pending" class="secondary" :disabled="busy" @click="check">核对当前路由</NButton>
          <NButton type="primary" attr-type="submit" class="primary" :disabled="busy || loading || !!error">
            {{ session.uncertain ? '重试原版本保存' : '保存路由' }}
          </NButton>
        </div>
      </div>
    </ConsoleForm>
    <div v-if="!loading && !error" class="record-list spaced">
      <p v-if="!rules.length" class="empty-state">暂无路由规则</p>
      <article v-for="r in rules" :key="r.ruleId" class="record-card">
        <div class="record-heading">
          <h2>{{ r.definition.name }}</h2>
          <span class="badge">{{ r.definition.enabled ? '启用' : '停用' }}</span>
        </div>
        <p>{{ r.definition.source }} → {{ r.definition.deviceIds.join('、') }}</p>
        <div class="management-actions">
          <NButton quaternary attr-type="button" class="text-button" :disabled="!operate || busy || !!session.draft" @click="edit(r)">
            编辑路由
          </NButton>
          <NButton
            quaternary
            attr-type="button"
            class="text-button"
            :disabled="!operate || busy || !!session.draft"
            @click="
              deleting = r;
              deleteName = '';
            "
          >
            删除路由
          </NButton>
        </div>
      </article>
    </div>
    <ConsoleForm v-if="deleting" class="inline-form spaced" @submit.prevent="remove">
      <ConsoleField path="field-12" :label-props="{ id: 'routingpanel-field-12' + '-label', for: 'routingpanel-field-12' }">
        <template #label>输入路由名称“{{ deleting.definition.name }}”</template>
        <NInput
          :disabled="busy"
          v-model:value="deleteName"
          :input-props="{ id: 'routingpanel-field-12', 'aria-label': '输入路由名称“' + deleting.definition.name + '”' }"
        />
      </ConsoleField>
      <div class="form-footer">
        <NButton quaternary attr-type="button" class="text-button" :disabled="busy" @click="deleting = null">取消删除</NButton>
        <NButton type="error" attr-type="submit" class="primary" :disabled="busy || deleteName !== deleting.definition.name">
          确认删除路由
        </NButton>
      </div>
    </ConsoleForm>
    <ConsoleForm class="inline-form spaced" @submit.prevent="test">
      <h2>路由预演</h2>
      <div class="form-columns spaced">
        <ConsoleField
          path="field-13"
          :rule="fieldRule(() => testSource, { required: true })"
          :label-props="{ id: 'routingpanel-field-13' + '-label', for: 'routingpanel-field-13' }"
        >
          <template #label>来源</template>
          <NInput v-model:value="testSource" :input-props="{ required: true, id: 'routingpanel-field-13', 'aria-label': '来源' }" />
        </ConsoleField>
        <ConsoleField
          path="field-14"
          :rule="fieldRule(() => testType, { required: true })"
          :label-props="{ id: 'routingpanel-field-14' + '-label', for: 'routingpanel-field-14' }"
        >
          <template #label>事件类型</template>
          <NInput v-model:value="testType" :input-props="{ required: true, id: 'routingpanel-field-14', 'aria-label': '事件类型' }" />
        </ConsoleField>
      </div>
      <ConsoleField
        class="spaced"
        path="field-15"
        :rule="fieldRule(() => testTitle, { required: true, maxlength: 160 })"
        :label-props="{ id: 'routingpanel-field-15' + '-label', for: 'routingpanel-field-15' }"
      >
        <template #label>标题</template>
        <NInput
          v-model:value="testTitle"
          :maxlength="160"
          :input-props="{ required: true, id: 'routingpanel-field-15', 'aria-label': '标题' }"
        />
      </ConsoleField>
      <ConsoleField
        class="spaced"
        path="field-16"
        :rule="fieldRule(() => testContent, { required: true, maxlength: 8192 })"
        :label-props="{ id: 'routingpanel-field-16' + '-label', for: 'routingpanel-field-16' }"
      >
        <template #label>内容</template>
        <NInput
          v-model:value="testContent"
          type="textarea"
          :maxlength="8192"
          :input-props="{ required: true, id: 'routingpanel-field-16', 'aria-label': '内容' }"
        />
      </ConsoleField>
      <div class="form-footer">
        <span>不发送消息，不改变设备免打扰设置。</span>
        <NButton attr-type="submit" class="secondary" :disabled="busy || loading || !!error">预演匹配设备</NButton>
      </div>
      <p v-if="preview" role="status">{{ preview.length ? `匹配：${preview.join('、')}` : '无匹配设备' }}</p>
    </ConsoleForm>
    <ConsoleForm v-if="operate" class="inline-form spaced" @submit.prevent="send">
      <h2>定向测试消息</h2>
      <p>使用上方标题与内容，仅向选择的启用设备建立投递；离线会等待，设备免打扰仍生效。</p>
      <ConsoleField
        path="field-17"
        :rule="fieldRule(() => selected, { required: true, multiple: true, disabled: () => busy || !!session.test })"
        :label-props="{ id: 'routingpanel-field-17' + '-label', for: 'routingpanel-field-17' }"
      >
        <template #label>测试设备</template>
        <NSelect
          @keydown.esc.stop
          :disabled="busy || !!session.test"
          v-model:value="selected"
          :options="[...devices.filter((v) => v.enabled).map((d) => ({ label: d.name, value: d.deviceId }))]"
          multiple
          :max-tag-count="2"
          :input-props="{ id: 'routingpanel-field-17', 'aria-label': '测试设备' }"
        />
      </ConsoleField>
      <div class="form-columns spaced">
        <ConsoleField path="field-18" :label-props="{ id: 'routingpanel-field-18' + '-label', for: 'routingpanel-field-18' }">
          <template #label>测试模板</template>
          <NSelect
            @keydown.esc.stop
            :disabled="busy || !!session.test"
            v-model:value="testTemplate"
            :options="[...templates.map((t) => ({ label: t.definition.name, value: t.templateId }))]"
            clearable
            :placeholder="'原始文本'"
            :input-props="{ id: 'routingpanel-field-18', 'aria-label': '测试模板' }"
          />
        </ConsoleField>
        <ConsoleField path="field-19" :label-props="{ id: 'routingpanel-field-19' + '-label', for: 'routingpanel-field-19' }">
          <template #label>测试优先级</template>
          <NSelect
            @keydown.esc.stop
            :disabled="busy || !!session.test"
            v-model:value="testPriority"
            :options="[
              { label: '低', value: 'low' },
              { label: '普通', value: 'normal' },
              { label: '高', value: 'high' },
              { label: '紧急', value: 'critical' },
            ]"
            :input-props="{ id: 'routingpanel-field-19', 'aria-label': '测试优先级' }"
          />
        </ConsoleField>
      </div>
      <p v-if="session.test" role="status">结果未确认时重试相同请求；切换导航后仍保留。当前标题：{{ session.test.title }}</p>
      <div class="form-footer">
        <span v-if="sent">消息编号：{{ sent }}；回执请在投递页核对。</span>
        <NButton
          type="primary"
          attr-type="submit"
          class="primary"
          :disabled="busy || loading || !!error || (!session.test && !selected.length)"
        >
          {{ session.test ? '重试同一测试消息' : '发送到选择设备' }}
        </NButton>
      </div>
    </ConsoleForm>
  </section>
</template>
