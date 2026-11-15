@echo off
setlocal
cd /d "%~dp0"
if not exist "data" mkdir "data"
"runtime\bin\java.exe" -Dfile.encoding=UTF-8 "-Dtvnext.app.dir=%~dp0." "-Dtvnext.data.dir=%~dp0data" "-Djna.library.path=%~dp0." -cp "lib/*" com.tvnext.app.MainKt > "data\startup.log" 2>&1
if errorlevel 1 (
  echo TVNext could not start. See data\startup.log
  pause
)
