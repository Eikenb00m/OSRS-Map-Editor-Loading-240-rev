@echo off
rem Build the standalone runnable jar into target\osrs-map-editor.jar
setlocal
cd /d "%~dp0"
where mvn >nul 2>&1
if %errorlevel%==0 (
  call mvn -DskipTests package
) else (
  echo Maven ^(mvn^) not found on PATH. Trying the bundled wrapper...
  call mvnw.cmd -DskipTests package
)
if errorlevel 1 (
  echo.
  echo BUILD FAILED
  pause
) else (
  echo.
  echo Built:  target\osrs-map-editor.jar
  pause
)
