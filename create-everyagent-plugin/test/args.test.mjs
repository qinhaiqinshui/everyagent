/**
 * test/args.test.mjs —— CLI 参数解析矩阵（node:test 内置运行器，零依赖）。
 *
 * 位置参数语义是 `[dir]`（输出目录，**不是插件 id**）——这一条被踩过（BUG-1：位置参数当 id 用时
 * 静默生成到仓库根，pom 必构建失败），所以这里把「位置参数 × 子命令 × flag」的组合逐一钉住，
 * 落点防呆的行为由 test/cli.test.mjs 覆盖。
 *
 * 跑法：node --test（或 npm test），在 create-everyagent-plugin/ 下执行。
 */

import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { parseArgs } from '../lib/args.mjs'
import { UsageError } from '../lib/errors.mjs'

test('位置参数解析为输出目录 [dir]，flag 与开关各就各位', () => {
  const parsed = parseArgs(['my-tool', '--kind', 'java', '--mode', 'builtin', '--yes'])
  assert.equal(parsed.dirArg, 'my-tool')
  assert.deepEqual(parsed.positional, ['my-tool'])
  assert.equal(parsed.subcommand, null)
  assert.equal(parsed.values.kind, 'java')
  assert.equal(parsed.values.mode, 'builtin')
  assert.equal(parsed.bools.yes, true)
})

test('位置参数在 flag 之后同样识别（顺序无关）', () => {
  const parsed = parseArgs(['--id', 'x', '--mode', 'standalone', 'out/tool'])
  assert.equal(parsed.dirArg, 'out/tool')
  assert.equal(parsed.values.id, 'x')
  assert.equal(parsed.values.mode, 'standalone')
  assert.equal(parsed.bools.yes, undefined)
})

test('-- 之后一律当位置参数（目录名以连字符开头也安全）', () => {
  const parsed = parseArgs(['--yes', '--', '-weird-dir'])
  assert.equal(parsed.dirArg, '-weird-dir')
  assert.equal(parsed.bools.yes, true)
})

test('--flag=value 内联取值形态', () => {
  const parsed = parseArgs(['--id=my-tool', '--kind=web', '--name=我的工具'])
  assert.equal(parsed.values.id, 'my-tool')
  assert.equal(parsed.values.kind, 'web')
  assert.equal(parsed.values.name, '我的工具')
})

test('短别名 -y / -f / -h / -v 展开为长 flag', () => {
  const parsed = parseArgs(['-y', '-f'])
  assert.equal(parsed.bools.yes, true)
  assert.equal(parsed.bools.force, true)
  assert.equal(parsed.bools.help, undefined)
  const help = parseArgs(['-h'])
  assert.equal(help.bools.help, true)
  assert.equal(parseArgs(['-v']).bools.version, true)
})

test('pack 子命令被识别；--verify 不在主 flag 集里 ⇒ main() 必须先截走 pack', () => {
  const parsed = parseArgs(['pack', 'every-agent-plugins/my-tool'])
  assert.equal(parsed.subcommand, 'pack')
  assert.deepEqual(parsed.positional, ['every-agent-plugins/my-tool'])
  // -o/--output 在主 flag 集里（pack 子命令与主命令共用），--verify 不在 ⇒ 主解析器会拒掉它
  assert.equal(parseArgs(['pack', 'p', '-o', 'dist']).values.output, 'dist')
  assert.throws(() => parseArgs(['pack', 'p', '--verify']), UsageError)
})

test('只能有一个位置参数', () => {
  assert.throws(() => parseArgs(['a', 'b']), UsageError)
  assert.throws(() => parseArgs(['pack', 'a', 'b']), UsageError)
})

test('未知 flag / 缺值 / 重复一律 UsageError，不静默忽略', () => {
  assert.throws(() => parseArgs(['--nope']), UsageError)
  assert.throws(() => parseArgs(['--kind']), UsageError)
  assert.throws(() => parseArgs(['--id', 'a', '--id', 'b']), UsageError)
  assert.throws(() => parseArgs(['--yes=true']), UsageError)
  assert.throws(() => parseArgs(['--id=']), UsageError)
  assert.throws(() => parseArgs(['-k', 'java']), UsageError)
  // 单字母别名不接受长写法：--y 是未知 flag
  assert.throws(() => parseArgs(['--y']), UsageError)
})

test('flag 取值不能误吞下一个 flag', () => {
  assert.throws(() => parseArgs(['--id', '--kind', 'java']), UsageError)
})