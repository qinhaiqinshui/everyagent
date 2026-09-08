import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

/**
 * every-agent-web 构建配置。
 *
 * 与 n 分支原配置的差异:
 * - 去掉 buffer/stream shim(ZenFS/isomorphic-git 已下沉 worker,前端零 Node polyfill);
 * - `@every-agent/client` 别名指向本仓库内置的 hub 客户端 SDK(src/sdk,原 every-agent-client 模块已并入)。
 */
export default defineConfig({
  plugins: [
    react(),
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
      '@every-agent/client': resolve(__dirname, 'src/sdk'),
    },
  },
  server: {
    port: 5174,
  },
  build: {
    outDir: 'dist',
  },
})
