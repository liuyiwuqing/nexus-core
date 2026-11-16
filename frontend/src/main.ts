import { createApp } from 'vue';
import { NConfigProvider, darkTheme, zhCN, dateZhCN } from 'naive-ui';
import { h } from 'vue';
import App from './App.vue';
import { darkConsoleTheme, lightConsoleTheme, styleMountTarget } from './theme';
import { isDark } from './colorMode';
import './style.css';

// 主题在两套覆盖之间切换：Naive 的基础深色由 darkTheme 提供，品牌色与控件描边仍由这里的覆盖决定。
const mountTarget = styleMountTarget();
createApp({
  setup() {
    return () => h(NConfigProvider, {
      theme: isDark.value ? darkTheme : null,
      themeOverrides: isDark.value ? darkConsoleTheme : lightConsoleTheme,
      locale: zhCN, dateLocale: dateZhCN, inlineThemeDisabled: true, styleMountTarget: mountTarget,
    }, { default: () => h(App) });
  },
}).mount('#app');
