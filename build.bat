@echo off
rem ============================================================
rem  Shine - build script (Minecraft 1.21.10 / Fabric)
rem
rem  Usage:
rem    build.bat              Build the mod jar
rem    build.bat clean        Clean first, then build
rem    build.bat --help       Show this help
rem
rem  Any other arguments are passed straight through to Gradle,
rem  e.g.  build.bat --offline
rem
rem  Requires JDK 21 or newer. Gradle itself is downloaded
rem  automatically by the wrapper on the first run.
rem ============================================================

setlocal EnableDelayedExpansion

rem Always work from the directory this script lives in.
cd /d "%~dp0"

set "CLEAN_TASK="
set "EXTRA_ARGS="

:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="clean"   (set "CLEAN_TASK=clean" & shift & goto parse_args)
if /i "%~1"=="--clean" (set "CLEAN_TASK=clean" & shift & goto parse_args)
if /i "%~1"=="-h"      goto usage
if /i "%~1"=="--help"  goto usage
if /i "%~1"=="/?"      goto usage
set "EXTRA_ARGS=!EXTRA_ARGS! %1"
shift
goto parse_args
:args_done

if not exist "gradlew.bat" (
    echo [ERROR] gradlew.bat not found.
    echo         Run this script from inside the Shine repository.
    goto fail
)

rem ---------- Locate a Java runtime ----------
set "JAVA_EXE=java.exe"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] No Java runtime found.
    echo         Install JDK 21 ^(or newer^) and either add it to PATH
    echo         or point JAVA_HOME at it.
    echo         Download: https://adoptium.net/temurin/releases/?version=21
    goto fail
)

rem ---------- Check the Java version is at least 21 ----------
set "JAVA_VER="
for /f "tokens=3" %%v in ('"%JAVA_EXE%" -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined JAVA_VER set "JAVA_VER=%%~v"
)

set "JAVA_MAJOR="
for /f "tokens=1,2 delims=." %%a in ("%JAVA_VER%") do (
    if "%%a"=="1" (set "JAVA_MAJOR=%%b") else (set "JAVA_MAJOR=%%a")
)

rem Strip any early-access suffix such as 21-ea
for /f "tokens=1 delims=-+" %%a in ("%JAVA_MAJOR%") do set "JAVA_MAJOR=%%a"

if not defined JAVA_MAJOR (
    echo [WARN ] Could not determine the Java version, continuing anyway.
) else (
    echo [INFO ] Using Java %JAVA_VER%
    set /a JAVA_MAJOR_NUM=JAVA_MAJOR 2>nul
    if !JAVA_MAJOR_NUM! LSS 21 (
        echo [ERROR] Java 21 or newer is required, but Java %JAVA_VER% was found.
        echo         Set JAVA_HOME to a JDK 21+ installation and try again.
        goto fail
    )
)

rem ---------- Build ----------
echo.
if defined CLEAN_TASK (
    echo [INFO ] Building Shine ^(clean build^)...
) else (
    echo [INFO ] Building Shine...
)
echo [INFO ] The first run downloads Gradle, Minecraft and the mod
echo         dependencies, so it may take several minutes.
echo.

call gradlew.bat %CLEAN_TASK% build%EXTRA_ARGS%
if errorlevel 1 (
    echo.
    echo [ERROR] The Gradle build failed. See the output above for details.
    goto fail
)

rem ---------- Report the resulting jar ----------
set "MOD_JAR="
for %%f in ("build\libs\*.jar") do (
    set "NAME=%%~nf"
    echo !NAME! | findstr /i /c:"-sources" >nul
    if errorlevel 1 (
        echo !NAME! | findstr /i /c:"-dev" >nul
        if errorlevel 1 set "MOD_JAR=%%~ff"
    )
)

echo.
echo ============================================================
if defined MOD_JAR (
    echo  BUILD SUCCESSFUL
    echo.
    echo  Mod jar:
    echo    !MOD_JAR!
    echo.
    echo  Copy it into the 'mods' folder of a Fabric 1.21.10
    echo  installation to use it.
) else (
    echo  BUILD SUCCESSFUL
    echo.
    echo  No jar was found in build\libs - check the Gradle output.
)
echo ============================================================
echo.

endlocal
exit /b 0

:usage
echo.
echo Shine build script
echo.
echo   build.bat              Build the mod jar
echo   build.bat clean        Clean first, then build
echo   build.bat --help       Show this help
echo.
echo Any other arguments are forwarded to Gradle, e.g. build.bat --offline
echo Requires JDK 21 or newer.
echo.
endlocal
exit /b 0

:fail
echo.
endlocal
exit /b 1
