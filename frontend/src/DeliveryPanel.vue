<script setup lang="ts">
import ConsoleState from './ConsoleState.vue';
import ConsoleField from './ConsoleField.vue';
import { NButton, NSelect, type SelectInst } from 'naive-ui';
import { useAccess } from './access';
const { operate } = useAccess();
import { nextTick, ref } from 'vue';
import DeliveryHistoryPanel from './DeliveryHistoryPanel.vue';
import ListSearchForm from './ListSearchForm.vue';
import { formatTime, deliveryNames, deliveryErrorNames, errorNames, type Api, type Delivery, type Page, type ListSearch } from './types';
defineProps<{ data: Page<Delivery>; api: Api; busy: boolean; loading: boolean; failed: boolean; status: string; reason: string; search: ListSearch; exporting: boolean; retry: (id: string) => Promise<boolean> }>();
const emit = defineEmits<{ page: [page: number]; filter: [value: string]; reason: [value: string]; search: [value: ListSearch]; clear: []; export: []; refresh: [] }>();
const selected = ref<string | null>(null);
const statusSelect = ref<SelectInst | null>(null);
function filterChanged(value: string) { selected.value = null; emit('filter', value); }
function reasonChanged(value: string) { selected.value = null; emit('reason', value); }
function pageChanged(page: number) { selected.value = null; emit('page', page); }
async function closeHistory() {
  const id = selected.value; selected.value = null; await nextTick();
  const action = document.getElementById(`delivery-history-${id}`);
  if (action) action.focus();
  else statusSelect.value?.focus();
}
</script>
<template>
  <section>
    <div class="section-heading">
      <div>
        <h1 tabindex="-1">投递</h1>
        <p :aria-hidden="loading || failed">{{ loading || failed ? '\u00a0' : `${data.total} 条投递 · 区分已发送、设备确认与用户已读` }}</p>
      </div>
    </div>
    <ListSearchForm
      kind="deliveries"
      :filters="search"
      :status-active="!!status"
      :disabled="loading || busy"
      :failed="failed"
      :total="data.total"
      :exporting="exporting"
      @apply="
        selected = null;
        emit('search', $event);
      "
      @clear="
        selected = null;
        emit('clear');
      "
      @export="emit('export')"
    >
      <ConsoleField path="field-1" :label-props="{ id: 'deliverypanel-field-1' + '-label', for: 'deliverypanel-field-1' }">
        <template #label>投递状态</template>
        <NSelect
          ref="statusSelect"
          @keydown.esc.stop
          aria-label="投递状态"
          :disabled="loading || busy"
          :value="status"
          @update:value="filterChanged"
          :options="[
            { label: '全部', value: '' },
            ...Object.entries(deliveryNames).map(([value, name]) => ({ label: name, value: value })),
          ]"
          :input-props="{ id: 'deliverypanel-field-1', 'aria-label': '投递状态' }"
        />
      </ConsoleField>
      <!-- 失败原因只在状态=投递失败时出现：其它状态下这个筛选没有意义，显示出来只会误导。 -->
      <ConsoleField v-if="status === 'failed'" path="field-2" :label-props="{ id: 'deliverypanel-field-2-label', for: 'deliverypanel-field-2' }">
        <template #label>失败原因</template>
        <NSelect
          @keydown.esc.stop
          aria-label="失败原因"
          :disabled="loading || busy"
          :value="reason"
          @update:value="reasonChanged"
          :options="[
            { label: '全部原因', value: '' },
            ...Object.entries(deliveryErrorNames).map(([value, name]) => ({ label: name, value: value })),
          ]"
          :input-props="{ id: 'deliverypanel-field-2', 'aria-label': '失败原因' }"
        />
      </ConsoleField>
    </ListSearchForm>
    <ConsoleState v-if="loading && !data.items.length && !failed" kind="loading">正在加载投递…</ConsoleState>
    <ConsoleState v-else-if="!failed && !data.items.length && (status || reason || Object.values(search).some(Boolean))" title="没有符合条件的投递" />
    <ConsoleState v-else-if="!failed && !data.items.length" title="还没有投递记录">消息到达后，会为已启用的设备建立投递记录。</ConsoleState>
    <div v-else-if="!failed" class="table-shell" tabindex="0" role="region" aria-label="投递记录，可用方向键横向滚动查看全部列">
      <table>
        <thead>
          <tr>
            <th scope="col">消息 / 设备</th>
            <th scope="col">状态</th>
            <th scope="col">尝试</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="delivery in data.items" :key="delivery.eventId">
            <td>
              <strong>{{ delivery.title }}</strong>
              <span class="cell-sub mono">{{ delivery.deviceId }}</span>
            </td>
            <td data-label="状态">
              <span class="badge" :class="delivery.status === 'read' ? 'success' : delivery.status === 'failed' ? 'danger' : ''">
                {{ deliveryNames[delivery.status] || delivery.status }}
              </span>
              <span v-if="delivery.lastError" class="cell-sub">{{ deliveryErrorNames[delivery.lastError] || errorNames[delivery.lastError] || delivery.lastError }}</span>
              <span v-else-if="delivery.status === 'pending' || delivery.status === 'sent'" class="cell-sub">
                下次尝试 {{ formatTime(delivery.nextAttemptAt) }}
              </span>
            </td>
            <td data-label="尝试">{{ delivery.attempts }}</td>
            <td>
              <div class="management-actions">
                <NButton
                  attr-type="button"
                  v-if="operate && delivery.status === 'failed'"
                  class="secondary compact"
                  :disabled="busy"
                  @click="retry(delivery.eventId)"
                >
                  重试
                </NButton>
                <NButton
                  attr-type="button"
                  :id="`delivery-history-${delivery.eventId}`"
                  class="text-button"
                  :disabled="busy"
                  @click="selected = delivery.eventId"
                >
                  时间线
                </NButton>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="pagination">
        <NButton attr-type="button" :disabled="data.page <= 1 || loading || busy" @click="pageChanged(data.page - 1)">
          上一页
        </NButton>
        <span>{{ data.page }} / {{ Math.max(1, Math.ceil(data.total / data.size)) }}</span>
        <NButton
          attr-type="button"
          :disabled="data.page * data.size >= data.total || loading || busy"
          @click="pageChanged(data.page + 1)"
        >
          下一页
        </NButton>
      </div>
    </div>
    <DeliveryHistoryPanel
      v-if="selected"
      :key="selected"
      :event-id="selected"
      :api="api"
      :busy="busy"
      @close="closeHistory"
      @changed="emit('refresh')"
    />
  </section>
</template>
