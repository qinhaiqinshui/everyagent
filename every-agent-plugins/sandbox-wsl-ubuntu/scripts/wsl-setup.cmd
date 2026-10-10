@echo off
rem eagent wsl-bwrap sandbox one-click setup (double-click me)
rem Guides through: WSL platform -> distro (managed image / default) ->
rem deps (python3/bubblewrap/ripgrep/git) -> userns fix -> full probe.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0wsl-setup.ps1"
pause
