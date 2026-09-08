/**
 * 触发浏览器下载：把 Blob 内容以指定文件名保存到本地。
 *
 * 该函数不关心内容来源（文件读取、ZIP 打包、生成数据等），
 * 只负责创建临时 URL 并通过隐藏 <a> 标签完成下载，结束后回收 URL。
 */
export function downloadBlob(blob: Blob, fileName: string): void {
  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = fileName
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(objectUrl)
}
