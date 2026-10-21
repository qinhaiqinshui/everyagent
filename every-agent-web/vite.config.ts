import { defineConfig, type Plugin } from 'vite'
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

/**
 * CSP 开发态宽松化(M-4 整改配套)。
 *
 * index.html 里的 CSP meta 是随构建发布的那份,script-src 已收紧为 'self' 'blob:'
 * (生产 Vite 产物无内联脚本)。但 Vite dev server + @vitejs/plugin-react 的
 * react-refresh preamble 依赖内联 module 脚本(部分依赖还需 eval),开发态必须放行。
 * 故仅在 dev server(apply: 'serve')下,经 transformIndexHtml 把 CSP meta 的
 * script-src 追加 'unsafe-inline' 'unsafe-eval'——vite build 走该插件时被
 * apply 条件排除,生产输出不受影响。
 */

/** 给 CSP 的 script-src 指令追加 'unsafe-inline' 'unsafe-eval'(已存在则幂等)。其余指令原样保留。 */
export function relaxScriptSrcForDev(csp: string): string {
  return csp.replace(/script-src[^;]*;?/, (directive) => {
    const extra = ["'unsafe-inline'", "'unsafe-eval'"].filter((d) => !directive.includes(d))
    return extra.length ? `${directive.replace(/;$/, '')} ${extra.join(' ')};` : directive
  })
}

/** dev server 专用:CSP meta 宽松化插件。 */
function cspDevRelaxPlugin(): Plugin {
  return {
    name: 'every-agent:csp-dev-relax',
    apply: 'serve',
    transformIndexHtml(html) {
      return html.replace(
        /<meta\b[^>]*http-equiv="Content-Security-Policy"[^>]*>/g,
        (tag) =>
          tag.replace(/content="([^"]*)"/, (_m, csp: string) => `content="${relaxScriptSrcForDev(csp)}"`),
      )
    },
  }
}

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(loadAppVersion()),
  },
  plugins: [
    cspDevRelaxPlugin(),
    react(),
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
      '@every-agent/client': resolve(__dirname, 'src/sdk'),
      '@everyagent/plugin-api': resolve(__dirname, '../every-agent-plugin-api/js/index.ts'),
      '@plugins': resolve(__dirname, '../every-agent-plugins'),
      'antd': resolve(__dirname, 'node_modules/antd'),
    },
  },
  server: {
    port: 5174,
  },
  build: {
    outDir: 'dist',
  },
})
