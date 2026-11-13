---
name: 浏览器操控
description: 需要浏览网页、点击、填表、读取页面内容、截图或对网页做自动化测试时使用本技能（经浏览器扩展通道操控用户真实浏览器）。
---

# 浏览器操控（browser-automation）

## 适用条件

- 当需要打开网页、点击元素、填写表单、获取页面结构/内容、截图时使用。
- 当需要对网页做自动化测试（导航 → 操作 → 断言 → 收集报错）时使用。
- 需要用户已登录的站点：直接在用户真实浏览器里操作，登录态天然存在；未登录时请用户在浏览器窗口手动登录后继续。

## 重要特性说明

1. **操控用户真实浏览器**：命令经「CLI → 本机守护进程 → 浏览器扩展(MV3)」链路下发，打开的是用户浏览器里真实可见的标签页（多窗口可接受）。登录态、Cookie 与用户日常浏览器一致。
2. **一次性安装**：首次使用需完成「资产落盘 + 安装浏览器扩展」两步（见下节）；此后即开即用。skill 资产更新（版本变化）时重跑落盘命令即可自愈。
3. **单守护进程 + 多标签**：守护进程常驻后台（首次执行任意命令自动拉起）；每次 `open` 创建一个新标签页并返回 `tabId`（Chrome 数字 tabId，跨导航稳定）。所有后续命令必须 `--tab <tabId>` 指定标签页。多个任务可各自 open 互不干扰。
4. **自动关闭**：守护进程 120 秒无命令自动退出（每次执行命令刷新倒计时；`open --timeout 600` 可延长，适合等用户手动登录）。退出前只关闭本会话打开的标签页。
5. **安全边界**：`close --tab` 只能关本会话 `open` 打开的标签页；`close`（不带 --tab）只关本会话全部标签并退出守护。用户自己的标签页永远不会被触碰。
6. **截图仅视口**：截图走浏览器可见区截图（截图前会自动激活目标标签）；如需整页内容用 `aria`/`query` 取文本。

## 首次安装（必读，一次性）

**第 ① 步：资产落盘**（把内嵌在本文件尾部的脚本/扩展解码写入本 skill 目录）。
先把下面第一行 `$skillDir` 的值改成**本 skill 目录的绝对路径**（即本 skill.md 所在目录），然后整段执行：

```powershell
$skillDir = "C:\Users\<你>\.everyagent\skills\browser-automation"   # ← 改成本 skill 目录绝对路径
$content = Get-Content -Raw (Join-Path $skillDir 'skill.md')
if (-not ($content -match '(?s)<<<EA-ASSETS\s*\r?\n(.*?)\r?\nEA-ASSETS>>>')) { throw '未找到资产清单(<<<EA-ASSETS 块)' }
$manifest = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1].Trim())) | ConvertFrom-Json
foreach ($f in $manifest.files) {
  $dest = Join-Path $skillDir ($f.path -replace '/', '\')
  New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
  [IO.File]::WriteAllBytes($dest, [Convert]::FromBase64String($f.b64))
}
Set-Content (Join-Path $skillDir 'scripts\.ea-assets-version') $manifest.version
"已安装 $($manifest.files.Count) 个资产文件,版本 $($manifest.version)"
```

自检：`node "$skillDir\scripts\browser.js" --help` 能打印用法即成功（Node ≥ 18，否则先装 Node）。

**第 ② 步：安装浏览器扩展并连通**：

```powershell
# 1) 激活守护进程并拿到服务地址与 token(预期报「扩展未连接」,属正常——守护已启动):
node "$skillDir\scripts\browser.js" tabs
# 2) 读运行时信息(port/token):
Get-Content "$env:USERPROFILE\.everyagent\browser-automation\runtime.json"
```

然后：在 Chrome/Edge 打开 `chrome://extensions` → 开启「开发者模式」→「加载已解压的扩展程序」→ 选择目录 `"$skillDir\extension"` → 点扩展图标打开设置页 → 服务地址填 `http://127.0.0.1:<runtime.json 里的 port>`、token 填 runtime.json 里的 token → 保存并点「测试连接」显示成功。

最后验证：`node "$skillDir\scripts\browser.js" tabs` 应返回 `success: true` 与标签列表。之后即可正常使用（守护进程闲置 120s 自动退出，下次执行命令会自动拉起，token/port 不变，无需重新配置）。

## 脚本路径与调用方式

- CLI：`node "<skill目录>\scripts\browser.js" <command> [args] [--options]`（任意工作目录，绝对路径调用）。
- 输出为 **YAML 信封**：`success: true/false`、`message: "..."`、`data:`（成功 exit 0，失败 exit 1）。
- `aria` 命令例外：未超阈值时输出**双 YAML 文档**（`---` 之前是元信息，之后是结构 DSL 原文）。

## 子命令列表

| 命令 | 用法 | 说明 |
|---|---|---|
| `open` | `open <url> [--timeout <秒>]` | 打开新标签页导航到 url，返回 `tabId`。`--timeout` 覆盖守护闲置自动退出时长（默认 120 秒）。 |
| `goto` | `goto <url> --tab <tabId>` | 在指定标签页导航到新 url。 |
| `back` / `forward` | `back --tab <tabId>` | 指定标签页后退/前进。 |
| `click` | `click <selector> --tab <tabId>` | 点击元素；等待元素出现最多 10 秒。 |
| `fill` | `fill <selector> <text> --tab <tabId>` | 在输入框填入文本（input/textarea/contenteditable）。 |
| `query` | `query <selector> --tab <tabId> [--limit <n>] [--full]` | 查询匹配元素，返回 tag/text/href/value/name/id/type/placeholder/role/ariaLabel。默认最多 20 个、text 截断 200 字；`--full` 返回完整文本（读正文用）。 |
| `aria` | `aria --tab <tabId> [--save <path>]` | **首选信息获取命令**。页面结构 DSL（`<banner>`、`<a url="...">新闻</a>`、`<input>搜索词</input>`，层级缩进，同 URL 链接去重）。超阈值（>300 行/15000 字符）不返回正文，只返回统计与 `fullFile` 路径——此时用 `query` 精确钻取或 `read_file` 读 fullFile。`--save <path>` 直写指定文件。 |
| `screenshot` | `screenshot <path> --tab <tabId>` | 截图保存到指定路径（仅视口；自动激活目标标签）。 |
| `tabs` | `tabs` | 列出全部标签页（tabId/url/title），并标注 `sessionTabIds`（本会话 open 的）。 |
| `close` | `close --tab <tabId>` / `close` | 关指定标签（仅会话内）；不带 `--tab` 关闭本会话全部标签并退出守护进程。 |

## 操作步骤（AI 执行流程）

1. `open <url>` 打开标签页，**记住返回的 `tabId`**（等用户手动登录时加 `--timeout 600`）。
2. **首选 `aria --tab <tabId>`** 获取页面结构（导航/按钮/输入框/链接层级）；超阈值时用 `query <selector>` 精确钻取或读 `fullFile`。
3. 需要元素具体内容或精确 selector 时用 `query`（返回真实 DOM 的 id/name/href）；读长正文加 `--full`。
4. `click` / `fill` 执行交互；selector 支持 CSS 选择器与文本选择器 `text=xxx`（如 `click "text=登录" --tab <tabId>`）。
5. 点击后用 `aria`（或 `screenshot`）确认页面状态变化——自动化测试的断言即：操作后 `aria`/`query`/`screenshot` 比对预期，配 `query` 检查页面文本。
6. 点击链接打开了新标签页：`tabs` 找到新 tabId（不在 sessionTabIds 里的新标签），后续用它操作（注意：非 open 打开的标签不能 close）。
7. 任务完成后 `close --tab <tabId>` 收尾；全部结束 `close` 退出守护。

信息获取决策：**结构/交互 → aria；元素内容/selector → query；视觉 → screenshot**。

## 自动化测试模式（导航-操作-断言-收集）

对每个用例：`open` 起始页 → `aria` 取基线 → `fill`/`click` 操作 → `aria`/`query` 断言（元素存在/文本匹配/URL 变化）→ `screenshot` 留证 → 最后汇总每用例通过/失败与页面报错。发现页面 JS 报错时可 `query "body" --full` 检查错误文案节点（或对可疑元素截图）。全部用例结束 `close` 收尾。

## 注意事项

- 必须先 `open` 再执行其他命令；除 `open`/`tabs`/`close` 外所有命令都必须带 `--tab <tabId>`。
- 忘记 tabId 时用 `tabs` 查看；`sessionTabIds` 标出本会话可关闭的标签。
- **扩展未连接**（报「扩展未连接,请按 skill 安装指引安装并配置浏览器扩展」）：按「首次安装」第 ② 步检查扩展加载与 options 配置（服务地址端口/token 以 runtime.json 为准）。
- 资产自检/自愈：`node "<skill目录>\scripts\browser.js" --help` 报找不到文件或版本不符（skill.md 版本与 `scripts\.ea-assets-version` 不一致）时，重跑「首次安装」第 ① 步落盘命令。
- 需要 Node ≥ 18（`node --version` 自检）。
- 需登录的站点：告知用户浏览器已打开，请手动登录后继续（记得 `open --timeout 600`）。
- `close` 的安全边界：只能关闭本会话 `open` 打开的标签；用户自己的标签页不受影响。
