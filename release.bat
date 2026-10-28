@echo off
rem Publishes a release of Pitchstone on GitHub (github.com/inhahe/Pitchstone/releases): the
rem signed release APK, and R8's mapping file for reading that build's crash traces, tagged
rem v<appVersion> (gradle.properties), once the tests pass. Publishes nothing if that version is
rem already released or not above the last release: raise it first (CLAUDE.md).
rem
rem   release.bat            test, build and publish
rem   release.bat --dry-run  test, build and show the release notes; publish nothing
setlocal EnableExtensions

set "REPO=inhahe/Pitchstone"
set "DRY="
if /i "%~1"=="--dry-run" (
    set "DRY=1"
) else if not "%~1"=="" (
    goto :usage
)
if not "%~2"=="" goto :usage

cd /d "%~dp0"

set "VERSION="
for /f "tokens=1,* delims==" %%a in (gradle.properties) do if "%%a"=="appVersion" set "VERSION=%%b"
call :number "%VERSION%" CODE
if errorlevel 1 (
    echo appVersion in gradle.properties is "%VERSION%": it must be major.minor.patch, minor and
    echo patch below 100, without leading zeros.
    exit /b 1
)
set "TAG=v%VERSION%"

where gh >nul 2>&1
if errorlevel 1 (
    echo The GitHub CLI ^(gh^) is needed: https://cli.github.com
    exit /b 1
)
gh auth status --hostname github.com >nul 2>&1
if errorlevel 1 (
    echo gh is not logged in to github.com: run  gh auth login
    exit /b 1
)

rem What is released is main as GitHub has it: nothing uncommitted, nothing left out, nothing unpushed.
set "BRANCH="
for /f %%b in ('git symbolic-ref --short HEAD 2^>nul') do set "BRANCH=%%b"
if not "%BRANCH%"=="main" (
    echo Releases are made from main, and this is "%BRANCH%". Nothing was published.
    exit /b 1
)
git diff --quiet HEAD --
if errorlevel 1 (
    echo There are uncommitted changes: commit and push them first. Nothing was published.
    exit /b 1
)
set "LEFT_OUT="
for /f "delims=" %%f in ('git ls-files --others --exclude-standard -- app editor gradle build.gradle.kts settings.gradle.kts gradle.properties') do set "LEFT_OUT=%%f"
if defined LEFT_OUT (
    echo %LEFT_OUT% is not in git, but the build may use it: add it, or ignore it. Nothing was published.
    exit /b 1
)
git fetch --quiet --tags origin
if errorlevel 1 (
    echo Could not reach GitHub. Nothing was published.
    exit /b 1
)
for /f %%h in ('git rev-parse HEAD') do set "HEAD=%%h"
set "MAIN="
for /f %%h in ('git rev-parse origin/main 2^>nul') do set "MAIN=%%h"
if not "%HEAD%"=="%MAIN%" (
    echo This commit is not the main GitHub has: push ^(or pull^) first. Nothing was published.
    exit /b 1
)

rem Once per version, and each above the one before.
gh release view "%TAG%" --repo %REPO% >nul 2>&1
if not errorlevel 1 (
    echo Version %VERSION% is already released: https://github.com/%REPO%/releases/tag/%TAG%
    echo Nothing was published. Raise appVersion in gradle.properties first ^(see CLAUDE.md^).
    exit /b 1
)
git rev-parse -q --verify "refs/tags/%TAG%" >nul
if not errorlevel 1 (
    echo The tag %TAG% exists without a release ^(left by a deleted one?^): raise appVersion, or
    echo delete the tag on GitHub and here. Nothing was published.
    exit /b 1
)
set "PREVIOUS="
for /f %%t in ('git describe --tags --abbrev^=0 --match "v[0-9]*" HEAD 2^>nul') do set "PREVIOUS=%%t"
if not defined PREVIOUS goto :new_version
call :number "%PREVIOUS:~1%" PREVIOUS_CODE
if errorlevel 1 goto :new_version
if %CODE% GTR %PREVIOUS_CODE% goto :new_version
echo Version %VERSION% is not above the last release, %PREVIOUS%: raise appVersion in gradle.properties.
echo Nothing was published.
exit /b 1
:new_version

echo Testing Pitchstone %VERSION%...
pushd editor
call npm test
if errorlevel 1 goto :tests_failed
call npm run test:e2e
if errorlevel 1 goto :tests_failed
popd

echo Building Pitchstone %VERSION%, after the app's tests...
call "%~dp0build-release.bat" testDebugUnitTest
if errorlevel 1 (
    echo Nothing was published.
    exit /b 1
)

set "MINSDK="
for /f "tokens=2 delims== " %%m in ('findstr /r /c:"^ *minSdk *=" app\build.gradle.kts') do set "MINSDK=%%m"
set "ANDROID=Android API level %MINSDK%"
for %%p in (26:8.0 27:8.1 28:9 29:10 30:11 31:12 32:12L 33:13 34:14 35:15 36:16) do (
    for /f "tokens=1,2 delims=:" %%a in ("%%p") do if "%%a"=="%MINSDK%" set "ANDROID=Android %%b"
)

rem From here on, every way out goes through :finish, which removes OUT.
set "RESULT=1"
set "OUT=%TEMP%\pitchstone-release-%VERSION%"
if exist "%OUT%" rd /s /q "%OUT%"
mkdir "%OUT%" || goto :finish
copy /y "app\build\outputs\apk\release\app-release.apk" "%OUT%\Pitchstone-%VERSION%.apk" >nul || goto :finish
set ASSETS="%OUT%\Pitchstone-%VERSION%.apk"
set "MAPPING=app\build\outputs\mapping\release\mapping.txt"
if exist "%MAPPING%" copy /y "%MAPPING%" "%OUT%\Pitchstone-%VERSION%-mapping.txt" >nul || goto :finish
if exist "%MAPPING%" set ASSETS=%ASSETS% "%OUT%\Pitchstone-%VERSION%-mapping.txt"

rem The release notes: how to install, and what changed since the last release.
set "NOTES=%OUT%\notes.md"
> "%NOTES%" echo Pitchstone %VERSION%, markdown notes for %ANDROID% and later.
>> "%NOTES%" echo.
>> "%NOTES%" echo **Install:** on the phone, download `Pitchstone-%VERSION%.apk` and open it; allow your browser or file manager to install apps when asked. A newer version installs over an older one, keeping your notes.
>> "%NOTES%" echo.
if defined PREVIOUS (
    >> "%NOTES%" echo **Changes since %PREVIOUS%:**
    >> "%NOTES%" echo.
    git log --no-merges --pretty=format:"- %%s" %PREVIOUS%..HEAD >> "%NOTES%"
) else (
    >> "%NOTES%" echo The first release.
)

if defined DRY (
    echo.
    echo Dry run: would publish %TAG% at %HEAD% with %ASSETS%
    echo and these notes:
    echo.
    type "%NOTES%"
    echo.
    set "RESULT=0"
    goto :finish
)

gh release create "%TAG%" %ASSETS% --repo %REPO% --target %HEAD% --title "Pitchstone %VERSION%" --notes-file "%NOTES%"
if errorlevel 1 (
    echo Publishing failed.
    goto :finish
)
set "RESULT=0"
git fetch --quiet --tags origin
echo.
echo Released: https://github.com/%REPO%/releases/tag/%TAG%

:finish
if exist "%OUT%" rd /s /q "%OUT%"
if not "%RESULT%"=="0" echo Nothing was published.
exit /b %RESULT%

:tests_failed
popd
echo The tests failed. Nothing was published.
exit /b 1

:usage
echo Usage: release.bat [--dry-run]
echo Tests, builds and publishes the version in gradle.properties as a GitHub release; with
echo --dry-run, it publishes nothing.
exit /b 1

rem :number "<major.minor.patch>" <variable> sets the variable to the version's number,
rem major*10000 + minor*100 + patch, as versionCode in app\build.gradle.kts; fails for text that
rem is not such a version (minor or patch from 100, or a leading zero, which set /a reads as octal).
:number
echo %~1| findstr /r /x "[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*" >nul || exit /b 1
echo .%~1| findstr /r "\.0[0-9]" >nul && exit /b 1
for /f "tokens=1-3 delims=." %%a in ("%~1") do (
    if %%b GEQ 100 exit /b 1
    if %%c GEQ 100 exit /b 1
    set /a "%~2=%%a*10000+%%b*100+%%c"
)
exit /b 0
