/**
 * 构建指纹(单一出口):由 vite.config.ts 的 define 注入 __APP_BUILD_ID__
 * (构建时间 + git 短 hash);dev server 下未注入,显示 dev。
 * 用途:页面视觉标记/控制台输出,即时判定「部署是否生效」「页面是哪个构建」。
 */
declare const __APP_BUILD_ID__: string | undefined

export const APP_BUILD_ID: string = typeof __APP_BUILD_ID__ !== 'undefined' ? __APP_BUILD_ID__ : 'dev'
