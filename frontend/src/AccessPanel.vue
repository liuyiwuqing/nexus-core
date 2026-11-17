<script setup lang="ts">
import ConsoleDisclosure from './ConsoleDisclosure.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NCheckbox, NInput, NInputNumber, NSelect } from 'naive-ui';
import { nextTick, onUnmounted, ref, toRef } from 'vue';
import { formatTime, type Api, type AccessSession } from './types';
import StatusNote from './StatusNote.vue';
const props=defineProps<{session:AccessSession;api:Api;busy:boolean;mutate:(a:()=>Promise<unknown>,n:string)=>Promise<boolean>}>();
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
    <p v-if="loading" role="status">正在读取…</p>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
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
      <article v-for="t in tokens" :key="t.tokenId" class="record-card">
        <div class="record-heading">
          <h2>{{ t.name }}</h2>
          <span class="badge">{{ t.role }}</span>
        </div>
        <p>{{ t.revokedAt ? '已撤销' : `到期：${formatTime(t.expiresAt)}` }}</p>
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
      </article>
    </div>
    <ConsoleForm v-if="revoking" class="inline-form spaced" @submit.prevent="revoke">
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
      <div class="table-shell" tabindex="0" role="region" aria-label="操作审计，可用方向键横向滚动查看全部列">
        <table>
          <thead>
            <tr>
              <th>时间 / 角色</th>
              <th>操作</th>
              <th>结果</th>
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
