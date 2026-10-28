@echo off
rem Builds the signed release APK, app\build\outputs\apk\release\app-release.apk, for
rem install-on-phone.bat and release.bat; Gradle tasks given as arguments run first (release.bat
rem passes the tests). Exits non-zero, saying why, if there is no signed APK to show for it.
setlocal

rem The JDK Android Studio downloaded, unless JAVA_HOME names one (else Gradle uses java on PATH).
if not defined JAVA_HOME if exist "%USERPROFILE%\.jdks\jdk-21.0.12.1+1\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-21.0.12.1+1"

cd /d "%~dp0"

if not exist keystore.properties (
    echo keystore.properties is missing, so the APK would be unsigned ^(see README.md, Building^).
    exit /b 1
)

rem By its full path: cmd may be set not to look in the current folder.
call "%~dp0gradlew.bat" %* assembleRelease
if errorlevel 1 (
    echo The build failed.
    exit /b 1
)
if not exist "app\build\outputs\apk\release\app-release.apk" (
    echo The build made no signed APK.
    exit /b 1
)
