/**
 * markdown 内嵌 HTML 渲染管线(plugins/markdown,仅文件预览场景启用)。
 *
 * 背景:react-markdown 默认不渲染内嵌 HTML——README 里 `<p align="center">` +
 * `<img src="https://img.shields.io/...">` 的徽章块会被当纯文本回显,而 GitHub /
 * VS Code 预览均渲染。本模块按同一惯例接入两步:
 * - rehype-raw:把 CommonMark 解析保留的 raw HTML 片段解析进 hast 树;
 * - rehype-sanitize(默认白名单):对整棵树做安全清洗——script/事件属性(onerror
 *   等)被剥离,src/href 协议收敛到 http(s) 等安全白名单。默认 schema 已恰好覆盖
 *   本仓库所需:全局 align(<p align="center">)、code 的 className(language-*
 *   语法高亮)、img 的 src+alt。
 *
 * 范围约束:仅用于「用户工作区内 md 文件的预览」(与 VS Code 预览本地文件同级信任);
 * 聊天消息(模型输出)不经此管线——模型注入的 HTML 保持纯文本,不引入 XSS 面。
 * 外部图能显示还需 CSP 放行 img-src https:(见 index.html)。
 */
import rehypeRaw from 'rehype-raw'
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize'
import type { PluggableList } from 'unified'

/** 文件预览专用 rehype 插件序列:先解析 raw HTML,再统一安全清洗。 */
export const rawHtmlRehypePlugins: PluggableList = [rehypeRaw, [rehypeSanitize, defaultSchema]]
