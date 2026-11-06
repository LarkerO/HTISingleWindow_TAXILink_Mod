@echo off
setlocal DisableDelayedExpansion
set "newModToken="
set "newDebugToken="

if not exist "%~dp0vendor\autoload.php" (
    where.exe composer >nul 2>&1
    if errorlevel 1 (
        echo PHP dependencies are missing. Install Composer, then run init.bat again. 1>&2
        exit /b 1
    )
    pushd "%~dp0" || exit /b 1
    call composer install --no-interaction --prefer-dist
    if errorlevel 1 (
        popd
        exit /b 1
    )
    popd
    if not exist "%~dp0vendor\autoload.php" (
        echo Composer did not create vendor\autoload.php. 1>&2
        exit /b 1
    )
)

if "%TAXILINK_ALLOW_LEGACY_TRANSPORT%"=="1" if not defined TAXILINK_TOKEN (
    call :loadToken "%~dp0.env.taxilink-mod" newModToken
    if errorlevel 1 exit /b 1
)
if not defined TAXILINK_DEBUG_TOKEN (
    call :loadToken "%~dp0.env.taxilink-debug" newDebugToken
    if errorlevel 1 exit /b 1
)

endlocal & if not "%newModToken%"=="" set "TAXILINK_TOKEN=%newModToken%" & if not "%newDebugToken%"=="" set "TAXILINK_DEBUG_TOKEN=%newDebugToken%"
echo TAXILink tokens are ready in this CMD session.
echo Generated token files, if needed, are saved next to init.bat.
exit /b 0

:loadToken
set "TAXILINK_INIT_FILE=%~1"
for /f "delims=" %%T in ('powershell.exe -NoProfile -NonInteractive -Command "$ErrorActionPreference='Stop'; $path=$env:TAXILINK_INIT_FILE; if (Test-Path -LiteralPath $path) { $token=[IO.File]::ReadAllText($path).Trim(); if ($token -cnotmatch '^[0-9a-f]{64}$') { throw 'Invalid saved TAXILink token' } } else { $bytes=New-Object byte[] 32; $rng=[Security.Cryptography.RandomNumberGenerator]::Create(); try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }; $token=[BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant(); $file=[IO.File]::Open($path,[IO.FileMode]::CreateNew); try { $encoded=[Text.Encoding]::ASCII.GetBytes($token); $file.Write($encoded,0,$encoded.Length) } finally { $file.Dispose() } }; $token"') do set "%~2=%%T"
if not defined %~2 (
    echo Could not initialize "%~1". 1>&2
    exit /b 1
)
exit /b 0
