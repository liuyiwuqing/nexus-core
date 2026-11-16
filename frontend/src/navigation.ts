import type { Component } from 'vue';
import type { Tab } from './types';
import { BookOpen, Cable, FileText, Inbox, LayoutDashboard, Monitor, Route, Send, ShieldCheck, Sparkles, Wrench, Workflow } from '@lucide/vue';
import type { Role } from './access';

export interface NavigationItem { id: Tab; label: string; icon: Component; admin?: boolean }
export const navigation: { label: string; items: NavigationItem[] }[] = [
  { label: '日常', items: [
    { id: 'overview', label: '概览', icon: LayoutDashboard },
    { id: 'inbox', label: '消息', icon: Inbox },
    { id: 'devices', label: '设备', icon: Monitor },
    { id: 'pet', label: '成长', icon: Sparkles },
  ] },
  { label: '消息与自动化', items: [
    { id: 'connectors', label: '接入', icon: Cable },
    { id: 'deliveries', label: '投递', icon: Send },
    { id: 'routing', label: '路由', icon: Route },
    { id: 'templates', label: '模板', icon: FileText },
    { id: 'automation', label: '自动化', icon: Workflow },
  ] },
  { label: '系统', items: [
    { id: 'memory', label: '记忆', icon: BookOpen },
    { id: 'tools', label: '工具', icon: Wrench, admin: true },
    { id: 'access', label: '权限', icon: ShieldCheck, admin: true },
  ] },
];

export function visibleNavigation(role: Role) {
  return navigation.map(group => ({ ...group, items: group.items.filter(item => !('admin' in item) || role === 'admin') }));
}

export function navigationTitle(tab: Tab) {
  return navigation.flatMap(group => group.items).find(item => item.id === tab)?.label ?? '消息';
}
