@echo off
rem Launch the OSRS Map Editor (standalone runnable jar).
setlocal
cd /d "%~dp0"
set "JAVACMD=java"
if defined JAVA_HOME set "JAVACMD=%JAVA_HOME%\bin\java"
if not exist "target\osrs-map-editor.jar" (
  echo Jar not built yet. Build it first with:  build-editor.bat
  pause
  goto :eof
)
rem No --cache passed: the editor shows a folder chooser. Pass args to override,
rem e.g.  run-editor.bat --cache "C:\path\to\cache"
"%JAVACMD%" -jar "target\osrs-map-editor.jar" %*
if errorlevel 1 pause
