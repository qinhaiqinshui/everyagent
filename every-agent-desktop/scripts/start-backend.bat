@echo off
setlocal enabledelayedexpansion
rem ========================================================================
rem Every Agent worker 独立启动脚本
rem
rem 用途:脱离 Desktop GUI 独立启动 worker(hub 仍由 desktop 管理,不在此启动)。
rem 适配 Windows 任务计划程序"系统启动时"触发器(Session 0 无 GUI 场景)。
rem worker 启动后会自动连接 hub(localhost:6101);若 hub 尚未启动,worker 会
rem 持续重试连接,直到 desktop 启动拉起 hub 后自动连上。
rem
rem 用法:
rem   直接运行:双击或在命令行执行
rem   任务计划程序:程序填此 bat 路径;触发器选"登录时"或"系统启动时"(后者需以
rem                最高权限运行,且因 Session 0 无 GUI,仅 worker 进程启动,
rem                Desktop 窗口/托盘不显示——这是预期行为)。
rem
rem 打包后此脚本位于 <安装根>\resources\start-backend.bat,
rem 同目录下有 jre\、backend\、runtime\ 等(resourcesPath 即程序根)。
rem ========================================================================

rem 程序根 = 脚本所在目录(resourcesPath);显式经 -Deveryagent.program-dir 注入给 worker。
rem 注意:CWD 一律落 EVERYAGENT_HOME,不得落在安装目录——Windows 下进程 CWD 所在目录
rem 不可删除,以安装目录为 CWD 会导致卸载/覆盖安装时 resources 目录删不掉(实证 bug)。
set "PROGRAM_DIR=%~dp0"

rem 设置 EVERYAGENT_HOME(默认 %USERPROFILE%\.everyagent)
if not defined EVERYAGENT_HOME set EVERYAGENT_HOME=%USERPROFILE%\.everyagent

rem 确保日志目录存在
if not exist "%EVERYAGENT_HOME%\logs" mkdir "%EVERYAGENT_HOME%\logs"

rem 选择 Java 可执行文件:优先 jlink 精简 JRE 的 javaw.exe,回退 java.exe / 系统 java
set "JAVA_EXE=%PROGRAM_DIR%jre\bin\javaw.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=%PROGRAM_DIR%jre\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"

set "WORKER_URL=http://127.0.0.1:6102/health"

rem ------------------------------------------------------------------
rem  启动 worker
rem ------------------------------------------------------------------
echo [%date% %time%] 检查 worker 健康状态...
call :check_http "%WORKER_URL%"
if !errorlevel! equ 0 (
    echo [%date% %time%] worker 已在运行,跳过启动
) else (
    echo [%date% %time%] 启动 worker...
    cd /d "%EVERYAGENT_HOME%"
    start "" "%JAVA_EXE%" "-Deveryagent.program-dir=%PROGRAM_DIR%" -jar "%PROGRAM_DIR%backend\worker.jar" 1>> "%EVERYAGENT_HOME%\logs\worker.out.log" 2>&1
)

rem 等待 worker 就绪(健康检查 + hub 连接,最多 120 秒)
rem worker 会自动重试连接 hub;若 hub 尚未启动,worker 的 hubConnected 会保持 false,
rem 此脚本会等待 hub 就绪后才退出(预期 hub 由 desktop 启动,desktop 启动后 worker 自动连上)。
set WORKER_WAIT=0
:wait_worker
call :check_worker_ready "%WORKER_URL%"
if !errorlevel! equ 0 goto worker_ready
set /a WORKER_WAIT+=1
if !WORKER_WAIT! geq 120 (
    echo [%date% %time%] worker 等待 hub 就绪超时 ^(120s^),worker 进程已启动但可能尚未连接 hub
    exit /b 0
)
timeout /t 1 /nobreak >nul
goto wait_worker
:worker_ready
echo [%date% %time%] worker 就绪 ^(hub 连接已建立^)

echo [%date% %time%] worker 启动完成
exit /b 0

rem ======================================================================
rem  子例程:检查 HTTP 端点是否返回 200
rem  返回:errorlevel 0 = 健康,1 = 不可达/非 200
rem ======================================================================
:check_http
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -Uri '%~1' -TimeoutSec 3 -UseBasicParsing; if ($r.StatusCode -eq 200) { exit 0 } } catch {} exit 1"
goto :eof

rem ======================================================================
rem  子例程:检查 worker 是否就绪(健康检查 200 且 hubConnected=true)
rem  返回:errorlevel 0 = 就绪,1 = 未就绪
rem ======================================================================
:check_worker_ready
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -Uri '%~1' -TimeoutSec 3 -UseBasicParsing; $j = $r.Content | ConvertFrom-Json; if ($j.hubConnected -eq $true) { exit 0 } } catch {} exit 1"
goto :eof
