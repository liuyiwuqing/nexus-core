import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [{
    name: 'vueuc-nonce-styles',
    enforce: 'pre',
    resolveId(source, importer) {
      if (source === './cssr' && importer?.replaceAll('\\', '/').endsWith('/vueuc/es/shared/index.js'))
        return new URL('./src/vueucStyles.ts', import.meta.url).pathname;
    },
  }, vue()],
  // 让开发模式也经过同一适配器，避免 Vite 预打包绕过 resolveId。
  optimizeDeps: { exclude: ['vueuc'] },
  build: { outDir: '../target/frontend', emptyOutDir: true },
  server: { proxy: { '/api': 'http://127.0.0.1:8088' } },
});
