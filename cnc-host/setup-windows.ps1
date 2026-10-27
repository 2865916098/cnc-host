# CNC 上位机 - Windows 一键部署脚本（PowerShell）
# 用法：右键 -> 使用 PowerShell 运行，或在 PowerShell 中执行：
#   Set-ExecutionPolicy -Scope Process Bypass; .\setup-windows.ps1
#
# 功能：
#   1. 检测并安装 JDK 17（Temurin）
#   2. 构建服务端
#   3. 启动服务端
#   4. 自动打开浏览器访问管理后台
#   5. 可选：配置 Android SDK 用于编译 APK

$ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  CNC 上位机 - Windows 本地部署脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ---------- 1. 检测 / 安装 JDK 17 ----------
Write-Host "[1/4] 检测 JDK 17 ..." -ForegroundColor Yellow

$javaHome = $env:JAVA_HOME
$javaExe = if ($javaHome) { Join-Path $javaHome "bin\java.exe" } else { $null }

if ($javaExe -and (Test-Path $javaExe)) {
    $ver = & $javaExe -version 2>&1 | Select-Object -First 1
    Write-Host "  已找到 JAVA_HOME: $javaHome" -ForegroundColor Green
    Write-Host "  版本: $ver" -ForegroundColor Green
    if ($ver -notmatch '"17\.') {
        Write-Host "  警告: 当前 JDK 不是 17，项目建议使用 JDK 17" -ForegroundColor Yellow
    }
} else {
    Write-Host "  未检测到 JAVA_HOME，尝试安装 JDK 17 (Temurin) ..." -ForegroundColor Yellow
    try {
        winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-package-agreements --accept-source-agreements
        # 安装后重新检测
        $candidates = @(
            "C:\Program Files\Eclipse Adoptium\jdk-17*",
            "C:\Program Files\Temurin\jdk-17*"
        )
        foreach ($c in $candidates) {
            $found = Get-ChildItem $c -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($found) {
                $env:JAVA_HOME = $found.FullName
                $env:Path = "$env:JAVA_HOME\bin;$env:Path"
                Write-Host "  已设置 JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
                break
            }
        }
    } catch {
        Write-Host "  winget 安装失败，请手动安装 JDK 17:" -ForegroundColor Red
        Write-Host "  https://adoptium.net/temurin/releases/?version=17" -ForegroundColor Red
        exit 1
    }
}

if (-not $env:JAVA_HOME) {
    Write-Host "  错误: 未能找到 JDK 17，请手动安装后重试" -ForegroundColor Red
    Write-Host "  下载地址: https://adoptium.net/temurin/releases/?version=17" -ForegroundColor Red
    exit 1
}

# ---------- 2. 进入项目目录 ----------
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectDir
Write-Host ""
Write-Host "[2/4] 项目目录: $projectDir" -ForegroundColor Yellow

# ---------- 3. 构建并启动服务端 ----------
Write-Host ""
Write-Host "[3/4] 构建服务端 ..." -ForegroundColor Yellow

$gradlew = Join-Path $projectDir "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Host "  错误: 找不到 gradlew.bat，请确认在项目根目录运行" -ForegroundColor Red
    exit 1
}

& $gradlew :server:installDist --no-daemon
if ($LASTEXITCODE -ne 0) {
    Write-Host "  服务端构建失败" -ForegroundColor Red
    exit 1
}
Write-Host "  服务端构建成功" -ForegroundColor Green

# 查找启动脚本
$startScript = Get-ChildItem -Path (Join-Path $projectDir "server\build\install") -Recurse -Filter "cnc-server.bat" | Select-Object -First 1
if (-not $startScript) {
    Write-Host "  错误: 找不到 cnc-server.bat" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[4/4] 启动服务端 ..." -ForegroundColor Yellow

# 设置数据目录（用户目录下，避免权限问题）
$env:CNC_DB = Join-Path $env:USERPROFILE "cnc-server-data\cnc-server.db"
New-Item -ItemType Directory -Force -Path (Split-Path $env:CNC_DB) | Out-Null

$env:PORT = "8080"
$env:CNC_ADMIN_TOKEN = "cnc-admin-2026"

Write-Host "  数据库: $env:CNC_DB" -ForegroundColor Gray
Write-Host "  端口: 8080" -ForegroundColor Gray
Write-Host "  管理口令: cnc-admin-2026" -ForegroundColor Gray
Write-Host ""
Write-Host "  服务端启动中... 请勿关闭此窗口" -ForegroundColor Cyan
Write-Host "  按 Ctrl+C 停止服务" -ForegroundColor Gray
Write-Host ""

# 延迟 2 秒后打开浏览器
Start-Job -ScriptBlock {
    Start-Sleep -Seconds 3
    Start-Process "http://localhost:8080/admin/"
} | Out-Null

# 前台运行服务端（阻塞）
& $startScript.FullName
