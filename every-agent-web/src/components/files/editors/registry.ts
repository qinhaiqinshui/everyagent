import type { FileEditorKind } from '@/types'
import type { FileContentEditorDescriptor } from './types'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'

type FileContentEditorModule = {
  descriptor?: FileContentEditorDescriptor
}

// 核心只保留 Json / Text（及 fallback）编辑器；`.md` 等插件自带编辑器通过
// 通用扩展扫描从 `src/plugins/*/editors/` 发现（如 markdown 插件）。
const editorModuleMap = import.meta.glob(
  ['./UniversalFileEditor.tsx', '../../../plugins/*/editors/*FileEditor.tsx'],
  { eager: true },
) as Record<string, FileContentEditorModule>

const editorDescriptors = Object.values(editorModuleMap)
  .map((module) => module.descriptor)
  .filter((descriptor): descriptor is FileContentEditorDescriptor => Boolean(descriptor))

/**
 * 合并内置（glob 扫描）与插件注册（ui.file_content_editors 扩展点）的编辑器。
 * 插件编辑器排在前面：同名扩展名匹配时插件优先，可覆盖内置编辑器。
 * 每次调用实时合并，插件在 activate() 中注册后立即生效（无需重启）。
 */
function allEditorDescriptors(): FileContentEditorDescriptor[] {
  return [...pluginDispatcher.listRegisteredFileContentEditors(), ...editorDescriptors]
}

function normalizeExtension(filePath: string): string {
  const normalized = filePath.trim().replace(/\\/g, '/').toLowerCase()
  const lastSlashIndex = normalized.lastIndexOf('/')
  const fileName = lastSlashIndex >= 0 ? normalized.slice(lastSlashIndex + 1) : normalized
  const dotIndex = fileName.lastIndexOf('.')
  if (dotIndex <= 0) {
    return ''
  }
  return fileName.slice(dotIndex)
}

export function listFileContentEditors(): FileContentEditorDescriptor[] {
  return allEditorDescriptors()
}

export function getFallbackFileContentEditor(): FileContentEditorDescriptor {
  const fallback = allEditorDescriptors().find((item) => item.isFallback)
  if (!fallback) {
    throw new Error('缺少文件内容 fallback 编辑器')
  }
  return fallback
}

export function resolveFileContentEditorByPath(filePath: string): FileContentEditorDescriptor {
  const extension = normalizeExtension(filePath)
  if (!extension) {
    return getFallbackFileContentEditor()
  }
  return allEditorDescriptors().find((item) => item.extensions.includes(extension)) ?? getFallbackFileContentEditor()
}

export function resolveFileEditorKindByPath(filePath: string): FileEditorKind {
  return resolveFileContentEditorByPath(filePath).kind
}
