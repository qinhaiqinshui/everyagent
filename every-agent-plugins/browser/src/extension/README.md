# Every Agent 浏览器控制 · MV3 扩展

一句话职责：**纯通用命令执行器** —— 连接 Every Agent browser 插件的守护进程（长轮询拉取命令），在用户真实浏览器中执行七种 op 并逐个回传结果；扩展自身零业务逻辑（选择器解析、aria 生成等脚本一律由服务端经 `exec` op 的 `js` 字符串下发，扩展只负责执行）。

## 安装（加载已解压扩展）

1. 打开 Chrome，地址栏输入 `chrome://extensions` 回车。
2. 打开右上角「开发者模式」。
3. 点击左上角「加载已解压的扩展程序」。
4. 选择本目录（`every-agent-plugins/browser/src/extension/`，即含 `manifest.json` 的目录）。

## options 配置

1. 点击工具栏上的扩展图标（或在 `chrome://extensions` 里点「详情 → 扩展程序选项」）打开设置页。
2. 填写「服务地址」与「Token」（见 Every Agent 侧 skill 面板 / 守护进程运行时信息，由 skill 侧提供）。
3. 可先点「测试连接」验证连通性（调用 `POST /ext/hello`），再点「保存」。
4. 保存后 service worker 自动连接并进入长轮询循环，无需任何手动触发。

## 协议速览

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/ext/hello` | POST | 连接注册，body `{token, extVersion, browser}` → `{ok, serverTime}` |
| `/ext/poll` | GET | 长轮询（服务端挂起 ≤25s，客户端 30s 超时），token 走 query → `{cmds:[{opId,type,args}]}` |
| `/ext/result` | POST | 单个 op 结果，body `{token, opId, ok, data\|error}` → `{ok}` |

每收到一批 cmds 按**顺序串行执行**，每个 op 独立 POST result（失败也上报可读 error 字符串）。

## 七种 op 一览

| op | args | 成功 data |
| --- | --- | --- |
| `tabs.list` | `{}` | `{tabs:[{tabId,url,title,active}]}`（全部窗口的全部标签） |
| `tab.create` | `{url,active}` | `{tabId}` |
| `tab.update` | `{tabId,url?,active?}` | `{}` |
| `tab.close` | `{tabId}` | `{}`（标签已不存在等失败 → `ok:false` + error 描述） |
| `tab.waitReady` | `{tabId,timeoutMs}` | `{}`（每 200ms 轮询 `document.readyState`，complete 后再稳定 300ms；超时 `ok:false`） |
| `exec` | `{tabId,js,awaitPromise?,world?}` | `{value}`（`world` 为 `'MAIN'`/`'ISOLATED'`，缺省 ISOLATED；注入 wrapper 内 `eval(js)`，结果 JSON 序列化，`undefined` → `null`；脚本异常时 data 为 `{ok:false,error}`） |
| `screenshot` | `{tabId}` | `{dataUrl}`（先激活标签页等 150ms 再截图，PNG） |

## 常见问题

- **一直未连接 / 没有命令进来？**
  先在 options 页点「测试连接」确认服务地址与 token 正确；再到 `chrome://extensions` 打开本扩展 service worker 的控制台查看日志（`[ea-ext]` 前缀）。未配置时扩展每 5s 重试读配置，不会报错退出。
- **poll 失败怎么办？会无限刷请求吗？**
  不会。网络错误或 HTTP 非 200 时按 1s→2s→5s→10s（封顶）退避重连，任一次成功后退避计数复位；MV3 回收 service worker 后由事件（浏览器启动、扩展安装/更新、配置变更、点击图标等）唤醒自动重新进入主循环。
- **截图只有可视区域？**
  是。`screenshot` 使用 `chrome.tabs.captureVisibleTab`，固有限制为**仅视口**截图；需要整页时可由服务端经 `exec` 下发滚动 + 多次截图拼接的脚本。

## 文件说明

| 文件 | 职责 |
| --- | --- |
| `manifest.json` | MV3 清单：权限（tabs/scripting/storage、`<all_urls>`）、service worker、options 页 |
| `sw.js` | 主循环与七种 op 执行器（纯通用执行器，零业务逻辑） |
| `options.html` / `options.js` | 中文设置页：配置服务地址与 token、测试连接 |
| `README.md` | 本文档 |
