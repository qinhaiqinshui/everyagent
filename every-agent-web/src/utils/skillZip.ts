/**
 * skill zip 导入/导出的共享工具(自 SkillListTable.tsx 抽出,逻辑原样搬运)。
 *
 * 供设置页「Skill 列表」(SkillListTable)使用:
 * - MAX_IMPORT_ZIP_BYTES / ZIP_RPC_TIMEOUT_MS:导入 zip 的前端预检上限与 RPC 宽限超时;
 * - base64ToBytes / bytesToBase64:zip 内容与 wire base64 的互转;
 * - pickZipFile:唤起单选 zip 文件选择器(须在用户手势调用栈内触发)。
 */

/** 导入 zip 的前端预检上限(与 worker 侧 skill.import 的 16MB 解码上限对齐)。 */
export const MAX_IMPORT_ZIP_BYTES = 16 * 1024 * 1024

/** 导出/导入 RPC 超时(打包/解压大目录时的宽限,纯客户端语义)。 */
export const ZIP_RPC_TIMEOUT_MS = 300_000

/** base64 → 字节(逐字符拷贝,UTF-8 安全)。 */
export function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

/** 字节 → base64(分块 btoa,避免大数组爆调用栈)。 */
export function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}

/** 单选 zip 文件(须在用户手势调用栈内触发);取消返回 null。 */
export function pickZipFile(): Promise<File | null> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.zip,application/zip'
    input.addEventListener('change', () => {
      resolve(input.files && input.files.length > 0 ? input.files[0] : null)
    })
    input.click()
  })
}
