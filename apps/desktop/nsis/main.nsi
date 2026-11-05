; TVNext - Windows installer (NSIS 3.x)
; Per-user install into %LOCALAPPDATA%\Programs\TVNext; Russian UI with an
; install-folder page (path + Browse) and a "Delete user data" checkbox on
; uninstall that wipes %USERPROFILE%\.tvnext.

Unicode true

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "nsDialogs.nsh"

; Installer / uninstaller icon.
!define MUI_ICON "..\icon.ico"
!define MUI_UNICON "..\icon.ico"

!define PRODUCT_NAME "TVNext"
!define PRODUCT_VERSION "1.0.0"
!define PRODUCT_UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\TVNext"

Name "${PRODUCT_NAME}"
OutFile "TVNext-${PRODUCT_VERSION}.exe"
InstallDir "$LOCALAPPDATA\Programs\TVNext"
RequestExecutionLevel user

; --------------------------------------------------------------------------
; Install pages: Welcome -> License -> Directory (path + Browse) -> Install -> Finish
; --------------------------------------------------------------------------
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "..\LICENSE.txt"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

; --------------------------------------------------------------------------
; Uninstall pages: Welcome -> custom confirm (Delete user data checkbox) -> Install -> Finish
; --------------------------------------------------------------------------
!insertmacro MUI_UNPAGE_WELCOME
UninstPage custom un.ConfirmPage un.ConfirmPageLeave
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

!insertmacro MUI_LANGUAGE "Russian"
!insertmacro MUI_LANGUAGE "English"

; Set to "1" when the user ticks the checkbox on the uninstall confirm page.
Var DELETE_USER_DATA
Var DELETE_CHECKBOX

; --------------------------------------------------------------------------
; Install section
; --------------------------------------------------------------------------
Section "Install"
  SetOutPath "$INSTDIR"

  ; jpackage app image (TVNext.exe + app/ + runtime/).
  File /r "..\build\compose\binaries\main\app\TVNext\*"

  ; libmpv + its shim next to the exe (MpvRuntimeLocator finds them there).
  File "..\app-resources\libmpv-2.dll"
  File "..\app-resources\d3dcompiler_43.dll"

  ; Start-menu + desktop shortcuts.
  CreateDirectory "$SMPROGRAMS\${PRODUCT_NAME}"
  CreateShortcut "$SMPROGRAMS\${PRODUCT_NAME}\${PRODUCT_NAME}.lnk" "$INSTDIR\TVNext.exe"
  CreateShortcut "$DESKTOP\${PRODUCT_NAME}.lnk" "$INSTDIR\TVNext.exe"

  ; Uninstaller + Add/Remove Programs entry.
  WriteUninstaller "$INSTDIR\uninstall.exe"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayName" "${PRODUCT_NAME}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayVersion" "${PRODUCT_VERSION}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "Publisher" "${PRODUCT_NAME}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayIcon" "$INSTDIR\TVNext.exe"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "UninstallString" "$\"$INSTDIR\uninstall.exe$\""
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "QuietUninstallString" "$\"$INSTDIR\uninstall.exe$\" /S"
  WriteRegDWORD HKCU "${PRODUCT_UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${PRODUCT_UNINST_KEY}" "NoRepair" 1
SectionEnd

; --------------------------------------------------------------------------
; Uninstall confirm page: "Delete user data" checkbox
; --------------------------------------------------------------------------
Function un.ConfirmPage
  nsDialogs::Create 1018
  Pop $0
  ${NSD_CreateLabel} 0 8u 100% 30u "Программа ${PRODUCT_NAME} будет удалена с этого компьютера. Если нужно также удалить все пользовательские данные (плейлисты, каналы, EPG, настройки) - отметьте галочку."
  Pop $0
  ${NSD_CreateCheckbox} 0 48u 100% 14u "Удалить все пользовательские данные (плейлисты, каналы, EPG, настройки)"
  Pop $DELETE_CHECKBOX
  nsDialogs::Show
FunctionEnd

Function un.ConfirmPageLeave
  ${NSD_GetState} $DELETE_CHECKBOX $0
  StrCmp $0 "1" 0 +3
  StrCpy $DELETE_USER_DATA "1"
  Return
  StrCpy $DELETE_USER_DATA "0"
FunctionEnd

; --------------------------------------------------------------------------
; Uninstall section
; --------------------------------------------------------------------------
Section "Uninstall"
  ; Remove the installed files (the whole app folder).
  RMDir /r "$INSTDIR"

  ; Shortcuts.
  Delete "$SMPROGRAMS\${PRODUCT_NAME}\${PRODUCT_NAME}.lnk"
  Delete "$DESKTOP\${PRODUCT_NAME}.lnk"
  RMDir "$SMPROGRAMS\${PRODUCT_NAME}"

  ; Optionally wipe all user data (playlists, channels, EPG, settings, cache).
  ${If} $DELETE_USER_DATA == "1"
    RMDir /r "$PROFILE\.tvnext"
  ${EndIf}

  ; Remove the Add/Remove Programs entry.
  DeleteRegKey HKCU "${PRODUCT_UNINST_KEY}"
SectionEnd
