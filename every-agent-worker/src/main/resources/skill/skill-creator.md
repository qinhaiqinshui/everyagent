# Skill 创建器

当用户要求创建一个新的 skill 时，按以下规范操作。

## skill 目录结构

系统技能目录下，一个 skill = 一个目录：

```
<skillsDir>/<skill-id>/
  skill.md           # 必须存在
  scripts/           # 可选，存放可执行脚本
    run.sh            #   脚本不限语言：bash / python / node 等
```

`<skillsDir>` 是 worker 的系统技能目录（通常是 `~/.everyagent/skills/`）。**注意：该目录对 AI 只读，AI 无法直接在其中创建文件。**

## 关键约束

- 系统技能目录对 AI 工具只读（沙箱只读挂载 + PermissionGate 写拦截），**不能直接用 `fs.write`、`fs.mkdir` 或 `bash` 在该目录下创建文件**
- AI 应先在**当前工作区**内创建 skill 的全部文件，再告知用户将目录移动到系统技能目录

## 创建步骤

1. 确认用户想要的 skill 功能和名称（id 只能包含小写字母、数字和连字符，首字符必须是字母或数字：`[a-z0-9][a-z0-9-]*`）
2. 在当前工作区内创建临时目录（如 `.everyagent/<skill-id>/`），把 skill 的全部文件写入其中：
   - `skill.md`（必须存在）
   - 脚本文件（如 `scripts/run.sh`，可选，任意语言）
3. 告知用户：
   - 将 `<工作区>/.everyagent/<skill-id>/` 整个目录移动到系统技能目录 `<skillsDir>/` 下
   - 系统技能目录路径：`~/.everyagent/skills/`（或自定义的 `skillsDir` 配置值）
   - 移动完成后在设置页点击「重新读取 Skill 列表」热加载
   - 之后即可在 `/` 菜单中看到新 skill

## skill.md 写作规范

- 第一行应是 `# <标题>`（标题行，不作为描述）
- 紧接着的空行之后的第一行正文 = 描述（进 `/` 菜单副标题，截断至 200 字符）
- 方法论正文应包含：适用条件、操作步骤、注意事项
- 如有脚本，在正文中注明相对路径和调用方式（如 `bash scripts/run.sh`、`python3 scripts/run.py`、`node scripts/run.js`）
- 脚本放在 skill 目录下任意位置（如 `scripts/`），由 skill.md 正文以相对路径引用

## 规则

1. skill id（目录名）只能包含小写字母、数字和连字符，首字符必须是字母或数字：`[a-z0-9][a-z0-9-]*`
2. 目录下必须有 `skill.md` 文件
3. `skill.md` 的第一个非空且非 `#` 标题行的正文行会作为 skill 的描述（显示在 `/` 菜单中），截断至 200 字符
4. `skill.md` 其余内容是方法论正文，AI 被选中该 skill 后按需 read_file 读取
5. 可执行脚本放在目录下任意位置（如 `scripts/`），在 skill.md 正文中以相对路径引用，由 AI 用对应解释器执行——不限于 bash，也可以是 Python、Node 等任意语言
6. 新 skill 创建后需在设置页点击「重新读取 Skill 列表」热加载，无需重启 worker
