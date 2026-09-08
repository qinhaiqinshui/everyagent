/**
 * 打开浏览器文件选择器，返回用户选择的文件列表。
 *
 * - directory=false：多选文件，每个文件的 webkitRelativePath 为空，
 *   调用方应直接用 file.name 作为文件名。
 * - directory=true：选择一个目录，文件会携带 webkitRelativePath
 *   （如 "myfolder/sub/file.txt"），调用方据此保持原目录结构。
 *
 * 必须在用户手势（click）调用栈中触发，否则浏览器会拦截。
 * 用户取消时 resolve 空数组。
 */
export function pickFiles(directory: boolean): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.multiple = true
    if (directory) {
      input.setAttribute('webkitdirectory', '')
    }
    input.addEventListener('change', () => {
      resolve(input.files ? Array.from(input.files) : [])
    })
    input.click()
  })
}
/**
 * 从 File 对象提取相对于上传目标的路径。
 * 目录上传时用 webkitRelativePath（含顶层文件夹名），否则用文件名。
 */
export function getFileRelativePath(file: File): string {
  const relativePath = file.webkitRelativePath
  if (relativePath) {
    return relativePath
  }
  return file.name
}
