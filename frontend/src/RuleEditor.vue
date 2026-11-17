<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import ConsoleDisclosure from './ConsoleDisclosure.vue';
import ConsoleField from './ConsoleField.vue';
import { fieldRule } from './formRules';
import ConsoleForm from './ConsoleForm.vue';
import { NButton, NInput, NSelect, NTimePicker } from 'naive-ui';
import { computed, onMounted, ref } from 'vue';
import { errorNames, type AutomationRule, type RuleEditInput, type RuleEditSession } from './types';
import StatusNote from './StatusNote.vue';
const props = defineProps<{ session: RuleEditSession; current?: AutomationRule; disabled: boolean; failed: boolean }>();
const emit = defineEmits<{ change: [input: RuleEditInput]; save: []; check: [reload: boolean]; close: [] }>();
const nameInput = ref<InstanceType<typeof NInput>>();
const changed = computed(() => !!props.current && props.current.version !== props.session.rule.version);
const blocked = computed(() => !props.current?.canEdit || changed.value);
const frozen = computed(() => props.disabled || !!props.session.pending || blocked.value || props.failed);
function change(key: keyof RuleEditInput, value: string | null) {
  emit('change', { ...props.session.draft, [key]: value ?? '' });
}
function condition(key:string,value:string){emit('change',{...props.session.draft,conditions:{...props.session.draft.conditions,[key]:value ?? ''}});}
onMounted(() => nameInput.value?.focus());
</script>
<template>
  <ConsoleForm class="inline-form rule-editor" aria-label="编辑规则" @submit.prevent="emit('save')">
    <div class="record-heading">
      <h2>编辑规则</h2>
      <span class="muted">
        {{
          session.rule.trigger === 'schedule.daily'
            ? '每日设备提醒'
            : session.rule.action === 'notify_device'
              ? '新消息条件提醒'
              : '公开评论分析'
        }}
        · 版本 {{ session.rule.version }}
      </span>
    </div>
    <StatusNote v-if="session.uncertain" tone="warn">
      保存结果尚未确认。重试保持原版本和参数；也可只读核对当前规则。刷新或关闭页面后请先核对规则列表。
    </StatusNote>
    <ConsoleState v-else-if="changed" kind="error" compact>规则版本已变化，请核对后载入当前版本；草稿仍保留。</ConsoleState>
    <p v-else-if="current?.editBlock" class="scope-note" role="status">{{ errorNames[current.editBlock] || '当前规则不可编辑。' }}</p>
    <ConsoleField
      class="spaced"
      path="field-1"
      :rule="fieldRule(() => session.draft?.name, { required: true, maxlength: 80, disabled: () => frozen })"
      :label-props="{ id: 'ruleeditor-field-1' + '-label', for: 'ruleeditor-field-1' }"
    >
      <template #label>名称</template>
      <NInput
        ref="nameInput"
        :disabled="frozen"
        :value="session.draft.name"
        @update:value="change('name', $event)"
        :maxlength="80"
        :input-props="{ required: true, 'aria-label': '编辑规则名称', id: 'ruleeditor-field-1' }"
      />
    </ConsoleField>
    <template v-if="session.rule.action === 'notify_device'">
      <div v-if="session.rule.trigger === 'schedule.daily'" class="form-columns spaced">
        <ConsoleField
          path="field-2"
          :rule="fieldRule(() => session.draft?.localTime, { required: true, disabled: () => frozen })"
          :label-props="{ id: 'ruleeditor-field-2' + '-label', for: 'ruleeditor-field-2' }"
        >
          <template #label>时间</template>
          <NTimePicker
            @keydown.esc.stop
            aria-label="编辑提醒时间"
            :disabled="frozen"
            :formatted-value="session.draft.localTime"
            @update:formatted-value="change('localTime', $event)"
            format="HH:mm"
            value-format="HH:mm"
          />
        </ConsoleField>
        <ConsoleField
          path="field-3"
          :rule="fieldRule(() => session.draft?.zone, { required: true, maxlength: 100, disabled: () => frozen })"
          :label-props="{ id: 'ruleeditor-field-3' + '-label', for: 'ruleeditor-field-3' }"
        >
          <template #label>时区</template>
          <NInput
            :disabled="frozen"
            :value="session.draft.zone"
            @update:value="change('zone', $event)"
            :maxlength="100"
            :input-props="{ required: true, 'aria-label': '编辑提醒时区', id: 'ruleeditor-field-3' }"
          />
        </ConsoleField>
      </div>
      <ConsoleField
        class="spaced"
        path="field-4"
        :rule="fieldRule(() => session.draft?.title, { required: true, maxlength: 160, disabled: () => frozen })"
        :label-props="{ id: 'ruleeditor-field-4' + '-label', for: 'ruleeditor-field-4' }"
      >
        <template #label>提醒标题</template>
        <NInput
          :disabled="frozen"
          :value="session.draft.title"
          @update:value="change('title', $event)"
          :maxlength="160"
          :input-props="{ required: true, 'aria-label': '编辑提醒标题', id: 'ruleeditor-field-4' }"
        />
      </ConsoleField>
      <ConsoleField
        class="spaced"
        path="field-5"
        :rule="fieldRule(() => session.draft?.content, { required: true, maxlength: 8192, disabled: () => frozen })"
        :label-props="{ id: 'ruleeditor-field-5' + '-label', for: 'ruleeditor-field-5' }"
      >
        <template #label>提醒内容</template>
        <NInput
          rows="3"
          :disabled="frozen"
          :value="session.draft.content"
          @update:value="change('content', $event)"
          type="textarea"
          :maxlength="8192"
          :input-props="{ required: true, 'aria-label': '编辑提醒内容', id: 'ruleeditor-field-5' }"
        />
      </ConsoleField>
    </template>
    <ConsoleDisclosure v-if="session.rule.trigger === 'message.received'" label="匹配条件" class="rule-conditions" :level="3"
      :summary="Object.values(session.draft.conditions || {}).filter(Boolean).length ? `${Object.values(session.draft.conditions || {}).filter(Boolean).length} 项` : '不限'"
      :force-open="!!session.pending">
    <div class="form-columns">
      <ConsoleField
        v-for="(label, key) in { source: '匹配来源', eventType: '匹配事件类型', keyword: '包含关键词', priority: '匹配优先级' }"
        :key="key"
        :path="'field-6-' + key"
        :label-props="{ id: 'ruleeditor-field-1' + '-' + key + '-' + key + '-label', for: 'ruleeditor-field-1' + '-' + key + '-' + key }"
      >
        <template #label>{{ label }}</template>
        <NSelect
          @keydown.esc.stop
          v-if="key === 'priority'"
          :disabled="frozen"
          :value="session.draft.conditions?.[key] || ''"
          @update:value="condition(key, $event)"
          :options="[
            { label: '不限', value: '' },
            { label: '低', value: 'low' },
            { label: '普通', value: 'normal' },
            { label: '高', value: 'high' },
            { label: '紧急', value: 'critical' },
          ]"
          :aria-label="'编辑' + label"
          :input-props="{ id: 'ruleeditor-field-1' + '-' + key, 'aria-label': '编辑' + label }"
        />
        <NInput
          v-else
          :disabled="frozen"
          :value="session.draft.conditions?.[key] || ''"
          @update:value="condition(key, $event)"
          :maxlength="key === 'keyword' ? 160 : key === 'source' ? 32 : 64"
          :input-props="{ 'aria-label': '编辑' + label }"
        />
      </ConsoleField>
    </div>
    </ConsoleDisclosure>
    <details v-if="session.observed" class="rule-readback spaced" :open="blocked || changed || !!session.pending">
      <summary>当前服务端定义 · 版本 {{ session.observed.version }} · {{ session.observed.enabled ? '已启用' : '已停用' }}</summary>
      <p>{{ session.observed.name }}</p>
      <template v-if="session.observed.action === 'notify_device'">
        <p v-if="session.observed.trigger === 'schedule.daily'">
          {{ session.observed.arguments.localTime }}（{{ session.observed.arguments.zone }}）
        </p>
        <p>{{ session.observed.arguments.title }}</p>
        <p class="rule-readback-content">{{ session.observed.arguments.content }}</p>
      </template>
      <p v-if="session.observed.trigger === 'message.received'">
        条件：{{
          ['source', 'eventType', 'keyword', 'priority']
            .map((k) => session.observed?.arguments[k + 'Filter'])
            .filter(Boolean)
            .join(' · ') || '不限'
        }}
      </p>
      <NButton attr-type="button" class="text-button" :disabled="disabled" @click="emit('check', true)">
        放弃草稿并载入当前版本
      </NButton>
    </details>
    <div class="form-footer">
      <span>
        {{
          session.rule.trigger === 'schedule.daily'
            ? '保存后保持停用；同一本地日期的提醒不会重发。'
            : session.rule.action === 'notify_device'
              ? '保存后保持停用；重新启用后只处理新的匹配消息。'
              : '保存后保持停用；重新启用后只处理新的公开评论。'
        }}
      </span>
      <div class="management-actions">
        <NButton attr-type="button" class="text-button" :disabled="disabled" @click="emit('close')">关闭编辑</NButton>
        <NButton attr-type="button" class="secondary" :disabled="disabled" @click="emit('check', false)">核对当前规则</NButton>
        <NButton type="primary" attr-type="submit" class="primary" :disabled="disabled || failed || (!session.pending && blocked)">
          {{ session.uncertain ? '重试原版本修改' : '保存修改' }}
        </NButton>
      </div>
    </div>
  </ConsoleForm>
</template>
