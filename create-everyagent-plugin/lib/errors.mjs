/**
 * lib/errors.mjs —— CLI 统一错误类型与退出码。
 *
 * 退出码约定（README「退出码」节同步）：
 *   0   成功
 *   1   用法错误（未知 flag、flag 缺值、未实现的子命令等）
 *   2   参数/校验失败（非法 id、kind/mode 取值非法、字段含控制字符）
 *   3   目标目录冲突（已存在且非空且未给 --force）
 *   4   写盘失败（权限、磁盘、路径越界）
 *   5   模板错误（模板根缺失、base 层同名冲突、未识别占位符、渲染后路径冲突）
 *   130 用户取消（交互问答 Ctrl+C / 明确拒绝）
 *
 * 所有面向用户的错误一律经 index.mjs 打到 stderr，不使用 emoji 前缀，
 * 风格与 scripts/*.py 保持一致（「错误：<原因>」+ 若干行提示）。
 */

export const EXIT = {
  OK: 0,
  USAGE: 1,
  INVALID: 2,
  CONFLICT: 3,
  IO: 4,
  TEMPLATE: 5,
  CANCELED: 130,
}

/** CLI 错误的公共基类：message 面向用户，hint 为可选的多行提示。 */
export class CliError extends Error {
  /**
   * @param {string} message 一句话错误原因（不打 emoji，不以换行开头）
   * @param {object} [opts]
   * @param {number} [opts.code] 退出码（默认 1）
   * @param {string[]} [opts.hints] 逐行提示（缩进由输出层负责）
   */
  constructor(message, { code = EXIT.USAGE, hints = [] } = {}) {
    super(message)
    this.name = 'CliError'
    this.exitCode = code
    this.hints = hints
  }
}

/** 用法错误（flag 不认识 / 缺值 / 子命令未实现）。 */
export class UsageError extends CliError {
  constructor(message, opts = {}) {
    super(message, { code: EXIT.USAGE, ...opts })
    this.name = 'UsageError'
  }
}

/** 参数与命名校验失败。 */
export class ValidationError extends CliError {
  constructor(message, opts = {}) {
    super(message, { code: EXIT.INVALID, ...opts })
    this.name = 'ValidationError'
  }
}

/** 模板层问题（冲突、占位符、模板根缺失）。 */
export class TemplateError extends CliError {
  constructor(message, opts = {}) {
    super(message, { code: EXIT.TEMPLATE, ...opts })
    this.name = 'TemplateError'
  }
}

/** 目标目录冲突。 */
export class ConflictError extends CliError {
  constructor(message, opts = {}) {
    super(message, { code: EXIT.CONFLICT, ...opts })
    this.name = 'ConflictError'
  }
}

/** 写盘失败。 */
export class WriteError extends CliError {
  constructor(message, opts = {}) {
    super(message, { code: EXIT.IO, ...opts })
    this.name = 'WriteError'
  }
}

/** 用户在交互问答里取消（Ctrl+C）。 */
export class CanceledError extends CliError {
  constructor(message = '用户取消', opts = {}) {
    super(message, { code: EXIT.CANCELED, ...opts })
    this.name = 'CanceledError'
  }
}

/** 是否是我们自己抛出的可控错误。 */
export function isCliError(err) {
  return err instanceof CliError
}
