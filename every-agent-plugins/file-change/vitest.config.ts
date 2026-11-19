/**
 * file-change 插件最小测试配置(复用 every-agent-web 安装的 vitest,模式同 svg-renderer)。
 *
 * 运行(仓库根):
 *   every-agent-web\node_modules\.bin\vitest run --config every-agent-plugins\file-change\vitest.config.ts
 *
 * - 纯对象默认导出(插件目录下 node 解析不到 every-agent-web/node_modules 的
 *   'vitest/config',本文件也不参与 tsc --noEmit)。
 * - 测试文件放插件根而非 web/:every-agent-web 的 tsconfig include 覆盖各插件 web 目录
 *   参与 tsc --noEmit,而 'vitest' 类型自插件目录不可解析——测试留在 tsc 范围外。
 * - root 显式锚定本目录,任意 cwd 以 --config 运行均正确。
 */
export default {
  root: __dirname,
  test: {
    environment: 'node',
    include: ['*.test.ts', 'web/**/*.test.ts'],
  },
}
