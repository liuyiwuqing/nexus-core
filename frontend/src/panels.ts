import { defineAsyncComponent, type Component } from 'vue';
import PanelSkeleton from './PanelSkeleton.vue';
import type { Tab } from './types';

// 面板按需加载：首屏只打包外壳与默认页，翻页时才取对应 chunk。
// 加载器集中在这里，导航侧的"意图预取"与页面侧的 defineAsyncComponent 共用同一份 import()，
// 保证同一个模块只会被请求一次（打包器按模块 id 去重）。
const loaders = {
  overview: () => import('./OverviewPanel.vue'),
  devices: () => import('./DevicesPanel.vue'),
  pet: () => import('./PetPanel.vue'),
  connectors: () => import('./ConnectorsPanel.vue'),
  pull: () => import('./PullPanel.vue'),
  sources: () => import('./SourcesPanel.vue'),
  deliveries: () => import('./DeliveryPanel.vue'),
  routing: () => import('./RoutingPanel.vue'),
  templates: () => import('./TemplatesPanel.vue'),
  automation: () => import('./AutomationPanel.vue'),
  memory: () => import('./MemoryPanel.vue'),
  tools: () => import('./ToolsPanel.vue'),
  access: () => import('./AccessPanel.vue'),
  guide: () => import('./SetupGuide.vue'),
} satisfies Record<string, () => Promise<unknown>>;

export type PanelName = keyof typeof loaders;

// 每个标签页要渲染的面板；inbox 是默认页，随外壳同步加载，不在此列。
const tabPanels: Record<Tab, readonly PanelName[]> = {
  inbox: [],
  overview: ['overview', 'guide'],
  devices: ['devices'],
  pet: ['pet'],
  connectors: ['connectors', 'pull', 'sources'],
  deliveries: ['deliveries'],
  routing: ['routing'],
  templates: ['templates'],
  automation: ['automation'],
  memory: ['memory'],
  tools: ['tools'],
  access: ['access'],
};

async function load(name: PanelName): Promise<Component> {
  const module = await loaders[name]();
  return (module as { default: Component }).default;
}

export function prefetch(...names: PanelName[]): void {
  for (const name of names) void loaders[name]().catch(() => {});
}

export function prefetchTab(tab: Tab): void {
  prefetch(...tabPanels[tab]);
}

export function asyncPanel(name: PanelName): Component {
  // delay 让局域网内的瞬时加载不闪骨架；超时给出明确的失败态而不是永久空白。
  return defineAsyncComponent({ loader: () => load(name), loadingComponent: PanelSkeleton, delay: 200, timeout: 20000 });
}
