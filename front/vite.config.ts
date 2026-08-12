import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: fileURLToPath(new URL('../backend/src/main/resources/static', import.meta.url)),
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8084', changeOrigin: true },
      '/wecom': { target: 'http://localhost:8084', changeOrigin: true },
    },
  },
})
