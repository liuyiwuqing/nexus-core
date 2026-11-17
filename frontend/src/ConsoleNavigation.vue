<script setup lang="ts">
import { ChevronsLeft, ChevronsRight, LogOut } from '@lucide/vue';
import { NButton } from 'naive-ui';
import { computed } from 'vue';
import type { Role } from './access';
import type { Tab } from './types';
import { visibleNavigation } from './navigation';
import { prefetchTab } from './panels';
const props = defineProps<{ tab: Tab; role: Role; collapsed?: boolean }>();
defineEmits<{ navigate: [tab: Tab]; logout: []; toggleCollapse: [] }>();
const groups = computed(() => visibleNavigation(props.role));
</script>

<template>
  <aside class="sidebar">
    <div class="brand"><span class="pet-symbol" aria-hidden="true"><i></i><i></i></span><span>NEXUS<span class="brand-sub">桌面伙伴管理台</span></span></div>
    <nav aria-label="主导航">
      <div v-for="group in groups" :key="group.label" class="nav-group">
        <p class="nav-group-label">{{ group.label }}</p>
        <!-- 指针/键盘落到导航项即预取对应 chunk，点击时通常已在缓存里，切换不出现空窗。
             折叠后文字由 CSS 收起，名称改由 aria-label 与 title 提供，图标按钮仍然可读、可悬停识别。 -->
        <NButton v-for="item in group.items" :key="item.id" attr-type="button" block :data-tab="item.id" :class="{ active: tab === item.id }"
          :type="tab === item.id ? 'primary' : 'default'" :secondary="tab === item.id" :quaternary="tab !== item.id"
          :aria-current="tab === item.id ? 'page' : undefined" :aria-label="collapsed ? item.label : undefined"
          :title="collapsed ? item.label : undefined"
          @pointerenter="prefetchTab(item.id)" @focus="prefetchTab(item.id)" @click="$emit('navigate', item.id)">
          <template #icon><component :is="item.icon" :size="18" :stroke-width="1.7" aria-hidden="true" /></template><span class="nav-label">{{ item.label }}</span>
        </NButton>
      </div>
    </nav>
    <div class="sidebar-bottom">
      <span class="local-indicator">{{ role === 'admin' ? '管理员' : role === 'operator' ? '操作员' : '只读访问' }}</span>
      <div class="sidebar-actions">
        <NButton quaternary class="text-button" :circle="!!collapsed" :size="collapsed ? 'small' : 'medium'" :aria-label="collapsed ? '退出登录' : undefined" :title="collapsed ? '退出登录' : undefined" @click="$emit('logout')"><template #icon><LogOut :size="16" /></template><span class="nav-label">退出登录</span></NButton>
        <NButton quaternary circle size="small" class="sidebar-collapse" :aria-label="collapsed ? '展开导航' : '折叠导航'" :aria-pressed="!!collapsed" :title="collapsed ? '展开导航' : '折叠导航'" @click="$emit('toggleCollapse')">
          <template #icon><ChevronsRight v-if="collapsed" :size="16" /><ChevronsLeft v-else :size="16" /></template>
        </NButton>
      </div>
    </div>
  </aside>
</template>
