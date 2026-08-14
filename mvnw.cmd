@echo off
rem Minimal Maven Wrapper launcher (uses .mvn\wrapper\maven-wrapper.jar).
setlocal
set "DIR=%~dp0"
rem Strip trailing backslash so a quoted path doesn't escape its closing quote.
if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java"
"%JAVA_EXE%" -Dmaven.multiModuleProjectDirectory="%DIR%" -classpath "%DIR%\.mvn\wrapper\maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain %*
