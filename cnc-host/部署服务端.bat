@echo off
chcp 65001 >nul
echo ========================================
echo   CNC 上位机 - Windows 本地部署
echo ========================================
echo.
echo 正在启动部署脚本...
echo.

cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File "%~dp0setup-windows.ps1"

if errorlevel 1 (
    echo.
    echo 部署失败，请查看上方错误信息
    pause
)
