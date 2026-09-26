@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem ===========================================================================
rem  AnvilCraftFluid 本地自动发版
rem
rem  用法：双击运行，或  release.bat 1.1.0  [发布说明.txt]
rem
rem  做的事：
rem    0) 检查工作区干净、分支是 main、tag 没被占用
rem    1) 把 gradle.properties 的 mod_version 改成新版本
rem    2) gradlew build（失败就自动还原 gradle.properties）
rem    3) 提交版本号 + 打注释 tag v<版本> + push main 和 tag
rem    4) 创建 GitHub Release  ->  触发 .github/workflows/release.yml
rem       （CI 在 tag 上重新 build，把 jar 挂到 release 上）
rem
rem  说明：
rem    * jar 是由 CI 构建上传的，本地这个 jar 只是用来确认能构建成功
rem    * 只有 gradle.properties 里 upload=true 时，CI 才会顺带发
rem      Modrinth / CurseForge（现在 id 还是 xxxxx，保持 upload=false）
rem    * 版本号带后缀（如 1.1.0-beta.1）会自动建成 prerelease
rem    * 本机没装 gh CLI 时，会帮你打开"新建 Release"的网页，点一下 Publish 即可
rem ===========================================================================

set "PROP=gradle.properties"
set "REPO=xm1221/AnvilCraftFluid"
set "BRANCH=main"
set "MODID=anvilcraft_fluid"
set "MCVER=1.21.1"

echo.
echo ==== AnvilCraftFluid 自动发版 ====
echo.

rem ---- 0. 基本环境 ---------------------------------------------------------
git rev-parse --is-inside-work-tree >nul 2>nul
if errorlevel 1 (
    echo [x] 这个目录不是 git 仓库
    goto :fail
)

git rev-parse --abbrev-ref HEAD > "%TEMP%\acf_branch.txt"
set /p CURBRANCH=<"%TEMP%\acf_branch.txt"
del "%TEMP%\acf_branch.txt" >nul 2>nul
if /i not "%CURBRANCH%"=="%BRANCH%" (
    echo [x] 当前分支是 %CURBRANCH%，发版要在 %BRANCH% 上做
    goto :fail
)

rem ---- 1. 版本号 -----------------------------------------------------------
set "NEWVER=%~1"
set "NOTES=%~2"
if "%NEWVER%"=="" set /p "NEWVER=新版本号（例如 1.1.0，直接回车取消）: "
if "%NEWVER%"=="" (
    echo 已取消。
    goto :done
)

powershell -NoProfile -Command "if ('%NEWVER%' -notmatch '^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$') { exit 1 }"
if errorlevel 1 (
    echo [x] 版本号格式不对，要 x.y.z（可以带 -beta.1 这种后缀）
    goto :fail
)

for /f "tokens=2 delims==" %%a in ('findstr /b /c:"mod_version=" "%PROP%"') do set "OLDVER=%%a"
set "SKIPBUMP="
if "%OLDVER%"=="%NEWVER%" (
    set /p "ANS=gradle.properties 里已经是 %NEWVER% 了，直接用这个版本发？(y/N) "
    if /i not "!ANS!"=="y" goto :fail
    set "SKIPBUMP=1"
)

git rev-parse -q --verify "refs/tags/v%NEWVER%" >nul 2>nul
if not errorlevel 1 (
    echo [x] 标签 v%NEWVER% 已经存在，换个版本号
    goto :fail
)

echo 版本：%OLDVER%  --^>  %NEWVER%

rem ---- 2. 工作区必须干净（版本号改动由本脚本自己提交）----------------------
set "DIRTY="
for /f "delims=" %%a in ('git status --porcelain') do set "DIRTY=1"
if defined DIRTY (
    echo.
    echo [x] 工作区不干净 —— 发版前先自己提交/暂存（下面的改动也是要进这个版本的吧）：
    echo.
    git status --short
    goto :fail
)

rem ---- 3. 改版本号 ---------------------------------------------------------
if not defined SKIPBUMP (
    powershell -NoProfile -Command "$p='%PROP%'; $t=[IO.File]::ReadAllText($p); $t=[Text.RegularExpressions.Regex]::Replace($t,'(?m)^mod_version=[^\r\n]*','mod_version=%NEWVER%'); [IO.File]::WriteAllText($p,$t,(New-Object Text.UTF8Encoding($false)))"
    findstr /b /c:"mod_version=%NEWVER%" "%PROP%" >nul
    if errorlevel 1 (
        echo [x] 写 gradle.properties 失败
        goto :fail
    )
)

rem ---- 4. 构建 -------------------------------------------------------------
echo.
echo [1/5] 构建 gradlew build ...
call gradlew.bat build --console=plain
if errorlevel 1 (
    echo.
    echo [x] 构建失败
    if not defined SKIPBUMP git checkout -- "%PROP%"
    goto :fail
)

set "JAR=build\libs\%MODID%-neoforge-%MCVER%-%NEWVER%.jar"
if not exist "%JAR%" (
    echo [x] 没找到构建产物：%JAR%
    if not defined SKIPBUMP git checkout -- "%PROP%"
    goto :fail
)
echo       产物：%JAR%

rem ---- 5. 提交 + tag + push -------------------------------------------------
echo.
echo [2/5] 提交版本号 ...
git add "%PROP%"
git diff --cached --quiet
if errorlevel 1 (
    git commit -m "release: v%NEWVER%"
    if errorlevel 1 (
        echo [x] 提交失败
        goto :fail
    )
) else (
    echo       没有需要提交的改动，跳过
)

echo [3/5] 打标签 v%NEWVER% ...
git tag -a "v%NEWVER%" -m "v%NEWVER%"
if errorlevel 1 (
    echo [x] 打标签失败
    goto :fail
)

echo [4/5] 推送 %BRANCH% 和标签 ...
git push origin %BRANCH%
if errorlevel 1 (
    echo [x] 推送 %BRANCH% 失败，先把 %BRANCH% 推上去再重跑（标签还没推）
    goto :fail
)
git push origin "v%NEWVER%"
if errorlevel 1 (
    echo [x] 推送标签失败。本地已经有 v%NEWVER% 这个标签了，重跑前先执行：
    echo       git push origin v%NEWVER%
    goto :fail
)

rem ---- 6. 创建 GitHub Release（release.yml 靠它触发）------------------------
echo.
echo [5/5] 创建 GitHub Release ...
set "PRE="
echo %NEWVER% | findstr /c:"-" >nul && set "PRE=--prerelease"

where gh >nul 2>nul
if not errorlevel 1 (
    if defined NOTES (
        gh release create "v%NEWVER%" --title "v%NEWVER%" %PRE% --notes-file "%NOTES%"
    ) else (
        gh release create "v%NEWVER%" --title "v%NEWVER%" %PRE% --generate-notes
    )
    if errorlevel 1 (
        echo [x] gh 创建 Release 失败，改走网页
        goto :manual
    )
    echo       完成：CI 会自动构建并把 jar 挂到这个 release 上
    goto :done
)

if not "%GH_TOKEN%%GITHUB_TOKEN%"=="" goto :api

:manual
echo       本机没有 gh CLI，也没设 GH_TOKEN，已打开"新建 Release"页面：
echo         https://github.com/%REPO%/releases/new?tag=v%NEWVER%
echo       在页面上点 "Publish release"，release.yml 就会自动构建并挂上 jar。
start "" "https://github.com/%REPO%/releases/new?tag=v%NEWVER%&title=v%NEWVER%"
goto :done

:api
echo       用 GH_TOKEN 调 GitHub API 创建 Release ...
powershell -NoProfile -Command "$h=@{Authorization='Bearer '+$env:GH_TOKEN;'X-GitHub-Api-Version'='2022-11-28'}; $n='%NOTES%'; $b=@{tag_name='v%NEWVER%';name='v%NEWVER%';draft=$false;prerelease=$('%PRE%' -ne '')}; if ($n -ne '' -and (Test-Path $n)) { $b['body']=[IO.File]::ReadAllText($n) }; try { Invoke-RestMethod -Method Post -Uri 'https://api.github.com/repos/%REPO%/releases' -Headers $h -Body ($b | ConvertTo-Json) -ContentType 'application/json' | Out-Null; exit 0 } catch { Write-Host $_; exit 1 }"
if errorlevel 1 (
    echo [x] API 创建失败（token 权限不够？），改走网页
    goto :manual
)
echo       完成：CI 会自动构建并把 jar 挂到这个 release 上

goto :done

:fail
echo.
echo 发版中断，看看上面的提示。

:done
echo.
pause
endlocal
