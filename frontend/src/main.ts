import { createApp } from 'vue';
import { NConfigProvider, zhCN, dateZhCN } from 'naive-ui';
import { h } from 'vue';
import App from './App.vue';
import { consoleTheme, styleMountTarget } from './theme';
import './style.css';

createApp({ render: () => h(NConfigProvider, { themeOverrides: consoleTheme, locale: zhCN, dateLocale: dateZhCN,
  inlineThemeDisabled: true, styleMountTarget: styleMountTarget() }, { default: () => h(App) }) }).mount('#app');
