<script setup lang="ts">
import { LogOut } from '@lucide/vue';
import { NButton } from 'naive-ui';
import { computed } from 'vue';
import type { Role } from './access';
import type { Tab } from './types';
import { visibleNavigation } from './navigation';
import { prefetchTab } from './panels';
const props = defineProps<{ tab: Tab; role: Role }>();
defineEmits<{ navigate: [tab: Tab]; logout: [] }>();
const groups = computed(() => visibleNavigation(props.role));
</script>

<template>
  <aside class="sidebar">
    <div class="brand"><span class="pet-symbol" aria-hidden="true"><i></i><i></i></span><span>NEXUS<span class="brand-sub">桌面伙伴管理台</span></span></div>
    <nav aria-label="主导航">
      <div v-for="group in groups" :key="group.label" class="nav-group">
        <p class="nav-group-label">{{ group.label }}</p>
        <!-- 指针/键盘落到导航项即预取对应 chunk，点击时通常已在缓存里，切换不出现空窗。 -->
        <button v-for="item in group.items" :key="item.id" type="button" :data-tab="item.id"
          :class="{ active: tab === item.id }" :aria-current="tab === item.id ? 'page' : undefined"
          @pointerenter="prefetchTab(item.id)" @focus="prefetchTab(item.id)" @click="$emit('navigate', item.id)">
          <component :is="item.icon" :size="18" :stroke-width="1.7" aria-hidden="true" /><span>{{ item.label }}</span>
        </button>
      </div>
    </nav>
    <div class="sidebar-bottom"><span class="local-indicator">{{ role === 'admin' ? '管理员' : role === 'operator' ? '操作员' : '只读访问' }}</span><NButton quaternary class="text-button" @click="$emit('logout')"><template #icon><LogOut :size="16" /></template>退出登录</NButton></div>
  </aside>
</template>
