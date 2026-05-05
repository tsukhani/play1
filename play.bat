@echo off
REM Play 1 -- thin wrapper around Gradle (Windows).
REM
REM Mirrors ./play (POSIX shell). See that script's header for design notes.

setlocal enabledelayedexpansion

REM --- Resolve PLAY_HOME from this script's location -----------------------
set "PLAY_HOME=%~dp0"
if "!PLAY_HOME:~-1!"=="\" set "PLAY_HOME=!PLAY_HOME:~0,-1!"

REM --- Banner --------------------------------------------------------------
REM Detect --silent anywhere in args; controls banner only (never reaches Gradle).
set "SILENT=0"
if not "%*"=="" echo %* | findstr /i /c:"--silent" >nul && set "SILENT=1"
if "%SILENT%"=="0" call :logo

REM --- Subcommand ----------------------------------------------------------
:read_cmd
set "CMD=%~1"
if /i "%CMD%"=="--silent" (shift & goto :read_cmd)
if "%CMD%"=="" set "CMD=help"
if not "%CMD%"=="" shift

REM --- Top-level dispatch --------------------------------------------------
if /i "%CMD%"=="help"     goto :usage
if /i "%CMD%"=="--help"   goto :usage
if /i "%CMD%"=="-h"       goto :usage
if /i "%CMD%"=="new"      goto :new

if /i "%CMD%"=="deps"          goto :removed
if /i "%CMD%"=="check"         goto :removed
if /i "%CMD%"=="ant"           goto :removed
if /i "%CMD%"=="install"       goto :removed
if /i "%CMD%"=="list-modules"  goto :removed
if /i "%CMD%"=="build-module"  goto :removed
if /i "%CMD%"=="new-module"    goto :removed
if /i "%CMD%"=="id"            goto :removed

if /i "%CMD%"=="idea"      goto :ide
if /i "%CMD%"=="intellij"  goto :ide
if /i "%CMD%"=="eclipse"   goto :ide
if /i "%CMD%"=="netbeans"  goto :ide

REM --- App-context commands: look up Gradle task name ----------------------
set "TASK="
if /i "%CMD%"=="run"            set "TASK=playRun"
if /i "%CMD%"=="start"          set "TASK=playStart"
if /i "%CMD%"=="stop"           set "TASK=playStop"
if /i "%CMD%"=="restart"        set "TASK=playRestart"
if /i "%CMD%"=="status"         set "TASK=playStatus"
if /i "%CMD%"=="pid"            set "TASK=playPid"
if /i "%CMD%"=="out"            set "TASK=playOut"
if /i "%CMD%"=="test"           set "TASK=playTest"
if /i "%CMD%"=="auto-test"      set "TASK=playAutotest"
if /i "%CMD%"=="clean"          set "TASK=playClean"
if /i "%CMD%"=="precompile"     set "TASK=playPrecompile"
if /i "%CMD%"=="evolutions"     set "TASK=playEvolutions"
if /i "%CMD%"=="classpath"      set "TASK=playClasspath"
if /i "%CMD%"=="modules"        set "TASK=playModulesInfo"
if /i "%CMD%"=="secret"         set "TASK=playSecret"
if /i "%CMD%"=="enable-https"   set "TASK=playEnableHttps"
if /i "%CMD%"=="disable-https"  set "TASK=playDisableHttps"
if /i "%CMD%"=="javadoc"        set "TASK=playJavadoc"
REM `dist` maps to playBundle — 1.12 `play dist` produced a deployment-ready
REM zip, which is what playBundle does in 1.13.x. The source-tree-zip task
REM playDist is reachable only via `gradlew playDist` since users typing
REM `play dist` mean the 1.12 deployment semantics.
if /i "%CMD%"=="dist"           set "TASK=playBundle"
if /i "%CMD%"=="bundle"         set "TASK=playBundle"
if /i "%CMD%"=="version"        set "TASK=playVersion"

if "!TASK!"=="" (
    echo play: unknown command '%CMD%'. Run 'play help' for usage.>&2
    exit /b 1
)

REM --- Locate gradlew/gradle ------------------------------------------------
REM Order: .\gradlew.bat -> %PLAY_HOME%\gradlew.bat (when in framework dir)
REM        -> system gradle on PATH -> install-instructions error.
set "GRADLE="
if exist "gradlew.bat" (
    set "GRADLE=gradlew.bat"
    goto :have_gradle
)
if /i "%CD%"=="%PLAY_HOME%" (
    if exist "%PLAY_HOME%\gradlew.bat" (
        set "GRADLE=%PLAY_HOME%\gradlew.bat"
        goto :have_gradle
    )
)
where gradle >nul 2>&1
if !errorlevel!==0 (
    set "GRADLE=gradle"
    goto :have_gradle
)
echo play: no Gradle wrapper found in this directory and 'gradle' is not on PATH.>&2
echo.>&2
echo Most play commands run from inside an application directory ^(one containing>&2
echo gradlew.bat^). Either:>&2
echo   - cd into your Play 1 application, or>&2
echo   - run `play new ^<name^>` to scaffold a new one.>&2
echo.>&2
echo If you intend to run Gradle directly without a wrapper, install Gradle:>&2
echo   Windows:  winget install Gradle.Gradle>&2
echo   Manual:   https://gradle.org/install/>&2
exit /b 1

:have_gradle
REM --- Translate 1.12-CLI args into Gradle wire format ---------------------
REM Mirrors the POSIX `play` script's forward_args. See that file's header
REM comment for the translation table. JVM-style args (-X*, -D*, -javaagent:,
REM -agentlib:) accumulate into JVM_ARGS and emit as a single -PjvmArgs=
REM property at the end so the spawned JVM gets them as one argv slot.
set "ARGS="
set "JVM_ARGS="
:next_arg
if "%~1"=="" goto :run_task
if /i "%~1"=="--silent" (shift & goto :next_arg)
set "A=%~1"
REM Gradle's own CLI flags pass through unchanged
if /i "%A%"=="--info"                       (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--debug"                      (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--warn"                       (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--quiet"                      (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--stacktrace"                 (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--scan"                       (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--no-daemon"                  (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--no-build-cache"             (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--no-configuration-cache"     (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--rerun-tasks"                (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--refresh-dependencies"       (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--offline"                    (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--continue"                   (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
if /i "%A%"=="--dry-run"                    (set "ARGS=!ARGS! %A%" & shift & goto :next_arg)
REM 1.12 play.id override: --%mode -> -PplayId=mode
if "!A:~0,3!"=="--%%" (set "ARGS=!ARGS! -PplayId=!A:~3!" & shift & goto :next_arg)
REM Dotted Play config keys -> camelCase Gradle props
if "!A:~0,12!"=="--http.port="  (set "ARGS=!ARGS! -PhttpPort=!A:~12!"  & shift & goto :next_arg)
if "!A:~0,13!"=="--https.port=" (set "ARGS=!ARGS! -PhttpsPort=!A:~13!" & shift & goto :next_arg)
REM JVM args: accumulate (-X covers -Xms/-Xmx/-XX:/-Xlog:; -D covers -Dkey=val)
if "!A:~0,2!"=="-X"             (set "JVM_ARGS=!JVM_ARGS! !A!" & shift & goto :next_arg)
if "!A:~0,2!"=="-D"             (set "JVM_ARGS=!JVM_ARGS! !A!" & shift & goto :next_arg)
if "!A:~0,11!"=="-javaagent:"   (set "JVM_ARGS=!JVM_ARGS! !A!" & shift & goto :next_arg)
if "!A:~0,10!"=="-agentlib:"    (set "JVM_ARGS=!JVM_ARGS! !A!" & shift & goto :next_arg)
REM Generic --foo=bar / --foo
if "!A:~0,2!"=="--" set "A=-P!A:~2!"
set "ARGS=!ARGS! !A!"
shift
goto :next_arg

:run_task
REM Emit accumulated JVM args as a single quoted -PjvmArgs property so the
REM value's internal spaces survive batch's argv tokenizer.
if not "!JVM_ARGS!"=="" (
    REM Strip the leading space JVM_ARGS picked up on each accumulation
    set "JVM_ARGS=!JVM_ARGS:~1!"
    set "ARGS=!ARGS! -PjvmArgs="!JVM_ARGS!""
)
call %GRADLE% %TASK% !ARGS!
exit /b !errorlevel!

REM ========================================================================
REM Subroutines
REM ========================================================================

:usage
echo Play 1 -- Gradle-backed CLI wrapper.
echo.
echo Usage: play ^<command^> [args...]
echo.
echo Application lifecycle:
echo   run                  Run the application in the foreground ^(dev mode^)
echo   start                Start the application in the background
echo   stop                 Stop the running application
echo   restart              Restart the running application
echo   status               Print application status
echo   pid                  Print the running application's PID
echo   out                  Tail the running application's log
echo                        The above background commands accept --pid-file=^<name^>
echo                        to target a specific instance ^(default: server.pid;
echo                        fallback: application.pidFile in conf\application.conf^).
echo.
echo Build / test / package:
echo   clean                Remove generated files
echo   precompile           Precompile sources and templates for production
echo   test                 Run the application's tests interactively
echo   auto-test            Run all tests headlessly and exit
echo   dist                 Build a distributable archive
echo   bundle               Build a self-contained deployment zip
echo.
echo Project setup:
echo   new ^<name^>            Scaffold a new application in ^<cwd^>\^<name^>
echo                        Optional: --frontend ^(Nuxt 3 frontend^)
echo                                  --dest=^<path^> ^(override destination^)
echo   secret               Generate a new application secret
echo   enable-https         Enable HTTPS in conf\application.conf
echo   disable-https        Disable HTTPS in conf\application.conf
echo.
echo Information:
echo   classpath            Print the resolved runtime classpath
echo   modules              List installed modules
echo   evolutions           Apply database evolutions
echo   javadoc              Generate Javadoc for the application
echo                        Optional: --include-modules ^(include declared module sources^)
echo                                  --links ^(add external API doc links^)
echo   version              Print the framework version
echo   help                 Show this help
echo.
echo Argument forwarding:
echo   --key=value          Pass a property override to the build
echo   --key                Pass a boolean property flag
exit /b 0

:new
set "APP_NAME=%~1"
if "%APP_NAME%"=="" (
    echo play new: missing application name.>&2
    echo Usage: play new ^<name^> [--frontend] [--dest=^<path^>]>&2
    exit /b 1
)
shift
REM Capture the user's CWD before we cd to PLAY_HOME, so relative dest paths
REM resolve correctly.
set "ORIG_CWD=%CD%"
set "DEST=%ORIG_CWD%\%APP_NAME%"
set "EXTRA="
:new_arg
if "%~1"=="" goto :do_new
if /i "%~1"=="--silent" (shift & goto :new_arg)
set "A=%~1"
if /i "%A%"=="--frontend" (
    set "EXTRA=!EXTRA! -Pfrontend"
) else if "!A:~0,7!"=="--dest=" (
    set "DEST=!A:~7!"
) else if "!A:~0,2!"=="--" (
    set "EXTRA=!EXTRA! -P!A:~2!"
) else (
    echo play new: unrecognized argument '!A!'>&2
    exit /b 1
)
shift
goto :new_arg

:do_new
REM Resolve relative DEST against ORIG_CWD
echo "!DEST!" | findstr /r "^.:" >nul
if errorlevel 1 (
    echo "!DEST!" | findstr /r "^\\\\" >nul
    if errorlevel 1 set "DEST=%ORIG_CWD%\!DEST!"
)
pushd "%PLAY_HOME%"
call gradlew.bat playNewApp -Pname="%APP_NAME%" -Pdest="!DEST!" !EXTRA!
set "RC=!errorlevel!"
popd
exit /b !RC!

:removed
echo play %CMD%: removed in 1.13.x.>&2
echo.>&2
echo Gradle handles dependency resolution and build orchestration natively. There is>&2
echo no longer a lib\ directory or modules registry to manage.>&2
echo.>&2
echo   - To inspect dependencies:  gradlew dependencies>&2
echo   - To add a dependency:      edit build.gradle.kts>&2
echo   - To list bundled modules:  play modules>&2
exit /b 1

:ide
echo play %CMD%: not needed in 1.13.x.>&2
echo.>&2
echo Open your IDE ^(IntelliJ IDEA, VS Code with the Gradle extension, Eclipse with>&2
echo Buildship, etc.^) and point it at build.gradle.kts in your application directory.>&2
echo Modern IDEs auto-import Gradle projects without generated project files.>&2
exit /b 0

:logo
REM Mirrors the banner printed by the pre-1.13 Python CLI.
echo ~  _   _       _ _       _     _
echo ~ ^| ^| ^| ^|_   _^| ^| ^| __ _^| ^|__ ^| ^|
echo ~ ^| ^|_^| ^| ^| ^| ^| ^| ^|/ _' ^| '_ \^| ^|
echo ~ ^|  _  ^| ^|_^| ^| ^| ^| ^(_^| ^| ^| ^| ^|_^|
echo ~ ^|_^| ^|_^|\__,_^|_^|_^|\__,_^|_^| ^|_^(_^)
echo ~
if exist "%PLAY_HOME%\framework\src\play\version" (
    set /p VERSION=<"%PLAY_HOME%\framework\src\play\version"
    echo ~ play! !VERSION!, https://www.playframework.com
    echo ~
)
goto :eof
