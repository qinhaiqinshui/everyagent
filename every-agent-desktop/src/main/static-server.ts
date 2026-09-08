/**
 * 本地静态服务:在 127.0.0.1 随机端口服务 resources/web 构建产物。
 * 前端通过 http://127.0.0.1:<port>/ 访问(安全上下文,WebCrypto 可用;file:// 会降级)。
 * SPA fallback:非文件路径一律回 index.html。
 */
import { createServer, type Server } from 'node:http'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { extname, join, normalize } from 'node:path'

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.wasm': 'application/wasm',
  '.map': 'application/json',
}

export interface StaticServer {
  port: number
  url: string
  close: () => void
}

export function startStaticServer(root: string): Promise<StaticServer> {
  const server: Server = createServer((req, res) => {
    const urlPath = (req.url ?? '/').split('?')[0] ?? '/'
    let filePath = normalize(join(root, urlPath === '/' ? 'index.html' : urlPath))
    // 防目录穿越:确认仍在 root 内。
    const rootNorm = normalize(root)
    if (!filePath.startsWith(rootNorm)) filePath = join(rootNorm, 'index.html')

    if (!existsSync(filePath) || statSync(filePath).isDirectory()) {
      filePath = join(rootNorm, 'index.html')
    }

    try {
      const data = readFileSync(filePath)
      res.writeHead(200, { 'Content-Type': MIME[extname(filePath)] ?? 'application/octet-stream' })
      res.end(data)
    } catch {
      res.writeHead(404)
      res.end('Not Found')
    }
  })

  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      if (!address || typeof address === 'string') {
        server.close()
        reject(new Error('无法获取静态服务地址'))
        return
      }
      const port = address.port
      resolve({
        port,
        url: `http://127.0.0.1:${port}/`,
        close: () => server.close(),
      })
    })
  })
}