---
name: Skill 创建器
description: 当用户要求创建一个新的 skill 时，按以下规范操作。
---

# Skill 创建器

当用户要求创建一个新的 skill 时，按以下规范操作。

## skill 目录结构

系统技能目录下，一个 skill = 一个目录：

```
<skillsDir>/<skill-id>/
  skill.md           # 必须存在；首部为 frontmatter 元数据块，其后为方法论正文
  scripts/           # 可选，存放可执行脚本
    run.sh            #   脚本不限语言：bash / python / node 等
```

`<skillsDir>` 是 EveryAgent 的系统技能目录（通常是 `~/.everyagent/skills/`）。该目录对你（EveryAgent）读写开放，可直接在其中创建文件。

**落盘即生效**：`<skillsDir>/<id>/skill.md` 一经存在，该 skill 即自动进入 `/` 菜单，并默认进入每轮 system prompt 的技能索引——不存在任何「注册」步骤（无代码注册通道、无配置开关）。是否自动进 system prompt 由用户在设置页按工作区配置（默认全部工作区自动暴露），与 skill.md 内容无关。

## 创建步骤

1. 确认用户想要的 skill 功能和名称（id 只能包含小写字母、数字和连字符，首字符必须是字母或数字：`[a-z0-9][a-z0-9-]*`）
2. 在系统技能目录下创建 `<id>/` 目录，写入全部文件：
   - `skill.md`（必须存在，首部带 frontmatter，见下）
   - 脚本文件（如 `scripts/run.sh`，可选，任意语言，加 shebang 如 `#!/usr/bin/env bash`、`#!/usr/bin/env python3` 等）
3. 告知用户在设置页点击「重新读取 Skill 列表」热加载后即可在 `/` 菜单中看到并使用新 skill

## skill.md 写作规范

文件首部是 `---` 定界的 frontmatter 元数据块，**只有两个键**：

```
---
name: 计划模式
description: 当任务复杂需拆步骤执行，或用户要求做计划时使用本技能。
---
```

- `name`：人读标题，展示在 `/` 菜单与 system prompt 技能索引（如 id 为 `plan`、name 为「计划模式」——id 是英文标识，name 是人读标题，两者不同是常态）
- `description`：一句话「何时用」——什么场景下该用这个技能；单行，超过 200 字符会被截断展示；同样用于 `/` 菜单副标题与 system prompt 索引行
- **没有其他键**：不存在「是否暴露 / 是否启用」之类的控制字段——暴露控制是 worker 侧按工作区的用户配置，skill.md 不含也无法声明它；frontmatter 只提供展示元数据
- frontmatter 之后是方法论正文，应包含：适用条件、操作步骤、注意事项——AI 选中该 skill 后按需用 read_file 读取正文执行
- 如有脚本，在正文中注明相对路径和调用方式（如 `bash scripts/run.sh`、`python3 scripts/run.py`、`node scripts/run.js`）；脚本放在 skill 目录下任意位置（如 `scripts/`）

## 规则

1. skill id（目录名）只能包含小写字母、数字和连字符，首字符必须是字母或数字：`[a-z0-9][a-z0-9-]*`
2. 目录下必须有 `skill.md` 文件，首部 frontmatter 只写 `name` 与 `description` 两个键
3. `name` 用作展示标题（缺省回落目录名）；`description` 用作一句话描述（缺省回落正文首个非 `#` 行、截断 200 字符——新写的 skill 不应依赖回落，务必显式写 frontmatter）
4. `skill.md` 正文是方法论（AI 执行依据），选中该 skill 后由 AI 按需用 read_file 工具读取
5. 可执行脚本放在目录下任意位置（如 `scripts/`），在 skill.md 正文中以相对路径引用，由 AI 用对应解释器执行——不限于 bash，也可以是 Python、Node 等任意语言
6. 新 skill 落盘后即自动进 `/` 菜单与 system prompt（默认），无需任何注册；设置页「重新读取 Skill 列表」可立即热加载（不必重启）
