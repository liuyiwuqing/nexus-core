<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { errorNames, type AutomationRule, type RuleEditInput, type RuleEditSession } from './types';
const props = defineProps<{ session: RuleEditSession; current?: AutomationRule; disabled: boolean; failed: boolean }>();
const emit = defineEmits<{ change: [input: RuleEditInput]; save: []; check: [reload: boolean]; close: [] }>();
const nameInput = ref<HTMLInputElement>();
const changed = computed(() => !!props.current && props.current.version !== props.session.rule.version);
const blocked = computed(() => !props.current?.canEdit || changed.value);
const frozen = computed(() => props.disabled || !!props.session.pending || blocked.value || props.failed);
function change(key: keyof RuleEditInput, event: Event) {
  emit('change', { ...props.session.draft, [key]: (event.target as HTMLInputElement).value });
}
function condition(key:string,event:Event){emit('change',{...props.session.draft,conditions:{...props.session.draft.conditions,[key]:(event.target as HTMLInputElement).value}});}
onMounted(() => nameInput.value?.focus());
</script>
<template>
  <form class="inline-form rule-editor" aria-label="编辑规则" @submit.prevent="emit('save')">
    <div class="record-heading"><h2>编辑规则</h2><span class="muted">{{ session.rule.trigger === 'schedule.daily' ? '每日设备提醒' : session.rule.action==='notify_device'?'新消息条件提醒':'公开评论分析' }} · 版本 {{ session.rule.version }}</span></div>
    <p v-if="session.uncertain" class="scope-note" role="status">保存结果尚未确认。重试保持原版本和参数；也可只读核对当前规则。刷新或关闭页面后请先核对规则列表。</p>
    <p v-else-if="changed" class="inline-error" role="alert">规则版本已变化，请核对后载入当前版本；草稿仍保留。</p>
    <p v-else-if="current?.editBlock" class="scope-note" role="status">{{ errorNames[current.editBlock] || '当前规则不可编辑。' }}</p>
    <label class="spaced">名称<input ref="nameInput" aria-label="编辑规则名称" :value="session.draft.name" maxlength="80" required :disabled="frozen" @input="change('name', $event)" /></label>
    <template v-if="session.rule.action === 'notify_device'">
      <div v-if="session.rule.trigger === 'schedule.daily'" class="form-columns spaced"><label>时间<input aria-label="编辑提醒时间" :value="session.draft.localTime" type="time" required :disabled="frozen" @input="change('localTime', $event)" /></label><label>时区<input aria-label="编辑提醒时区" :value="session.draft.zone" maxlength="100" required :disabled="frozen" @input="change('zone', $event)" /></label></div>
      <label class="spaced">提醒标题<input aria-label="编辑提醒标题" :value="session.draft.title" maxlength="160" required :disabled="frozen" @input="change('title', $event)" /></label>
      <label class="spaced">提醒内容<textarea aria-label="编辑提醒内容" :value="session.draft.content" maxlength="8192" rows="3" required :disabled="frozen" @input="change('content', $event)"></textarea></label>
    </template>
    <div v-if="session.rule.trigger === 'message.received'" class="form-columns spaced"><label v-for="(label,key) in {source:'匹配来源',eventType:'匹配事件类型',keyword:'包含关键词',priority:'匹配优先级'}" :key="key">{{label}}<select v-if="key==='priority'" :aria-label="'编辑'+label" :value="session.draft.conditions?.[key]||''" :disabled="frozen" @change="condition(key,$event)"><option value="">不限</option><option value="low">低</option><option value="normal">普通</option><option value="high">高</option><option value="critical">紧急</option></select><input v-else :aria-label="'编辑'+label" :value="session.draft.conditions?.[key]||''" :maxlength="key==='keyword'?160:key==='source'?32:64" :disabled="frozen" @input="condition(key,$event)"></label></div>
    <details v-if="session.observed" class="rule-readback spaced" open><summary>当前服务端定义 · 版本 {{ session.observed.version }} · {{ session.observed.enabled ? '已启用' : '已停用' }}</summary><p>{{ session.observed.name }}</p><template v-if="session.observed.action === 'notify_device'"><p v-if="session.observed.trigger==='schedule.daily'">{{ session.observed.arguments.localTime }}（{{ session.observed.arguments.zone }}）</p><p>{{ session.observed.arguments.title }}</p><p class="rule-readback-content">{{ session.observed.arguments.content }}</p></template><p v-if="session.observed.trigger==='message.received'">条件：{{ ['source','eventType','keyword','priority'].map(k=>session.observed?.arguments[k+'Filter']).filter(Boolean).join(' · ') || '不限' }}</p><button class="text-button" type="button" :disabled="disabled" @click="emit('check', true)">放弃草稿并载入当前版本</button></details>
    <div class="form-footer"><span>{{ session.rule.trigger === 'schedule.daily' ? '保存后保持停用；同一本地日期的提醒不会重发。' : session.rule.action==='notify_device'?'保存后保持停用；重新启用后只处理新的匹配消息。':'保存后保持停用；重新启用后只处理新的公开评论。' }}</span><div class="management-actions"><button class="text-button" type="button" :disabled="disabled" @click="emit('close')">关闭编辑</button><button class="secondary" type="button" :disabled="disabled" @click="emit('check', false)">核对当前规则</button><button class="primary" :disabled="disabled || failed || (!session.pending && blocked)">{{ session.uncertain ? '重试原版本修改' : '保存修改' }}</button></div></div>
  </form>
</template>
