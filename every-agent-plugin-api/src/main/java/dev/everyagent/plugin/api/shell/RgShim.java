package dev.everyagent.plugin.api.shell;

import java.nio.file.Path;

/**
 * rg 的 PowerShell 包装脚本（沙箱后端共用：windows-mic / sandbox-windows-codex）。
 *
 * <p>解决两件在沙箱里<b>静默给出错误答案</b>的问题——它们与模型写法无关,只能由工具层兜住：
 *
 * <p><b>1. 非 ASCII 输出乱码(BUG-1)</b>:PowerShell 5.1 在<b>无真实控制台</b>(沙箱子进程正是如此)
 * 时,用系统 OEM 码页(中文 Windows=936/GBK)解码原生子进程的 stdout,而 rg 恒输出 UTF-8;
 * CLM(Constrained Language Mode)又禁止运行时改 {@code [Console]::OutputEncoding},{@code chcp}
 * 也不同步到它。于是 UTF-8 字节被按 GBK 解码(非法序列当场成 U+FFFD,信息不可逆丢失)再编码回去
 * ——读端怎么"智能解码"都救不回来,中文文件名还会被回灌成 os error 2 让任务卡死。
 * 包装把 rg 的 stdout/stderr 用<b>文件</b>承载({@code Start-Process -RedirectStandardOutput}),
 * rg 直接继承文件句柄写原始字节,PS 完全不参与转码;随后 {@code Get-Content -Encoding UTF8} 在
 * PS 进程内正确解码,再由 PS 编码送出——整条链路只走"cmdlet 文本"这一条已被验证正确的路。
 *
 * <p><b>2. 无搜索路径时静默空结果(BUG-5)</b>:沙箱里 stdin 恒为 NUL 设备,而 rg 的规则是
 * 「未给路径 且 stdin 非终端 → 把 stdin 当搜索源」,于是 {@code rg 某个符号} 返回空 + exit 1,
 * 与「代码里不存在这个符号」完全同形。包装按 rg 自身语法判定「确实没给路径」时补 {@code .}。
 *
 * <p><b>3. 退出码传导</b>:{@code Start-Process -PassThru} 读到的 {@code ExitCode} 写回
 * {@code $LASTEXITCODE},让工具尾注 {@code [exit code: N]} 如实反映 rg(1=无匹配,2=用法/正则错误);
 * rg 的 stderr 用 {@code cmd /c type … 1>&2} 原样送回 PS 的 stderr,不掺 PS 的 ErrorRecord 装饰,
 * 也不产生 CLIXML。
 *
 * <p>实现约束(都在中文 Windows 沙箱里实测过):只用 cmdlet / 核心类型 / 变量赋值——CLM 下
 * 方法调用与 .NET 类型字面量会被拒;不走 {@code cmd /c "rg … > file"} 那条路(cmd 会把整条
 * {@code /c} 参数当一条命令重解析,重定向与 {@code %ERRORLEVEL%} 都被吞);{@code $env:TEMP}
 * 在受限账户下常不可写,故承载文件必须落在工作区 {@code .everyagent/tmp}。
 */
public final class RgShim {

    private RgShim() {
    }

    /**
     * 生成包装脚本片段（自带结尾 {@code "; "}，可直接前置拼进命令串）。
     *
     * @param rgExe         rg 可执行文件绝对路径
     * @param workspaceRoot 工作区根（承载文件落点 {@code <root>/.everyagent/tmp}）
     * @return PowerShell 脚本片段
     */
    public static String build(String rgExe, Path workspaceRoot) {
        String scratch = workspaceRoot.resolve(".everyagent").resolve("tmp").toString();
        return """
                $global:__EaRg = '%1$s'
                $global:__EaRgDir = '%2$s'
                function global:rg {
                  $av = @($args)
                  $vf = '-g','--glob','-A','--after-context','-B','--before-context','-C','--context',
                       '-m','--max-count','-d','--max-depth','-e','--regexp','-f','--file','-r','--replace',
                       '--encoding','-t','--type','-T','--type-not','--type-add','--type-clear','--pre',
                       '--pre-glob','--max-columns','--colors','--sort','--sortr','--threads','--timeout',
                       '--buffer-size','--ignore-file','--hyperlink-format','-s','--separator'
                  $fm = '--files','--type-list','--generate','--help','-h','--version'
                  $pf = '-e','--regexp','-f','--file'
                  $filesMode = $false; $patFromFlag = $false
                  foreach ($x in $av) { $s = [string]$x
                    if ($fm -ccontains $s) { $filesMode = $true }
                    if ($pf -ccontains $s) { $patFromFlag = $true } }
                  $pos = 0; $skip = $false
                  foreach ($x in $av) {
                    if ($skip) { $skip = $false; continue }
                    $s = [string]$x
                    if ($s.StartsWith('-')) { $n = ($s -split '=')[0]
                      if (($vf -ccontains $n) -and -not $s.Contains('=')) { $skip = $true }
                      continue }
                    $pos++ }
                  $min = 2
                  if ($filesMode) { $min = 0 } elseif ($patFromFlag) { $min = 1 }
                  $pipe = @($input)
                  if ($pipe.Count -gt 0) { $pipe | & $global:__EaRg @av; return }
                  if ($pos -lt $min) { $av = $av + '.' }
                  $out = $global:__EaRgDir + '\\ea-rg-out-' + $PID + '.txt'
                  $err = $global:__EaRgDir + '\\ea-rg-err-' + $PID + '.txt'
                  try {
                    if (-not (Test-Path -LiteralPath $global:__EaRgDir)) {
                      New-Item -ItemType Directory -Force -Path $global:__EaRgDir | Out-Null }
                    foreach ($f in $out,$err) {
                      if (Test-Path -LiteralPath $f) {
                        Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue } }
                    $q = $av | ForEach-Object { $s = [string]$_
                      if ($s -match '[\\s"&|<>^]') { '"' + ($s -replace '"','\\"') + '"' } else { $s } }
                    $p = Start-Process -FilePath $global:__EaRg -ArgumentList ($q -join ' ') `
                      -NoNewWindow -Wait -PassThru -RedirectStandardOutput $out `
                      -RedirectStandardError $err
                    if (Test-Path -LiteralPath $out) { Get-Content -LiteralPath $out -Encoding UTF8 }
                    if (Test-Path -LiteralPath $err) {
                      $t = Get-Content -LiteralPath $err -Encoding UTF8 -Raw
                      if ($t) { & $env:ComSpec /c ('type "' + $err + '" 1>&2') } }
                    if ($p -ne $null) { $global:LASTEXITCODE = $p.ExitCode }
                  } catch {
                    # 承载文件建不出来(工作区不可写等):退回直跑,语义至少不变,绝不因包装而失败
                    & $global:__EaRg @av
                  } finally {
                    foreach ($f in $out,$err) {
                      if (Test-Path -LiteralPath $f) {
                        Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue } }
                  }
                }
                """.formatted(q(rgExe), q(scratch)) + "; ";
    }

    /** PowerShell 单引号串内转义（单引号双写）。 */
    private static String q(String s) {
        return s.replace("'", "''");
    }
}
