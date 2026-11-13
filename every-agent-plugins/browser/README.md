# browser —— 浏览器操控插件

浏览器操控插件：skill/CLI + MV3 扩展命令通道，AI 操控用户真实浏览器做网页自动化测试。

## 插件形态

本插件是**纯声明式**插件：

- **零 Java**：没有 `pom.xml`、没有入口类，不注册任何 worker 扩展点。
- **零 web**：没有 `web/` 目录、没有 `webMain`，不向前端注册任何 UI。
- **零 worker 核心改动**：不触碰 SkillMaterializer 等宿主代码。
- 唯一职责：通过 `plugin.json` 的 `"skills": "skill"` 字段，把 `skill/` 目录下的技能物化到 worker 系统技能目录。

## 目录结构

```
browser/
├── plugin.json                 # 纯声明式清单（skills: "skill"）
├── build/
│   ├── pack.mjs                # 组装脚本：src 资产 → base64 清单 → 追加到 skill-body.md 尾部
│   └── skill-body.md           # skill.md 正文模板（AI 操作手册）
├── skill/browser-automation/
│   └── skill.md                # 生成产物（含尾部 <<<EA-ASSETS>>> 内嵌资产，需随源码提交）
└── src/
    ├── scripts/                # Node ≥18 零依赖：CLI + 守护进程 + lib + node --test
    └── extension/              # MV3 扩展（通用命令执行器，零业务逻辑）
```

## skill 资产组装与测试

```powershell
# 改动 src/ 或 build/skill-body.md 后重新组装（幂等）：
node build/pack.mjs

# 服务端单测（selectors/yaml/scripts/commands/daemon 协议层，零浏览器依赖，28 用例）：
cd src/scripts
node --test
```

skill.md 机制：物化机制只搬运 `skill.md` 单文件，故 CLI 脚本与扩展源码以 base64 资产清单内嵌在 skill.md 尾部（`<<<EA-ASSETS … EA-ASSETS>>>`），AI 首次使用时按 skill「首次安装」节用 PowerShell 解码落盘到 skill 目录——自包含分发，无需改动物化核心。

## 生效方式

- 改动 `plugin.json` 或 `skill/` 下任何文件后，**重启 worker** 生效（插件无热重载）；重启时 worker 把 `skill/` 物化到系统技能目录。
- builtin 形态要求 worker 的 cwd 在仓库根（内置扫描按 `user.dir` 找 `every-agent-plugins/`）。
- 验证是否加载：worker 日志出现「插件已激活: id=browser」或「插件 browser 物化技能包: [browser-automation]」，或前端调用 `plugin.list` RPC、`/` 菜单出现「浏览器操控」。

## 使用（用户侧，一次性）

1. 让 AI 执行 skill「首次安装」第 ① 步（资产落盘到 skill 目录）。
2. `chrome://extensions` → 开发者模式 → 加载已解压的扩展程序 → 选 `<skill目录>/extension`。
3. 扩展 options 页填服务地址与 token（见 `~/.everyagent/browser-automation/runtime.json`，首次执行任意 CLI 命令后生成）。

详见 `skill/browser-automation/skill.md`「首次安装（必读，一次性）」。
