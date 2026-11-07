@echo off

setlocal
cd /d "%~dp0"

if defined CLEAN (
    echo Running gradlew clean...
    call gradlew.bat clean
    if errorlevel 1 goto :fail
)

echo Running gradlew dist...
call gradlew.bat dist %*
if errorlevel 1 goto :fail

echo.
echo ============================================
echo Library pack built under %CD%\dist\
echo ============================================
echo.
echo Contents:
dir /b dist
endlocal
exit /b 0

:fail
echo.
echo Build failed. See the gradle output above.
endlocal
exit /b 1
