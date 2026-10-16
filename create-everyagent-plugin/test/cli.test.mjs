/**
 * test/cli.test.mjs —— 输出目录定位、builtin 落点自检与端到端矩阵（node:test 内置运行器，零依赖）。
 *
 * 覆盖 BUG-1 的验证要点：
 *   - builtin + 位置参数（仓库根下）→ 拒绝生成并打印 --id 形态的正确命令（退出码 2）；
 *   - builtin + --id → 落到 every-agent-plugins/<id>/，且 pom 的 relativePath 指得回 SDK parent；
 *   - 显式目录用法不回归：写在 every-agent-plugins/ 下照常生成，standalone 任意位置照常生成；
 *   - --dry-run 只警告不拦（不写盘）；交互问答下当场确认是否改用仓库内默认位置。
 *
 * 全程在临时仓库快照里跑（自建 every-agent-plugins/ 与 every-agent-plugin-parent/pom.xml），
 * 不碰真实仓库。临时根可用 EA_TMP_BASE 覆盖（受限沙箱里指向工作区内的可写目录）。
 *
 * 跑法：node --test（或 npm test），在 create-everyagent-plugin/ 下执行。
 */

import { strict as assert } from 'node:assert'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { Readable } from 'node:stream'
import { after, test } from 'node:test'
import { main } from '../index.mjs'
import { CanceledError, ValidationError } from '../lib/errors.mjs'
import { checkBuiltinPlacement, resolveTarget } from '../lib/validate.mjs'

const TMP_BASE = process.env.EA_TMP_BASE || os.tmpdir()
const trash = []

/** 造一个「仓库根」快照：every-agent-plugins/ + every-agent-plugin-parent/pom.xml + 根 pom。 */
function makeRepo({ withParent = true } = {}) {
  fs.mkdirSync(TMP_BASE, { recursive: true })
  const root = fs.mkdtempSync(path.join(TMP_BASE, 'ea-cli-'))
  trash.push(root)
  fs.mkdirSync(path.join(root, 'every-agent-plugins'))
  fs.writeFileSync(
    path.join(root, 'pom.xml'),
    '<project><properties><revision>1.1.0</revision></properties></project>\n',
  )
  if (withParent) {
    fs.mkdirSync(path.join(root, 'every-agent-plugin-parent'))
    fs.writeFileSync(path.join(root, 'every-agent-plugin-parent', 'pom.xml'), '<project/>\n')
  }
  return root
}

/** 造一个「别处」目录（不在仓库根下：其祖先链上有 every-agent-plugins 也算，只要它不是仓库根）。 */
function makeElsewhereDir() {
  fs.mkdirSync(TMP_BASE, { recursive: true })
  const root = fs.mkdtempSync(path.join(TMP_BASE, 'ea-elsewhere-'))
  trash.push(root)
  return root
}

/** 一定不在任何仓库里的探针路径：所在盘的根目录下的不存在目录（findRepoRoot 只 stat 祖先，不要求存在）。 */
function noRepoProbePath() {
  return path.join(path.parse(TMP_BASE).root, '__ea_no_repo_probe__')
}

/**
 * 跑一次 CLI（捕获 stdout/stderr，cwd 临时切到 cwd）。
 * timeoutMs 兜住「等待交互输入」这类不返回的路径：超时即判失败，不让测试挂死。
 * @returns {Promise<{code?: number, error?: Error, out: string, err: string}>}
 */
async function runCli(argv, { cwd, stdin, onStdout, timeoutMs = 15000 } = {}) {
  const out = []
  const err = []
  const io = {
    stdin: stdin || { isTTY: false },
    stdout: {
      write(s) {
        out.push(s)
        if (onStdout) onStdout(s)
        return true
      },
    },
    stderr: {
      write(s) {
        err.push(s)
        return true
      },
    },
  }
  const prev = process.cwd()
  let timer
  process.chdir(cwd)
  try {
    const code = await Promise.race([
      main(argv, io),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`CLI 超时未返回（>${timeoutMs}ms）`)), timeoutMs)
      }),
    ])
    return { code, out: out.join(''), err: err.join('') }
  } catch (error) {
    return { error, out: out.join(''), err: err.join('') }
  } finally {
    clearTimeout(timer)
    process.chdir(prev)
    // CLI 已结束：收掉假 TTY，别让挂着的 readline 拖住进程退出
    if (stdin && typeof stdin.destroy === 'function') stdin.destroy()
  }
}

/**
 * 假 TTY stdin：isTTY=true 让 CLI 走交互分支，但 stdout 不带 isTTY ⇒ readline 走行模式（按 \n 断行）。
 * 答案按「提问串出现」逐条喂进去（script = [[触发子串, 回答], …]，按出现顺序各用一次）——
 * 提前喂会被上一段 readline 会话吃掉，所以必须等对应提问真的写出来。
 */
function ttyStdin(script) {
  const steps = script.map(([trigger, answer]) => ({ trigger, answer, used: false }))
  const stream = new Readable({ read() {} })
  stream.isTTY = true
  const onStdout = (s) => {
    const step = steps.find((it) => !it.used && s.includes(it.trigger))
    if (step) {
      step.used = true
      stream.push(step.answer)
    }
  }
  return { stdin: stream, onStdout }
}

/** 交互式问答的最小参数：id 走位置参数目录名缺省（真机里也是这么问的），name/desc/author 用 flag 给齐。 */
const INTERACTIVE_ARGV = [
  'my-tool',
  '--kind',
  'java',
  '--mode',
  'builtin',
  '--name',
  'n',
  '--desc',
  'd',
  '--author',
  'a',
]

after(() => {
  for (const dir of trash) {
    try {
      fs.rmSync(dir, { recursive: true, force: true })
    } catch {
      /* 清理失败不影响结论 */
    }
  }
})

test('目录定位：[dir] 优先于 mode 推导，内置/外部各有缺省位置', () => {
  const repo = makeRepo()
  const explicit = resolveTarget({
    dirArg: 'out/x',
    id: 'my-tool',
    mode: 'builtin',
    cwd: repo,
    searchStarts: [repo],
  })
  assert.equal(explicit.target, path.join(repo, 'out', 'x'))
  assert.equal(explicit.auto, false)

  const builtin = resolveTarget({
    dirArg: null,
    id: 'my-tool',
    mode: 'builtin',
    cwd: repo,
    searchStarts: [repo],
  })
  assert.equal(builtin.target, path.join(repo, 'every-agent-plugins', 'my-tool'))
  assert.equal(builtin.auto, true)

  const standalone = resolveTarget({
    dirArg: null,
    id: 'my-tool',
    mode: 'standalone',
    cwd: repo,
    searchStarts: [repo],
  })
  assert.equal(standalone.target, path.join(repo, 'my-tool'))
  assert.equal(standalone.auto, true)

  // 找不到仓库根时 builtin 无法自动选址（提示改用 standalone 或显式给目录）
  const probe = noRepoProbePath()
  assert.throws(
    () =>
      resolveTarget({
        dirArg: null,
        id: 'my-tool',
        mode: 'builtin',
        cwd: probe,
        searchStarts: [probe],
      }),
    ValidationError,
  )
})

test('落点自检：builtin 只认 every-agent-plugins/ 的一级子目录，standalone 不受限', () => {
  const repo = makeRepo()
  const pluginsDir = path.join(repo, 'every-agent-plugins')

  const inside = checkBuiltinPlacement({
    mode: 'builtin',
    target: path.join(pluginsDir, 'my-tool'),
    id: 'my-tool',
    repoRoot: repo,
  })
  assert.equal(inside.ok, true)
  assert.equal(inside.warn, undefined)

  const deeper = checkBuiltinPlacement({
    mode: 'builtin',
    target: path.join(pluginsDir, 'sub', 'my-tool'),
    id: 'my-tool',
    repoRoot: repo,
  })
  assert.equal(deeper.ok, false, '深一层不被内置扫描器发现，同样要拦')

  const outside = checkBuiltinPlacement({
    mode: 'builtin',
    target: path.join(repo, 'my-tool'),
    id: 'my-tool',
    repoRoot: repo,
  })
  assert.equal(outside.ok, false)
  assert.equal(outside.suggested, path.join(pluginsDir, 'my-tool'))
  assert.ok(outside.hints.some((h) => h.includes('relativePath')))
  assert.ok(outside.hints.some((h) => h.includes('--mode standalone')))

  const noRepo = checkBuiltinPlacement({
    mode: 'builtin',
    target: path.join(repo, 'my-tool'),
    id: 'my-tool',
    repoRoot: null,
  })
  assert.equal(noRepo.ok, false)
  assert.equal(noRepo.suggested, null, '没有仓库根就给不出建议位置')

  const standalone = checkBuiltinPlacement({
    mode: 'standalone',
    target: path.join(repo, 'my-tool'),
    id: 'my-tool',
    repoRoot: repo,
  })
  assert.equal(standalone.ok, true)
  assert.equal(standalone.suggested, null)
})

test('落点自检：同级缺插件 SDK parent 时放行但告警', () => {
  const repo = makeRepo({ withParent: false })
  const result = checkBuiltinPlacement({
    mode: 'builtin',
    target: path.join(repo, 'every-agent-plugins', 'my-tool'),
    id: 'my-tool',
    repoRoot: repo,
  })
  assert.equal(result.ok, true)
  assert.match(result.warn, /every-agent-plugin-parent/)
})

test('端到端矩阵：位置参数 × mode', async () => {
  const cases = [
    {
      title: 'builtin + 位置参数（仓库根下，BUG-1 原写法）→ 拒绝并给出 --id 正确命令',
      argv: ['my-tool', '--kind', 'java', '--mode', 'builtin', '--yes'],
      expect: 'reject',
    },
    {
      title: 'builtin + --id → 落到 every-agent-plugins/<id>',
      argv: ['--id', 'my-tool', '--kind', 'java', '--mode', 'builtin', '--yes'],
      expect: 'ok',
      rel: 'every-agent-plugins/my-tool',
      pomParentRelativePath: '../../every-agent-plugin-parent/pom.xml',
    },
    {
      title: 'builtin + 位置参数写在 every-agent-plugins/ 下 → 放行（显式目录不回归）',
      argv: ['every-agent-plugins/my-tool', '--kind', 'web', '--mode', 'builtin', '--yes'],
      expect: 'ok',
      rel: 'every-agent-plugins/my-tool',
    },
    {
      title: 'standalone + 位置参数 → 原位生成（standalone 不回归）',
      argv: ['my-tool', '--kind', 'java', '--mode', 'standalone', '--yes'],
      expect: 'ok',
      rel: 'my-tool',
      pomParentRelativePath: '',
    },
    {
      title: 'builtin + 位置参数 + --dry-run → 只警告不拦，且不写盘',
      argv: ['my-tool', '--kind', 'java', '--mode', 'builtin', '--yes', '--dry-run'],
      expect: 'warn',
    },
  ]

  for (const c of cases) {
    const repo = makeRepo()
    const target = c.rel ? path.join(repo, ...c.rel.split('/')) : path.join(repo, 'my-tool')
    const result = await runCli(c.argv, { cwd: repo })

    if (c.expect === 'reject') {
      assert.ok(result.error instanceof ValidationError, `${c.title}: 应抛 ValidationError`)
      assert.equal(result.error.exitCode, 2, `${c.title}: 退出码应为 2`)
      assert.match(result.error.message, /every-agent-plugins/)
      const hints = result.error.hints.join('\n')
      assert.match(hints, /--id my-tool --kind java --mode builtin/, `${c.title}: 应给出拼好的正确命令`)
      assert.equal(fs.existsSync(target), false, `${c.title}: 拒绝时不得留下目录`)
      continue
    }

    if (c.expect === 'warn') {
      assert.equal(result.error, undefined, `${c.title}: 不该抛错`)
      assert.equal(result.code, 0)
      assert.match(result.err, /警告：/)
      assert.match(result.err, /--id my-tool --kind java --mode builtin/, '警告里也要给正确命令')
      assert.equal(fs.existsSync(target), false, `${c.title}: dry-run 不写盘`)
      assert.match(result.out, /--dry-run：以上文件未写入。/)
      continue
    }

    assert.equal(result.error, undefined, `${c.title}: 不该抛错（${result.error?.message || ''}）`)
    assert.equal(result.code, 0, c.title)
    assert.equal(fs.existsSync(path.join(target, 'plugin.json')), true, `${c.title}: 缺 plugin.json`)
    assert.equal(fs.existsSync(path.join(target, 'README.md')), true, `${c.title}: 缺 README.md`)
    if (c.pomParentRelativePath !== undefined) {
      const pom = fs.readFileSync(path.join(target, 'pom.xml'), 'utf8')
      assert.ok(
        pom.includes(`<relativePath>${c.pomParentRelativePath}</relativePath>`),
        `${c.title}: pom 的 relativePath 不符（期望 "${c.pomParentRelativePath}"）`,
      )
    }
  }
})

test('位置参数给「别处的目录」：standalone 照常生成，builtin 拒绝且不动既有目录', async () => {
  const standaloneDir = makeElsewhereDir()
  const ok = await runCli(['my-tool', '--kind', 'web', '--mode', 'standalone', '--yes'], {
    cwd: standaloneDir,
  })
  assert.equal(ok.error, undefined, ok.error?.message)
  assert.equal(ok.code, 0)
  assert.equal(fs.existsSync(path.join(standaloneDir, 'my-tool', 'plugin.json')), true)

  const builtinDir = makeElsewhereDir()
  const rejected = await runCli(['my-tool', '--kind', 'web', '--mode', 'builtin', '--yes'], {
    cwd: builtinDir,
  })
  assert.ok(rejected.error instanceof ValidationError)
  assert.equal(rejected.error.exitCode, 2)
  assert.match(rejected.error.message, /every-agent-plugins/)
  assert.match(rejected.error.hints.join('\n'), /--id my-tool/, '拒绝时给拼好的正确命令')
  assert.equal(fs.existsSync(path.join(builtinDir, 'my-tool')), false, '拒绝时不得留下目录')
})

test('交互：落点不对时当场确认改用仓库内默认位置（回车 = 改用）', async () => {
  const repo = makeRepo()
  const { stdin, onStdout } = ttyStdin([
    ['插件 id', 'my-tool\n'],
    ['改用仓库内默认位置', '\n'],
  ])
  const result = await runCli(INTERACTIVE_ARGV, { cwd: repo, stdin, onStdout })
  assert.equal(result.error, undefined, result.error?.message)
  assert.equal(result.code, 0)
  assert.match(result.out, /插件 id （回车取 "my-tool"）/, 'id 问答用位置参数目录名做缺省值')
  assert.match(result.out, /改用仓库内默认位置并继续吗/, '应打印落点确认提问')
  assert.equal(
    fs.existsSync(path.join(repo, 'every-agent-plugins', 'my-tool', 'plugin.json')),
    true,
    '确认后应落到仓库内默认位置',
  )
  assert.equal(fs.existsSync(path.join(repo, 'my-tool')), false, '不该在原位置留目录')
})

test('交互：落点不对且回答 n → 退出码 130，不写任何文件', async () => {
  const repo = makeRepo()
  const { stdin, onStdout } = ttyStdin([
    ['插件 id', 'my-tool\n'],
    ['改用仓库内默认位置', 'n\n'],
  ])
  const result = await runCli(INTERACTIVE_ARGV, { cwd: repo, stdin, onStdout })
  assert.ok(result.error instanceof CanceledError, `应抛 CanceledError，实际 ${result.error}`)
  assert.equal(result.error.exitCode, 130)
  assert.match(result.error.hints.join('\n'), /--id my-tool/, '取消时也要给正确命令')
  assert.equal(fs.existsSync(path.join(repo, 'my-tool')), false)
  assert.equal(fs.existsSync(path.join(repo, 'every-agent-plugins', 'my-tool')), false)
})