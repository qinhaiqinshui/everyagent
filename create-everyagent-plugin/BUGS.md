# create-everyagent-plugin 已知 bug 待办

> 本文件记录脚手架待修复 bug,供后续修复任务直接取用;修复完成后把对应条目移除。

## BUG-1: 位置参数在 builtin 模式下被解析为显式输出目录,生成出「构建必坏」的工程且零警告

- **发现时间**: 2026-12(ripgrep-tool 插件化任务,步骤 1 派发实测)
- **严重度**: 高(生成产物静默不可构建,违背「生成可直接构建的插件工程」承诺)

### 现象
在仓库根执行(README「快速开始」的自然写法):
```
node create-everyagent-plugin ripgrep-tool --kind java --mode builtin --yes
```
命令**成功**但生成到**仓库根** `./ripgrep-tool/`(而非 builtin 应落的 `every-agent-plugins/ripgrep-tool/`)。该位置下 pom 的 `relativePath ../../every-agent-plugin-parent/pom.xml` 向上出仓库,`mvn compile` 必失败。全程无任何警告。

正确等价命令是 `node create-everyagent-plugin --id ripgrep-tool --kind java --mode builtin --yes`(不传位置参数,builtin 自动选址)。

### 根因
- CLI 位置参数语义是 `[dir]`(显式输出目录,「给了就用它,目录名会当作 --id 缺省值」),与常见脚手架(create-react-app/create-vite)「位置参数=项目名/id」的直觉相反。
- 用户以 id 心智传位置参数时,被当成 cwd 相对目录,完全绕过 builtin 的 `every-agent-plugins/<id>/` 自动选址推导。
- README 自己的「快速开始」示例 `node create-everyagent-plugin my-tool --kind full --yes` 就是这种坑写法(仓库内 node 直跑、cwd=仓库根时按示例执行即触发)。
- 生成前无「目标目录不在 every-agent-plugins/ 下 + mode=builtin」的防呆校验,生成后无警告。

### 建议修复方向(任选或组合,修复任务自行决策)
1. **防呆校验(最小改动)**: mode=builtin 且目标目录不含在 `every-agent-plugins/` 下时,拒绝生成并提示正确命令(`--id` 形态),或交互确认后放行并在结尾打印构建修正指引。
2. **语义收敛**: 位置参数改为「项目名/id」语义(create-react-app 式),显式目录改走 `--dir` flag;builtin 模式下 id 恒自动选址。需同步改 README/文档,属破坏性变更。
3. **文档止损**: README 快速开始/用法示例全部改为 `--id` 形态,并在位置参数文档处加显著警示。

### 验证要点(修复后)
- `node create-everyagent-plugin <name> --kind java --mode builtin --yes` 在仓库根执行:按方案 1 应被拦截/警告,按方案 2 应正确落到 `every-agent-plugins/<name>/`。
- 落点在 `every-agent-plugins/` 下的 builtin 工程 `mvn -f <plugin>/pom.xml compile` 通过。
- 显式 `--dir` / 显式目录用法不被破坏(standalone 模式回归)。
- CLI 参数解析单测覆盖「位置参数 × mode 组合」矩阵。
