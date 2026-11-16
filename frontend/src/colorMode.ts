import { computed, ref, watch } from 'vue';

/**
 * 明暗主题只有一处真相：<html data-theme>。CSS token 与 Naive 覆盖都由它驱动，
 * 组件不自行判断深浅，也不再从 CSS 反推模式。
 * 首次进入跟随系统；用户手动切换后写入 localStorage，此后以本地选择为准。
 */
export type ColorMode = 'light' | 'dark' | 'system';
const STORAGE_KEY = 'nexus-color-mode';
const prefersDark = window.matchMedia('(prefers-color-scheme: dark)');

function readMode(): ColorMode {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'light' || saved === 'dark' || saved === 'system') return saved;
  } catch {
    /* 隐私模式禁用 localStorage 时按系统偏好，不阻塞渲染。 */
  }
  return 'system';
}

export const colorMode = ref<ColorMode>(readMode());
const systemDark = ref(prefersDark.matches);
prefersDark.addEventListener('change', event => { systemDark.value = event.matches; });

export const isDark = computed(() => colorMode.value === 'dark' || (colorMode.value === 'system' && systemDark.value));

function applyMode(dark: boolean) {
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  /* 让原生控件（日期、下拉、滚动条）跟着换色，否则深色页里会出现白色日历。 */
  document.querySelector<HTMLMetaElement>('meta[name="color-scheme"]')?.setAttribute('content', dark ? 'dark light' : 'light dark');
}

watch(isDark, applyMode, { immediate: true });

export function toggleColorMode() {
  const next: ColorMode = isDark.value ? 'light' : 'dark';
  colorMode.value = next;
  try { localStorage.setItem(STORAGE_KEY, next); } catch { /* 忽略存储失败，当前会话仍然生效 */ }
}
