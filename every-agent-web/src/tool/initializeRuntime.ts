/**
 * 运行时注册表初始化——v1 空壳。
 *
 * n 分支在此装载插件/工具/斜杠命令注册表(浏览器内 agent 运行时);
 * 运行时已下沉 worker,前端无可初始化内容。
 */
export async function initializeRuntimeRegistry(): Promise<void> {
  // v1:无运行时注册表
}
