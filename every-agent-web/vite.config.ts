import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'
import { resolve } from 'path'

/**
 * every-agent-web 构建配置。
 *
 * 与 n 分支原配置的差异:
 * - 去掉 buffer/stream shim(ZenFS/isomorphic-git 已下沉 worker,前端零 Node polyfill);
 * - `@every-agent/client` 别名指向本仓库内置的 hub 客户端 SDK(src/sdk,原 every-agent-client 模块已并入)。
 */

/** 构建时从 package.json 读取版本号,注入 __APP_VERSION__(设置页/关于区展示,单一事实源)。 */
function loadAppVersion(): string {
  const pkg = JSON.parse(readFileSync(resolve(__dirname, 'package.json'), 'utf-8')) as { version?: string }
  return pkg.version ?? '0.0.0'
}

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(loadAppVersion()),
  },
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
