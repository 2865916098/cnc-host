# CNC 上位机 - Windows 本地运行指南

## 快速开始（推荐）

1. **下载项目**
   - 方式一（Git）：
     ```
     git clone https://github.com/2865916098/cnc-host.git
     cd cnc-host
     git checkout trae/agent-2S8kzZ
     ```
   - 方式二：下载压缩包解压

2. **一键启动服务端**
   - 双击 `部署服务端.bat`
   - 脚本会自动：检测/安装 JDK 17 → 构建服务端 → 启动 → 打开浏览器

3. **访问管理后台**
   - 浏览器自动打开 http://localhost:8080/admin/
   - 管理口令：`cnc-admin-2026`

## 手动启动（如果脚本有问题）

确保已安装 JDK 17：https://adoptium.net/temurin/releases/?version=17

```cmd
cd cnc-host
gradlew.bat :server:installDist
server\build\install\cnc-server\bin\cnc-server.bat
```

然后浏览器访问 http://localhost:8080/admin/

## 编译 APK（可选，需要 Android SDK）

1. 安装 Android Studio 或 Android 命令行工具
2. 在项目根目录创建 `local.properties`：
   ```
   sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
   ```
3. 编译：
   ```cmd
   gradlew.bat :app:assembleDebug
   ```
4. APK 路径：`app\build\outputs\apk\debug\app-debug.apk`

## 停止服务端

在运行服务端的窗口按 `Ctrl+C`

## 数据存储

- 服务端数据库：`C:\Users\你的用户名\cnc-server-data\cnc-server.db`
- 删除该文件可重置所有数据
