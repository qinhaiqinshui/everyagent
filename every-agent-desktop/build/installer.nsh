; Every Agent NSIS instrumentation (installer + uninstaller tracing).
;
; WHY THIS EXISTS: on some machines the NSIS uninstaller exits in under a
; second "successfully" while removing nothing (install dir, shortcuts and
; sandbox accounts all survive). The stock electron-builder uninstaller has
; no log, so we instrument every hook its NSIS templates expose.
;
; LOG TARGET: %USERPROFILE%\.everyagent\logs\uninstall.log (append mode,
; shared by install and uninstall runs, one timestamped line per event).
;
; READING THE ABSENCE OF LINES (crash localization):
;   - no "un.onInit done" line after an uninstall attempt
;       -> the uninstaller process died BEFORE finishing un.onInit
;          (self-copy to ~nsu.tmp or an early crash; check WER events).
;   - "un.onInit done" but no "un.install section" lines
;       -> died in the wizard pages (mode selection / user cancel).
;   - "removeFiles: RMDir done" + "INSTDIR still contains files"
;       -> deletion was attempted but files were locked.
;
; KEEP THIS FILE ASCII-ONLY: makensis reads .nsh without a BOM as the system
; codepage, so non-ASCII text would end up as mojibake (or break the build).
;
; Pure observation otherwise: customRemoveFiles below replicates the default
; file-removal block of uninstaller.nsh one-to-one (atomic rename dance for
; --updated runs + RMDir /r), only adding log lines around it.

; ---------------------------------------------------------------------------
; EA_LOG <line>: append one timestamped line to the trace log.
; Uses only plain NSIS instructions so the same macro body compiles in both
; installer and uninstaller contexts. Preserves every register it touches.
; ---------------------------------------------------------------------------

!macro EA_LOG _LINE
  Push $0
  Push $2
  Push $3
  Push $4
  Push $5
  Push $6
  Push $7
  Push $8
  Push $9
  Push $R7
  Push $R8
  ; Do NOT test ${Errors} here: CreateDirectory sets the sticky error flag
  ; when the directory already exists and NSIS never clears it on success.
  ; FileOpen leaves the handle empty on failure - test the handle instead.
  CreateDirectory "$PROFILE\.everyagent\logs"
  FileOpen $0 "$PROFILE\.everyagent\logs\uninstall.log" a
  ${If} $0 != ""
    ; NSIS 3.0.4.1 "append" mode opens WITHOUT truncating but with the write
    ; pointer at 0 - without this explicit seek every run would overwrite the
    ; previous log from the start (proven with a minimal makensis harness).
    FileSeek $0 0 END
    ; Save the message BEFORE the timestamp code runs: it clobbers $R7/$9 and
    ; callers commonly log a value they have just read into $R7.
    StrCpy $R8 "${_LINE}"
    System::Alloc 16
    Pop $2
    ${If} $2 == 0
      FileWrite $0 "$R8$\r$\n"
    ${Else}
      System::Call 'kernel32::GetLocalTime(i r2)'
      System::Call '*$2(&i2 .r3, &i2 .r4, &i2 ., &i2 .r5, &i2 .r6, &i2 .r7, &i2 .r8, &i2 .r9)'
      System::Free $2
      IntFmt $R7 "%04u" $3
      StrCpy $3 $R7
      IntFmt $R7 "%02u" $4
      StrCpy $4 $R7
      IntFmt $R7 "%02u" $5
      StrCpy $5 $R7
      IntFmt $R7 "%02u" $6
      StrCpy $6 $R7
      IntFmt $R7 "%02u" $7
      StrCpy $7 $R7
      IntFmt $R7 "%02u" $8
      StrCpy $8 $R7
      IntFmt $R7 "%03u" $9
      StrCpy $9 $R7
      StrCpy $R7 "$3-$4-$5 $6:$7:$8.$9 | "
      FileWrite $0 "$R7$R8$\r$\n"
    ${EndIf}
    FileClose $0
  ${EndIf}
  Pop $R8
  Pop $R7
  Pop $9
  Pop $8
  Pop $7
  Pop $6
  Pop $5
  Pop $4
  Pop $3
  Pop $2
  Pop $0
!macroend

; ---------------------------------------------------------------------------
; Installer hooks
; ---------------------------------------------------------------------------

; End of installer .onInit (after initMultiUser resolved $INSTDIR).
!macro customInit
  !insertmacro EA_LOG "===== installer .onInit done | INSTDIR=[$INSTDIR] installMode=[$installMode] ====="
  ; Elevation awareness: an ELEVATED install (user right-clicks "run as
  ; administrator", or UAC from a machine-wide dir) writes admin-owned
  ; files/shortcuts/registry that a later NON-elevated per-user uninstaller
  ; cannot delete. Log the account type so install logs reveal that path.
  Push $0
  UserInfo::GetAccountType
  Pop $0
  !insertmacro EA_LOG "  installer account type=[$0] (Admin = elevated install, admin-owned files)"
  Pop $0
!macroend

; Silent old-version uninstall result (runs when reinstalling over an
; existing registration; $R0 = exit code of the old uninstaller, Errors set
; when it could not be launched). Faithful replica of the default check in
; installUtil.nsh handleUninstallResult, plus logging.
!macro customUnInstallCheck
  ${If} ${Errors}
    !insertmacro EA_LOG "old-version uninstaller could NOT be launched (Errors set)"
    DetailPrint `Uninstall was not successful. Not able to launch uninstaller!`
  ${ElseIf} $R0 == 0
    !insertmacro EA_LOG "old-version silent uninstall exited 0"
  ${Else}
    !insertmacro EA_LOG "old-version silent uninstall FAILED exit=[$R0]"
    MessageBox MB_OK|MB_ICONEXCLAMATION "$(uninstallFailed): $R0"
    DetailPrint `Uninstall was not successful. Uninstaller error code: $R0.`
    SetErrorLevel 2
    Quit
  ${EndIf}
!macroend

; End of the install section (files + registry + shortcuts done).
!macro customInstall
  !insertmacro EA_LOG "===== installer section done | INSTDIR=[$INSTDIR] ====="
  Push $R7
  ReadRegStr $R7 SHELL_CONTEXT "${INSTALL_REGISTRY_KEY}" InstallLocation
  !insertmacro EA_LOG "  registry InstallLocation now=[$R7]"
  Pop $R7
  ${If} ${FileExists} "$DESKTOP\${SHORTCUT_NAME}.lnk"
    !insertmacro EA_LOG "  desktop shortcut exists"
  ${Else}
    !insertmacro EA_LOG "  desktop shortcut MISSING"
  ${EndIf}
  ${If} ${FileExists} "$SMPROGRAMS\${SHORTCUT_NAME}.lnk"
    !insertmacro EA_LOG "  start-menu shortcut exists"
  ${Else}
    !insertmacro EA_LOG "  start-menu shortcut MISSING"
  ${EndIf}
!macroend

; ---------------------------------------------------------------------------
; Shared hook: per-user / per-machine selection page (installer AND
; uninstaller). This is the first runtime signal that the wizard pages are
; actually being reached - and dumps the registry-derived mode state that
; decides which installation directory the uninstaller will target.
; ---------------------------------------------------------------------------
!macro customInstallmode
  !insertmacro EA_LOG "install-mode page reached | installMode=[$installMode] INSTDIR=[$INSTDIR] hasPerUser=[$hasPerUserInstallation] hasPerMachine=[$hasPerMachineInstallation] perUserFolder=[$perUserInstallationFolder] perMachineFolder=[$perMachineInstallationFolder]"
!macroend

; ---------------------------------------------------------------------------
; Uninstaller hooks
; ---------------------------------------------------------------------------

; End of un.onInit (after check64BitAndSetRegView + initMultiUser). Rich
; state dump: this is the anchor line - if it is missing after an uninstall
; attempt, the process died before init finished.
!macro customUnInit
  !insertmacro EA_LOG "===== uninstaller un.onInit done | INSTDIR=[$INSTDIR] installMode=[$installMode] ====="
  ${If} ${Silent}
    !insertmacro EA_LOG "  run mode=SILENT (/S)"
  ${Else}
    !insertmacro EA_LOG "  run mode=GUI wizard"
  ${EndIf}
  Push $R7
  ${GetParameters} $R7
  !insertmacro EA_LOG "  raw parameters=[$R7]"
  Pop $R7
  ReadRegStr $7 HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation
  !insertmacro EA_LOG "  HKCU ${APP_GUID}\InstallLocation=[$7]"
  ReadRegStr $7 HKCU "${UNINSTALL_REGISTRY_KEY}" UninstallString
  !insertmacro EA_LOG "  HKCU UninstallString=[$7]"
  ReadRegStr $7 HKLM "${INSTALL_REGISTRY_KEY}" InstallLocation
  !insertmacro EA_LOG "  HKLM InstallLocation=[$7] (empty = no per-machine record)"
  !insertmacro EA_LOG "  perUser=[$perUserInstallationFolder] hasPerUser=[$hasPerUserInstallation] perMachine=[$perMachineInstallationFolder] hasPerMachine=[$hasPerMachineInstallation]"
  Pop $7

  ; ---- self-elevation guard -------------------------------------------
  ; Known failure mode: install done "as administrator" -> tree owned by
  ; Administrators; later the per-user uninstaller runs NON-elevated and
  ; every delete fails silently (RMDir error, shortcuts + registry kept)
  ; while it still exits 0. Probe real delete rights inside INSTDIR; if
  ; missing, relaunch ourselves elevated once and quit. /eaNoElevate guards
  ; against loops; silent (update) runs are never elevated from here.
  ${If} ${FileExists} "$INSTDIR"
  ${AndIfNot} ${Silent}
    ClearErrors
    FileOpen $0 "$INSTDIR\~ea-delprobe.tmp" w
    ${If} ${Errors}
      !insertmacro EA_LOG "  delete-probe: cannot even CREATE in INSTDIR (create-denied)"
    ${Else}
      FileClose $0
      Delete "$INSTDIR\~ea-delprobe.tmp"
      ${If} ${FileExists} "$INSTDIR\~ea-delprobe.tmp"
        Push $8
        Push $9
        ${GetParameters} $8
        ClearErrors
        ${GetOptions} $8 "/eaNoElevate" $9
        ${If} ${Errors}
          !insertmacro EA_LOG "  delete-probe FAILED (no delete right in INSTDIR) -> relaunching ELEVATED"
          ExecShell "runas" '"$INSTDIR\${UNINSTALL_FILENAME}"' '"$8" /eaNoElevate'
          ${If} ${Errors}
            !insertmacro EA_LOG "  ELEVATION DECLINED/FAILED - continuing without it (deletes will likely fail)"
          ${Else}
            !insertmacro EA_LOG "  elevated relaunch handed off - quitting this instance"
            Pop $9
            Pop $8
            Quit
          ${EndIf}
        ${Else}
          !insertmacro EA_LOG "  delete-probe FAILED even after elevated retry (interceptor?) - continuing"
        ${EndIf}
        Pop $9
        Pop $8
      ${Else}
        !insertmacro EA_LOG "  delete-probe OK (we have delete rights in INSTDIR)"
      ${EndIf}
    ${EndIf}
  ${EndIf}
!macroend

; Replaces the default file-removal block of un.install. Replicates
; uninstaller.nsh exactly (isUpdated atomic-rename dance + RMDir /r) and
; logs what happened. On failure, additionally walks INSTDIR item by item
; (first EA_DEL_LOG_MAX failures logged individually) to separate
; access-denied from locked-file situations. Do NOT "simplify" the
; isUpdated branch away: silent update runs (uninstallOldVersion) depend
; on the atomic rename semantics.
!define EA_DEL_LOG_MAX 30
!macro customRemoveFiles
  !insertmacro EA_LOG "un.install section: removing files | INSTDIR=[$INSTDIR]"
  ; ---- stop backend java processes running from INSTDIR -------------------
  ; hub/worker live under $INSTDIR\resources\jre\bin\javaw.exe and hold open
  ; jar handles (and, in versions before the cwd fix, the resources dir as
  ; their CWD). While they live, RMDir silently fails on those entries.
  ; They survive app exit when: "退出桌面" keeps the worker by design, the
  ; worker was started externally (start-backend.bat / task scheduler), or
  ; the app itself was hard-killed (never runs stopAll). Kill anything whose
  ; image lives under INSTDIR - own user when non-elevated, every user when
  ; elevated. Scope is safe: dev/other installs have different paths.
  FileOpen $0 "$TEMP\ea-stop-backend.ps1" w
  FileWrite $0 "param($$inst, $$log)$\r$\n"
  FileWrite $0 "$$procs = Get-Process java,javaw -ErrorAction SilentlyContinue | Where-Object { try { $$_.Path -like ($$inst + '*') } catch { $$false } }$\r$\n"
  FileWrite $0 "foreach ($$p in $$procs) {$\r$\n"
  FileWrite $0 "  Add-Content -Path $$log -Value ('  backend process killed: pid=' + $$p.Id + ' ' + $$p.Path)$\r$\n"
  FileWrite $0 "  Stop-Process -Id $$p.Id -Force -ErrorAction SilentlyContinue$\r$\n"
  FileWrite $0 "}$\r$\n"
  FileClose $0
  nsExec::ExecToLog 'powershell -NoProfile -ExecutionPolicy Bypass -File "$TEMP\ea-stop-backend.ps1" "$INSTDIR" "$PROFILE\.everyagent\logs\uninstall.log"'
  Pop $0
  Delete "$TEMP\ea-stop-backend.ps1"
  ; give the killed processes a moment to release file/dir handles
  Sleep 500
  ${if} ${isUpdated}
    !insertmacro EA_LOG "  isUpdated=1 -> atomic move to old-install"
    CreateDirectory "$PLUGINSDIR\old-install"
    Push ""
    Call un.atomicRMDir
    Pop $R0
    ${if} $R0 != 0
      !insertmacro EA_LOG "  atomic move FAILED on [$R0] -> restoring + abort"
      DetailPrint "File is busy, aborting: $R0"
      Push ""
      Call un.restoreFiles
      Pop $R0
      Abort `Can't rename "$INSTDIR" to "$PLUGINSDIR\old-install".`
    ${endif}
  ${endif}
  ClearErrors
  RMDir /r $INSTDIR
  ${If} ${Errors}
    !insertmacro EA_LOG "  RMDir /r reported an error (locked or in-use items)"
  ${Else}
    !insertmacro EA_LOG "  RMDir /r completed without error flag"
  ${EndIf}
  ${If} ${FileExists} "$INSTDIR\*.*"
    !insertmacro EA_LOG "  INSTDIR STILL CONTAINS FILES after removal"
    ; ---- forensic item-by-item walk -------------------------------
    Push $0
    Push $1
    Push $2
    Push $3
    Push $4
    StrCpy $3 0
    StrCpy $4 0
    FindFirst $1 $2 "$INSTDIR\*.*"
    ${DoWhile} $2 != ""
      ${If} $2 != "."
      ${AndIf} $2 != ".."
        IntOp $4 $4 + 1
        ${If} $3 < ${EA_DEL_LOG_MAX}
          ClearErrors
          ${If} ${FileExists} "$INSTDIR\$2\*.*"
            RMDir "$INSTDIR\$2"
          ${Else}
            Delete "$INSTDIR\$2"
          ${EndIf}
          ${If} ${Errors}
            IntOp $3 $3 + 1
            !insertmacro EA_LOG "  delete FAILED: [$2] (error flag set)"
          ${EndIf}
        ${EndIf}
      ${EndIf}
      FindNext $1 $2
    ${Loop}
    FindClose $1
    !insertmacro EA_LOG "  forensic walk: top-level items=[$4] delete-failures-logged=[$3] (capped at ${EA_DEL_LOG_MAX})"
    Pop $4
    Pop $3
    Pop $2
    Pop $1
    Pop $0
  ${Else}
    !insertmacro EA_LOG "  INSTDIR is gone/empty after removal"
  ${EndIf}
!macroend

; End of the uninstall section (after shortcut + registry cleanup): dump the
; post-state so one log answers "what actually got removed".
!macro customUnInstall
  ${If} ${FileExists} "$INSTDIR\*.*"
    !insertmacro EA_LOG "un.install section done | INSTDIR still contains files"
  ${Else}
    !insertmacro EA_LOG "un.install section done | INSTDIR removed"
  ${EndIf}
  ${If} ${FileExists} "$DESKTOP\${SHORTCUT_NAME}.lnk"
    !insertmacro EA_LOG "  desktop shortcut STILL EXISTS"
  ${Else}
    !insertmacro EA_LOG "  desktop shortcut removed"
  ${EndIf}
  ${If} ${FileExists} "$SMPROGRAMS\${SHORTCUT_NAME}.lnk"
    !insertmacro EA_LOG "  start-menu shortcut STILL EXISTS"
  ${Else}
    !insertmacro EA_LOG "  start-menu shortcut removed"
  ${EndIf}
  Push $R7
  ReadRegStr $R7 SHELL_CONTEXT "${INSTALL_REGISTRY_KEY}" InstallLocation
  !insertmacro EA_LOG "  registry InstallLocation now=[$R7] (empty = cleaned)"
  ReadRegStr $R7 SHELL_CONTEXT "${UNINSTALL_REGISTRY_KEY}" UninstallString
  ${If} $R7 == ""
    !insertmacro EA_LOG "  registry ARP entry removed"
  ${Else}
    !insertmacro EA_LOG "  registry ARP entry STILL EXISTS: [$R7]"
  ${EndIf}
  Pop $R7
!macroend
