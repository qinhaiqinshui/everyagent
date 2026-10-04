package dev.everyagent.plugin.sandbox.mic;

/**
 * windows-mic 沙箱里 rg 的 PowerShell 包装脚本（只在 shell=powershell 时前置注入）。
 *
 * <p>职责单一：生成「让 {@code rg} 在沙箱里行为与交互终端一致」的 PowerShell 片段——
 * 即「模型没给搜索路径时补 {@code .}」。PATH 注入与工具注册在
 * {@link WindowsMicShellToolProvider}，编码/退出码承载在 worker 的
 * {@code CommandExecutor} / {@code OsSandbox}，互不掺手。
 */
final class MicRgShim {

    private MicRgShim() {
    }

    /**
     * 生成 rg 包装脚本：仅在「模型没给搜索路径」时补 {@code .}，其余参数逐字透传。
     *
     * <p><b>为什么必须包</b>：沙箱里 powershell 的 stdin 恒为 NUL 设备（非终端），而 rg 的规则是
     * 「未给路径 且 stdin 非终端 → 把 stdin 当搜索源」。于是模型敲 {@code rg 某个符号} 得到的是
     * <b>空结果 + exit 1</b>，与「代码里不存在这个符号」完全同形——是最恶劣的一类静默错误
     *（实测：搜 {@code class ExecResults} 返回空，补一个 {@code .} 立刻命中）。
     *
     * <p><b>判定规则</b>（对齐 rg 自身语法）：
     * <ul>
     *   <li>{@code --files} / {@code --type-list} / {@code --generate} / {@code --help} /
     *       {@code -h} / {@code --version}：既不读 stdin 也不要求模式参数 → 永不补路径
     *       （补了会把输出前缀变成 {@code .\}，与交互终端不一致）；</li>
     *   <li>模式由 {@code -e} / {@code -f} 提供：位置参数全是路径 → 一个都没有才补；</li>
     *   <li>其余：位置参数 = 模式 + 路径，只有 1 个（只给了模式）时补。</li>
     * </ul>
     * 值跟随型 flag（{@code -g} / {@code -C} / {@code --encoding} …）会吃掉后一个 token，
     * 必须先跳过再数位置参数；{@code --flag=value} 与 {@code -A2} 这类附着值不吃下一个 token。
     * <b>比较必须大小写敏感（{@code -ccontains}）</b>：PowerShell 的 {@code -contains} 不分大小写，
     * 会把布尔开关 {@code -c}(count) 误判成值型 {@code -C}(context)，进而把模式当 flag 值吞掉——
     * 实测会让 {@code rg -c 模式} 退化成「无位置参数」而不补路径。
     *
     * <p>管道用法（{@code Get-Content f | rg 模式}）保持原语义：检测到管道输入即不再补路径，
     * 让 rg 继续以 stdin 为搜索源。
     *
     * @param rgExe rg.exe 绝对路径（PowerShell 单引号串内，单引号按双写转义）
     * @return 可直接前置进命令串的 PowerShell 片段（自带结尾 {@code "; "}）
     */
    static String build(String rgExe) {
        String quoted = rgExe.replace("'", "''");
        return ("""
                $global:__EaRg = '%s'
                function global:rg {
                  $a = @($args)
                  $vf = '-g','--glob','-A','--after-context','-B','--before-context','-C','--context',
                       '-m','--max-count','-d','--max-depth','-e','--regexp','-f','--file','-r','--replace',
                       '--encoding','-t','--type','-T','--type-not','--type-add','--type-clear','--pre',
                       '--pre-glob','--max-columns','--colors','--sort','--sortr','--threads','--timeout',
                       '--buffer-size','--ignore-file','--hyperlink-format','-s','--separator'
                  $fm = '--files','--type-list','--generate','--help','-h','--version'
                  $pf = '-e','--regexp','-f','--file'
                  $filesMode = $false; $patFromFlag = $false
                  foreach ($x in $a) { $s = [string]$x
                    if ($fm -ccontains $s) { $filesMode = $true }
                    if ($pf -ccontains $s) { $patFromFlag = $true } }
                  $pos = 0; $skip = $false
                  foreach ($x in $a) {
                    if ($skip) { $skip = $false; continue }
                    $s = [string]$x
                    if ($s.StartsWith('-')) { $n = ($s -split '=')[0]
                      if (($vf -ccontains $n) -and -not $s.Contains('=')) { $skip = $true }
                      continue }
                    $pos++ }
                  $min = 2
                  if ($filesMode) { $min = 0 } elseif ($patFromFlag) { $min = 1 }
                  $pipe = @($input); $argv = $a
                  if ($pipe.Count -eq 0 -and $pos -lt $min) { $argv = $a + '.' }
                  if ($pipe.Count -gt 0) { $pipe | & $global:__EaRg @argv }
                  else { & $global:__EaRg @argv }
                }
                """).formatted(quoted) + "; ";
    }
}
