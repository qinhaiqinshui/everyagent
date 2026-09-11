/**
 * 应用元信息(设置页「关于」区等共用)。
 *
 * 版本号由 vite.config.ts 在构建时从 package.json 注入(`__APP_VERSION__`),
 * 保持与根工程版本一致且单一事实源;此处仅提供类型声明与兜底值。
 */
declare const __APP_VERSION__: string

export const APP_NAME = 'Every Agent'

/** 当前软件版本,构建期注入;非 vite 环境(如直接引用源码)兜底为 0.0.0。 */
export const APP_VERSION: string = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : '0.0.0'

/** 版权/所有权信息,与仓库 LICENSE 文件头部保持一致。 */
export const APP_COPYRIGHT = 'Copyright 2026 Every Agent Contributors'

/** 开源许可证。 */
export const APP_LICENSE = 'Apache License 2.0'
