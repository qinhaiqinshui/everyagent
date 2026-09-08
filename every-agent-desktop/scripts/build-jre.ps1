# 用 jlink 生成精简 JRE(JDK 25),产出 every-agent-desktop/resources/jre/。
# 用法: npm run build:jre(或 powershell -File scripts/build-jre.ps1)
# 前置: build-backend 已产出 resources/backend/{hub,worker}.jar;JDK 25 可用
#       ($env:JAVA_HOME 指向 jdk,或 jdeps/jlink 在 PATH)。
# 幂等:resources/jre 已存在则跳过 jlink(不重复解包 + jdeps + jlink)——发行包 backend
#       与 JRE 是构建期固定搭配,只有发新版(依赖变化)才需重建,此时显式
#       `npm run build:jre -- -Force`。不做基于 jar 内容的自动指纹:mvn package 本身
#       不可复现(实测连续两次 package 的 fat jar 字节、BOOT-INF/lib 依赖清单都会变),
#       任何自动指纹都会误判而退回「每次重建」。
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$desktopRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $desktopRoot 'resources\backend'
$jreDir = Join-Path $desktopRoot 'resources\jre'
$jars = @(
  (Join-Path $backendDir 'hub.jar'),
  (Join-Path $backendDir 'worker.jar')
)

foreach ($jar in $jars) {
  if (-not (Test-Path $jar)) {
    Write-Host "[build-jre] 缺少 $jar,请先执行 npm run build:backend" -ForegroundColor Red
    exit 1
  }
}

$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
  $javaHome = (Get-Command jdeps -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source | Split-Path | Split-Path)
}
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\jdeps.exe'))) {
  Write-Host '[build-jre] 找不到 JDK(需 JDK 25):请设置 JAVA_HOME 或确保 jdeps 在 PATH' -ForegroundColor Red
  exit 1
}

$jdeps = Join-Path $javaHome 'bin\jdeps.exe'
$jlink = Join-Path $javaHome 'bin\jlink.exe'

# 幂等:resources/jre 已存在则跳过 jlink。发行包的 backend jar 与 JRE 在构建期是
# 固定搭配,只有发新版(依赖变化)时才需重建——此时显式 npm run build:jre -- -Force。
# 不做基于 jar 内容的自动指纹:mvn package 本身不可复现(实测连续两次 package 的
# fat jar 字节、甚至 BOOT-INF/lib 依赖清单都会变),任何自动指纹都会误判而退回「每次重建」。
if (-not $Force -and (Test-Path $jreDir) -and (Test-Path (Join-Path $jreDir 'bin\java.exe'))) {
  Write-Host '[build-jre] JRE 已存在,跳过 jlink(依赖/模块变更时请用 -Force 强制重建)' -ForegroundColor Green
  exit 0
}

# 由两枚 jar 推导模块集合(jdeps --print-module-deps)。Spring Boot fat jar 的依赖在
# BOOT-INF/lib/*.jar 内,jdeps 直接分析外层 jar 看不到(只会推导出 java.base 之类),
# 故先解包再对目录分析;失败/为空时回退到 Spring Boot + Netty 常用超集。
$moduleSet = @{}
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("build-jre-" + [guid]::NewGuid().ToString('N'))
foreach ($jar in $jars) {
  $extractDir = Join-Path $tempDir ([System.IO.Path]::GetFileNameWithoutExtension($jar))
  New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
  Push-Location $extractDir
  try {
    & (Join-Path $javaHome 'bin\jar.exe') -xf $jar 2>$null
  } finally {
    Pop-Location
  }
  $libDir = Join-Path $extractDir 'BOOT-INF\lib'
  if (-not (Test-Path $libDir)) {
    # 非模块化普通 jar:直接分析 jar 本身
    $targets = @($jar)
  } else {
    $targets = @(Get-ChildItem -Path $libDir -Filter *.jar | Select-Object -ExpandProperty FullName)
  }
  $deps = & $jdeps --ignore-missing-deps --print-module-deps --multi-release 9 @targets 2>$null
  if ($LASTEXITCODE -eq 0 -and $deps) {
    foreach ($m in ($deps -split ',')) {
      $m = $m.Trim()
      if ($m -ne '') { $moduleSet[$m] = $true }
    }
  }
}
if (Test-Path $tempDir) { Remove-Item -Recurse -Force $tempDir }

if ($moduleSet.Count -eq 0) {
  Write-Host '[build-jre] jdeps 推导失败,使用安全超集模块列表' -ForegroundColor Yellow
  $fallback = @(
    'java.base','java.logging','java.naming','java.management','java.security.jgss',
    'java.instrument','java.net.http','java.sql','java.xml','java.desktop',
    'jdk.unsupported','jdk.crypto.ec','jdk.crypto.cryptoki','jdk.crypto.mscapi',
    'jdk.localedata','jdk.zipfs','java.compiler','java.datatransfer','java.prefs'
  )
  foreach ($m in $fallback) { $moduleSet[$m] = $true }
}

# 反射加载、jdeps 静态分析看不见的模块无条件并入:
#   jdk.crypto.ec / cryptoki / mscapi —— TLS(SunEC/SunMSCAPI) provider 按名注册,静态分析发现不了;
#   jdk.zipfs —— Spring Boot 用 zip 文件系统启动嵌套 jar(fat jar),缺了直接启动失败;
#   jdk.localedata —— 用户区域相关格式化。
foreach ($m in @('jdk.crypto.ec','jdk.crypto.cryptoki','jdk.crypto.mscapi','jdk.zipfs','jdk.localedata')) {
  $moduleSet[$m] = $true
}

$modules = ($moduleSet.Keys | Sort-Object) -join ','
Write-Host "[build-jre] modules: $modules"

if (Test-Path $jreDir) {
  Remove-Item -Recurse -Force $jreDir
}

& $jlink --add-modules $modules --output $jreDir --strip-debug --no-man-pages --no-header-files --compress=2
if ($LASTEXITCODE -ne 0) {
  Write-Host '[build-jre] jlink 失败' -ForegroundColor Red
  exit $LASTEXITCODE
}

$javaExe = Join-Path $jreDir 'bin\java.exe'
# java -version 走 stderr;PS 5.1 下 EAP=Stop + 2>&1 会把 stderr 包成 ErrorRecord 抛终止错误,故临时放宽。
$ErrorActionPreference = 'Continue'
$ver = & $javaExe -version 2>&1 | Select-Object -First 1
$ErrorActionPreference = 'Stop'
Write-Host "[build-jre] 完成: $javaExe ($ver)"