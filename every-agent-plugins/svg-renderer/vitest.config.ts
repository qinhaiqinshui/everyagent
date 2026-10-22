/**
 * svg-renderer 插件最小测试配置。
 *
 * 仓库既有测试基建是 every-agent-web 的 vitest（node 环境、无 jsdom），
 * 插件自身无 package.json，故复用其安装的 vitest 运行（仓库根执行）：
 *
 *   every-agent-web\node_modules\.bin\vitest run --config every-agent-plugins\svg-renderer\vitest.config.ts
 *
 * - 不 import 'vitest/config' 的 defineConfig：插件目录下 node 解析不到
 *   every-agent-web/node_modules，纯对象默认导出即可（本文件不参与 tsc --noEmit）。
 * - 测试文件放插件根（而非 web/）：every-agent-web 的 tsconfig include 覆盖
 *   every-agent-plugins 下各插件的 web 目录参与 tsc --noEmit，而 'vitest' 的类型
 *   自插件目录不可解析——测试须留在 tsc 范围外（与其「测试文件不参与 tsc」惯例一致）。
 * - root 显式锚定本目录：无论从哪个 cwd 以 --config 运行，include 都相对此处。
 * - node 环境：净化器经 SanitizeDom 注入测试替身（见 sanitize.test.ts），
 *   不引入 jsdom 等新依赖。
 */
export default {
  root: __dirname,
  test: {
    environment: 'node',
    include: ['*.test.ts', 'web/**/*.test.ts'],
  },
}
