@echo off
setlocal
pushd "%~dp0"
call "%~dp0build.bat" -OutputDirectory dist-release %*
if errorlevel 1 goto failed
where pwsh.exe >nul 2>nul
if errorlevel 1 (
    powershell.exe -NoLogo -NoProfile -File "%~dp0scripts\package-release.ps1"
) else (
    pwsh.exe -NoLogo -NoProfile -File "%~dp0scripts\package-release.ps1"
)
if errorlevel 1 goto failed
echo.
echo Release files are ready in dist-release.
popd
exit /b 0
:failed
echo.
echo Release build failed. See the error above.
popd
exit /b 1
