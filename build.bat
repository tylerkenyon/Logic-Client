@echo off
setlocal
pushd "%~dp0"
where pwsh.exe >nul 2>nul
if errorlevel 1 (
    powershell.exe -NoLogo -NoProfile -File "%~dp0build-injector.ps1" %*
) else (
    pwsh.exe -NoLogo -NoProfile -File "%~dp0build-injector.ps1" %*
)
set "BUILD_RESULT=%ERRORLEVEL%"
popd
if not "%BUILD_RESULT%"=="0" (
    echo.
    echo Build failed. See the error above.
    exit /b %BUILD_RESULT%
)
echo.
echo Build complete. See the output directory printed above.
exit /b 0
