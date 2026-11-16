import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  base: '/sqlguard/',
  plugins: [vue(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    proxy: {
      '/sqlguard/api': process.env.SQLGUARD_API_ORIGIN || 'http://127.0.0.1:8080',
    },
  },
})
