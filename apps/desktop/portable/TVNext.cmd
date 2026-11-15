@echo off
setlocal
cd /d "%~dp0"
if not exist "runtime\bin\javaw.exe" (
  echo Java runtime is missing. Extract the entire TVNext ZIP before launching.
  pause
  exit /b 1
)
if not exist "libmpv-2.dll" (
  echo Playback runtime is missing. Extract the entire TVNext ZIP.
  pause
  exit /b 1
)
if not exist "data" mkdir "data"
start "TVNext" "runtime\bin\javaw.exe" -Dfile.encoding=UTF-8 "-Dtvnext.app.dir=%~dp0." "-Dtvnext.data.dir=%~dp0data" "-Djna.library.path=%~dp0." -XX:ErrorFile=data/hs_err_pid%%p.log -cp "lib/*" com.tvnext.app.MainKt
