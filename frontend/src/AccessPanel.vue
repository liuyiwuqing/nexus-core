<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import AdminTokenEditor from './AdminTokenEditor.vue';
import { ApiFailure } from './failure';
import type { ConsoleSession } from './access';
import ConsoleDisclosure from './ConsoleDisclosure.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NCheckbox, NInput, NInputNumber, NSelect, NDatePicker } from 'naive-ui';
import { nextTick, onUnmounted, ref, toRef } from 'vue';
import { formatTime, type Api, type AccessSession } from './types';
import StatusNote from './StatusNote.vue';
const props=defineProps<{session:AccessSession;api:Api;busy:boolean;mutate:(a:()=>Promise<unknown>,n:string)=>Promise<boolean>;replaceSession:(session:ConsoleSession,token:string)=>void}>();
interface Credential {tokenId:string;name:string;role:string;version:number;expiresAt:string;revokedAt:string|null}
interface Audit {auditId:string;actor:string;role:string;method:string;target:string;status:number|null;createdAt:string;completedAt:string|null}
const tokens=ref<Credential[]>([]),audit=ref<Audit[]>([]),error=ref(''),loading=ref(false),page=ref(1),total=ref(0),filter=ref('');
const name=ref(''),role=ref('viewer'),days=ref(30),issued=toRef(props.session,'issued'),revealed=ref(false),pendingIssue=toRef(props.session,'pendingIssue'),revoking=ref<Credential|null>(null),confirmName=ref('');
const nameField=ref<InstanceType<typeof NInput>|null>(null);
/* 点掉"已核对"后提示连同按钮一起卸载，焦点会落到 body；把用户送到他下一步真正要用的字段上。 */
async function startNewIssue(){pendingIssue.value=false;await nextTick();nameField.value?.focus();void load();}
let alive=true,g=0,c:AbortController|undefined;
async function load(){c?.abort();const control=new AbortController();c=control;const current=++g;loading.value=true;error.value='';try{const [a,b]=await Promise.all([props.api<{items:Credential[]}>('access/tokens','GET',undefined,control.signal),props.api<{items:Audit[];total:number}>(`audit?page=${page.value}${filter.value?'&role='+filter.value:''}`,'GET',undefined,control.signal)]);if(alive&&current===g){tokens.value=a.items;audit.value=b.items;total.value=b.total;}}catch(e){if(alive&&current===g&&!control.signal.aborted){tokens.value=[];audit.value=[];error.value=e instanceof Error?e.message:'读取失败';}}finally{if(alive&&current===g)loading.value=false;}}
async function issue(){if(pendingIssue.value||props.busy)return;pendingIssue.value=true;const session=props.session;const ok=await props.mutate(async()=>{const value=await props.api<{token:string}>('access/tokens','POST',{name:name.value,role:role.value,expiresAt:new Date(Date.now()+days.value*86400000).toISOString()});if(!value?.token||value.token.length<32)throw new Error('签发结果未确认');session.issued=value.token;if(alive)name.value='';},'角色令牌已签发，仅本次显示。');if(alive){if(ok)pendingIssue.value=false;await load();}}
async function revoke(){const item=revoking.value;if(!item||confirmName.value!==item.name)return;await props.mutate(()=>props.api(`access/tokens/${item.tokenId}/revoke`,'POST',{expectedVersion:item.version}),'令牌已撤销，后续请求立即失效。');if(alive){revoking.value=null;await load();}}
const editing=ref<Credential|null>(null),regenerating=ref<Credential|null>(null),editName=ref(''),editRole=ref('viewer'),editExpiry=ref<number|null>(null),editUncertain=ref(false),rotationName=ref('');
const editInput=ref<InstanceType<typeof NInput>|null>(null);
const active=(item:Credential)=>!item.revokedAt&&new Date(item.expiresAt).valueOf()>Date.now();
async function beginEdit(item:Credential){editing.value=item;regenerating.value=null;editName.value=item.name;editRole.value=item.role;editExpiry.value=new Date(item.expiresAt).valueOf();editUncertain.value=false;await nextTick();editInput.value?.focus();}
async function saveEdit(){
  const item=editing.value;if(!item||props.busy||editUncertain.value||!editExpiry.value)return;
  editUncertain.value=true;
  const ok=await props.mutate(async()=>{try{const saved=await props.api<{version:number;tokenId:string}>(`access/tokens/${item.tokenId}`,'PUT',{name:editName.value,role:editRole.value,expiresAt:new Date(editExpiry.value!).toISOString(),expectedVersion:item.version});if(saved.tokenId!==item.tokenId||saved.version!==item.version+1)throw new Error('Unconfirmed token edit');if(alive)editUncertain.value=false;}catch(failure){if(alive&&failure instanceof ApiFailure&&failure.status>=400&&failure.status<500&&failure.status!==408)editUncertain.value=false;throw failure;}},'角色令牌已更新，权限和有效期立即生效。');
  if(alive){if(ok)editing.value=null;await load();}
}
async function reloadEdit(){const id=editing.value?.tokenId;await load();if(!alive||error.value)return;const item=tokens.value.find(t=>t.tokenId===id);if(item&&active(item))await beginEdit(item);else editing.value=null;}
async function rotate(){
  const item=regenerating.value;if(!item||props.busy||rotationName.value!==item.name||props.session.pendingRotation)return;
  const session=props.session;session.pendingRotation={tokenId:item.tokenId,name:item.name,version:item.version};
  const ok=await props.mutate(async()=>{try{const value=await props.api<{tokenId:string;version:number;token:string}>(`access/tokens/${item.tokenId}/rotate`,'POST',{expectedVersion:item.version});if(value.tokenId!==item.tokenId||value.version!==item.version+1||!value.token||value.token.length<32)throw new Error('Unconfirmed token rotation');session.issued=value.token;session.pendingRotation=null;}catch(failure){if(failure instanceof ApiFailure&&failure.status>=400&&failure.status<500&&failure.status!==408)session.pendingRotation=null;throw failure;}},'角色令牌已重新生成，旧凭据立即失效；请保存本次新值。');
  if(alive){if(ok){regenerating.value=null;revealed.value=false;}await load();}
}
async function acknowledgeRotation(){await load();if(alive&&!error.value){props.session.pendingRotation=null;regenerating.value=null;}}
void load();onUnmounted(()=>{alive=false;g++;c?.abort();});
</script>
<template>
  <section>
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">权限与审计</h1>
      </div>
      <NButton attr-type="button" class="text-button" :disabled="busy || loading" @click="load">刷新权限与审计</NButton>
    </div>
    <AdminTokenEditor :api="api" :busy="busy" :mutate="mutate" :replace-session="replaceSession" />
    <ConsoleState v-if="loading" kind="loading" :compact="!!tokens.length">正在读取…</ConsoleState>
    <ConsoleState v-if="error" kind="error" compact>{{ error }}</ConsoleState>
    <div v-if="issued" class="inline-form">
      <h2>本次角色令牌</h2>
      <NInput
        readonly
        :type="revealed ? 'text' : 'password'"
        :value="issued"
        :input-props="{ autocomplete: 'off', 'aria-label': '本次角色令牌' }"
      />
      <NCheckbox class="checkbox-label spaced" v-model:checked="revealed">显示令牌</NCheckbox>
      <NButton
        attr-type="button"
        class="text-button"
        @click="
          issued = '';
          revealed = false;
        "
      >
        已保存，隐藏角色令牌
      </NButton>
    </div>
    <StatusNote v-if="session.pendingRotation && !busy" tone="warn" class="spaced">
      “{{ session.pendingRotation.name }}”的重新生成结果未确认，旧凭据可能已失效。请刷新核对版本，再重新生成并保存新值。
      <template #actions><NButton attr-type="button" :disabled="busy || loading" @click="acknowledgeRotation">刷新核对后继续</NButton></template>
    </StatusNote>
    <ConsoleDisclosure label="签发角色令牌" class="access-issue" :force-open="pendingIssue">
    <ConsoleForm class="inline-form" @submit.prevent="issue">
      <ConsoleField
        path="field-1"
        :rule="fieldRule(() => name, { required: true, maxlength: 80, disabled: () => busy || pendingIssue })"
        :label-props="{ id: 'accesspanel-field-1' + '-label', for: 'accesspanel-field-1' }"
      >
        <template #label>名称</template>
        <NInput
          ref="nameField"
          :disabled="busy || pendingIssue"
          v-model:value="name"
          :maxlength="80"
          :input-props="{ required: true, id: 'accesspanel-field-1', 'aria-label': '名称' }"
        />
      </ConsoleField>
      <div class="form-columns spaced">
        <ConsoleField path="field-2" :label-props="{ id: 'accesspanel-field-2' + '-label', for: 'accesspanel-field-2' }">
          <template #label>角色</template>
          <NSelect
            @keydown.esc.stop
            :disabled="busy || pendingIssue"
            v-model:value="role"
            :options="[
              { label: 'viewer · 只读', value: 'viewer' },
              { label: 'operator · 消息与规则', value: 'operator' },
            ]"
            :input-props="{ id: 'accesspanel-field-2', 'aria-label': '角色' }"
          />
        </ConsoleField>
        <ConsoleField
          path="field-3"
          :rule="fieldRule(() => days, { required: true, min: 1, max: 365, number: true, disabled: () => busy || pendingIssue })"
          :label-props="{ id: 'accesspanel-field-3' + '-label', for: 'accesspanel-field-3' }"
        >
          <template #label>有效天数</template>
          <NInputNumber
            :disabled="busy || pendingIssue"
            v-model:value="days"
            :min="1"
            :max="365"
            :input-props="{ required: true, id: 'accesspanel-field-3', 'aria-label': '有效天数' }"
          />
        </ConsoleField>
      </div>
      <StatusNote v-if="pendingIssue" tone="warn">
        签发结果未确认，不能直接重复签发；请刷新核对列表，撤销无法取得的令牌后再操作。
        <template #actions>
          <NButton attr-type="button" class="text-button" :disabled="busy" @click="startNewIssue">已核对，开始新的签发</NButton>
        </template>
      </StatusNote>
      <div class="form-footer">
        <span>不会新增管理员令牌；凭据只显示一次。</span>
        <NButton type="primary" attr-type="submit" class="primary" :disabled="busy || loading || !!error || pendingIssue">签发令牌</NButton>
      </div>
    </ConsoleForm>
    </ConsoleDisclosure>
    <div v-if="!loading && !error" class="record-list spaced">
      <ConsoleState v-if="!tokens.length" title="暂无角色令牌" compact />
      <article v-for="t in tokens" :key="t.tokenId" class="record-card">
        <div class="record-heading">
          <h2>{{ t.name }}</h2>
          <span class="badge">{{ t.role }}</span>
        </div>
        <p>{{ t.revokedAt ? '已撤销' : !active(t) ? '已到期' : `到期：${formatTime(t.expiresAt)}` }} · v{{ t.version }}</p>
        <div class="form-actions">
          <NButton attr-type="button" :disabled="busy || !active(t)" @click="beginEdit(t)">编辑令牌</NButton>
          <NButton attr-type="button" :disabled="busy || !active(t) || !!session.pendingRotation" @click="regenerating=t;editing=null;rotationName=''">重新生成</NButton>
        <NButton
          attr-type="button"
          class="text-button"
          :disabled="busy || !!t.revokedAt"
          @click="
            revoking = t;
            confirmName = '';
          "
        >
          撤销令牌
        </NButton>
        </div>
      </article>
    </div>
    <ConsoleForm v-if="editing" class="inline-form spaced" @submit.prevent="saveEdit">
      <h2>编辑令牌 · {{ editing.name }}</h2>
      <ConsoleField path="editName" label="令牌名称" :rule="fieldRule(()=>editName,{required:true,maxlength:80,disabled:()=>busy||editUncertain})"><NInput ref="editInput" v-model:value="editName" :disabled="busy || editUncertain" :maxlength="80" :input-props="{'aria-label':'令牌名称'}" /></ConsoleField>
      <div class="form-columns spaced">
        <ConsoleField path="editRole" label="令牌角色"><NSelect v-model:value="editRole" :disabled="busy || editUncertain" :options="[{label:'viewer · 只读',value:'viewer'},{label:'operator · 消息与规则',value:'operator'}]" :input-props="{'aria-label':'令牌角色'}" /></ConsoleField>
        <ConsoleField path="editExpiry" label="令牌到期时间" :rule="fieldRule(()=>editExpiry,{required:true,number:true,min:Date.now(),max:Date.now()+366*86400000,step:'any',disabled:()=>busy||editUncertain})"><NDatePicker v-model:value="editExpiry" type="datetime" :disabled="busy || editUncertain" aria-label="令牌到期时间" /></ConsoleField>
      </div>
      <StatusNote v-if="editUncertain && !busy" tone="warn">保存结果待核对，重新读取会用服务端内容替换草稿。<template #actions><NButton attr-type="button" :disabled="busy || loading" @click="reloadEdit">重新读取令牌</NButton></template></StatusNote>
      <div class="form-footer spaced"><span>权限和有效期修改后立即生效。</span><div class="form-actions"><NButton attr-type="button" :disabled="busy" @click="editing=null">取消编辑</NButton><NButton type="primary" attr-type="submit" :disabled="busy || editUncertain">保存令牌</NButton></div></div>
    </ConsoleForm>
    <ConsoleForm v-if="regenerating && !session.pendingRotation" class="inline-form spaced confirmation-panel" @submit.prevent="rotate">
      <h2>重新生成 · {{ regenerating.name }}</h2>
      <p>旧凭据立即失效，新凭据仅显示一次；名称、权限、有效期和身份保持不变。</p>
      <ConsoleField path="rotationName" :label="`输入名称“${regenerating.name}”确认重新生成`"><NInput v-model:value="rotationName" :disabled="busy" :input-props="{'aria-label':'确认重新生成的令牌名称'}" /></ConsoleField>
      <div class="form-footer"><NButton attr-type="button" :disabled="busy" @click="regenerating=null">取消重新生成</NButton><NButton type="primary" attr-type="submit" :disabled="busy || rotationName!==regenerating.name">确认重新生成</NButton></div>
    </ConsoleForm>
    <ConsoleForm v-if="revoking" class="inline-form spaced confirmation-panel is-destructive" @submit.prevent="revoke">
      <ConsoleField path="field-4" :label-props="{ id: 'accesspanel-field-4' + '-label', for: 'accesspanel-field-4' }">
        <template #label>输入名称“{{ revoking.name }}”确认撤销</template>
        <NInput
          :disabled="busy"
          v-model:value="confirmName"
          :input-props="{ id: 'accesspanel-field-4', 'aria-label': '输入名称“' + revoking.name + '”确认撤销' }"
        />
      </ConsoleField>
      <div class="form-footer">
        <NButton attr-type="button" class="text-button" :disabled="busy" @click="revoking = null">取消撤销</NButton>
        <NButton type="error" attr-type="submit" class="primary" :disabled="busy || confirmName !== revoking.name">确认撤销令牌</NButton>
      </div>
    </ConsoleForm>
    <ConsoleDisclosure label="操作审计" class="access-audit" :summary="`${total} 条`" :force-open="audit.some(a => !a.completedAt)">
      <div class="section-heading">
        <ConsoleField path="field-5" :label-props="{ id: 'accesspanel-field-5' + '-label', for: 'accesspanel-field-5' }">
          <template #label>角色筛选</template>
          <NSelect
            @keydown.esc.stop
            :disabled="loading"
            v-model:value="filter"
            @update:value="
              page = 1;
              load();
            "
            :options="[
              { label: '全部', value: '' },
              ...['admin', 'operator', 'viewer', 'source', 'anonymous'].map((r) => ({ label: r, value: r })),
            ]"
            :input-props="{ id: 'accesspanel-field-5', 'aria-label': '角色筛选' }"
          />
        </ConsoleField>
      </div>
      <p>记录请求结果，不含令牌、消息正文或参数；未完成记录表示结果需要核对。</p>
      <ConsoleState v-if="!loading && !error && !audit.length" compact>暂无操作审计。</ConsoleState>
      <div v-if="audit.length && !error" class="table-shell" tabindex="0" role="region" aria-label="操作审计，可用方向键横向滚动查看全部列">
        <table>
          <thead>
            <tr>
              <th scope="col">时间 / 角色</th>
              <th scope="col">操作</th>
              <th scope="col">结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in audit" :key="a.auditId">
              <td>
                {{ formatTime(a.createdAt) }}
                <span class="cell-sub">{{ a.role }} · {{ a.actor }}</span>
              </td>
              <td data-label="操作">
                {{ a.method }}
                <span class="cell-sub mono">{{ a.target }}</span>
              </td>
              <td data-label="结果">{{ a.completedAt ? a.status : '待核对' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <NButton
          attr-type="button"
          :disabled="loading || page <= 1"
          @click="
            page--;
            load();
          "
        >
          上一页审计
        </NButton>
        <span>{{ page }} / {{ Math.max(1, Math.ceil(total / 100)) }}</span>
        <NButton
          attr-type="button"
          :disabled="loading || page * 100 >= total"
          @click="
            page++;
            load();
          "
        >
          下一页审计
        </NButton>
      </div>
    </ConsoleDisclosure>
  </section>
</template>
