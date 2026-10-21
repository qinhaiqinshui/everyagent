import { defineConfig } from 'vitest/config'
import { resolve } from 'path'

/**
 * vitest 测试配置(Phase 0 特征化测试引入的最小测试基建)。
 *
 * - resolve.alias 与 vite.config.ts 保持同一套别名(@ / @every-agent/client /
 *   @everyagent/plugin-api / @plugins),被测模块内的别名导入可正常解析;
 * - 测试覆盖纯数据层(src/task 折叠器等),node 环境即可,不引入 jsdom;
 * - include 收敛到 src 目录树下的 *.test.ts(glob 模式),测试文件放被测模块同目录
 *   (与 tsconfig 对 src/sdk 目录下 *.test.ts 的 exclude 惯例一致;src 下的测试文件
 *   参与 tsc --noEmit)。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
      '@every-agent/client': resolve(__dirname, 'src/sdk'),
      '@everyagent/plugin-api': resolve(__dirname, '../every-agent-plugin-api/js/index.ts'),
      '@plugins': resolve(__dirname, '../every-agent-plugins'),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
