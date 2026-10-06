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
  ReadRegStr $R7 HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation
  !insertmacro EA_LOG "  HKCU ${APP_GUID}\InstallLocation=[$R7]"
  ReadRegStr $R7 HKCU "${UNINSTALL_REGISTRY_KEY}" UninstallString
  !insertmacro EA_LOG "  HKCU UninstallString=[$R7]"
  ReadRegStr $R7 HKLM "${INSTALL_REGISTRY_KEY}" InstallLocation
  !insertmacro EA_LOG "  HKLM InstallLocation=[$R7] (empty = no per-machine record)"
  !insertmacro EA_LOG "  perUser=[$perUserInstallationFolder] hasPerUser=[$hasPerUserInstallation] perMachine=[$perMachineInstallationFolder] hasPerMachine=[$hasPerMachineInstallation]"
  Pop $R7
!macroend

; Replaces the default file-removal block of un.install. Replicates
; uninstaller.nsh exactly (isUpdated atomic-rename dance + RMDir /r) and
; logs what happened. Do NOT "simplify" the isUpdated branch away: silent
; update runs (uninstallOldVersion) depend on the atomic rename semantics.
!macro customRemoveFiles
  !insertmacro EA_LOG "un.install section: removing files | INSTDIR=[$INSTDIR]"
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
  Pop $R7
!macroend
