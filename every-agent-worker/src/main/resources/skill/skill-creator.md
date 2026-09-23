# Skill 创建器

当用户要求创建一个新的 skill 时，按以下规范操作。

## skill 目录结构

系统技能目录下，一个 skill = 一个目录：

```
<skillsDir>/<skill-id>/
  skill.md           # 必须存在
  scripts/           # 可选，存放可执行脚本
    run.sh
```

`<skillsDir>` 即本 skill 所在目录的父目录（本 skill 的 skill.md 路径去掉末尾 `skill-creator/skill.md` 即得）。

## 规则

1. skill id（目录名）只能包含小写字母、数字和连字符，首字符必须是字母或数字：`[a-z0-9][a-z0-9-]*`
2. 目录下必须有 `skill.md` 文件
3. `skill.md` 的第一个非空且非 `#` 标题行的正文行会作为 skill 的描述（显示在 `/` 菜单中），截断至 200 字符
4. `skill.md` 其余内容是方法论正文，AI 被选中该 skill 后按需 read_file 读取
5. 可执行脚本放在目录下任意位置（如 `scripts/`），在 skill.md 正文中以相对路径引用，AI 用 `bash` 执行
6. 重启 worker 后新 skill 生效（无热加载）

## 创建步骤

1. 确认用户想要的 skill 功能和名称
2. 在系统技能目录下创建 `<id>/` 目录
3. 写入 `skill.md`：首行写描述，其后写完整方法论
4. 如需脚本，创建脚本文件并确保以 `#!/usr/bin/env bash` 开头
5. 告知用户重启 worker 后在 `/` 菜单中可见

## skill.md 写作规范

- 第一行应是 `# <标题>`（标题行，不作为描述）
- 紧接着的空行之后的第一行正文 = 描述（进 `/` 菜单副标题）
- 方法论正文应包含：适用条件、操作步骤、注意事项
- 如有脚本，在正文中注明相对路径和调用方式（如 `bash scripts/run.sh`）
