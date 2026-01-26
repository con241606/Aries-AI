# Phone Agent 编译指南

> 本文档提供完整的环境搭建、依赖安装、项目配置和编译步骤，帮助新成员快速开始开发。

---

## 📋 目录

- [一、环境要求](#一环境要求)
- [二、依赖库清单](#二依赖库清单)
- [三、项目克隆和导入](#三项目克隆和导入)
- [四、VSCode配置](#四vscode配置)
- [五、Android Studio配置](#五android-studio配置)
- [六、编译和运行](#六编译和运行)
- [七、常见问题排查](#七常见问题排查)

---

## 一、环境要求

### 1.1 必需软件

| 软件 | 最低版本 | 推荐版本 | 下载地址 |
|------|----------|----------|----------|
| JDK | 17 | 17 (Temurin) | https://adoptium.net/temurin/releases/ |
| Android Studio | Hedgehog (2023.1.1) | Iguana (2023.2.1) | https://developer.android.com/studio |
| Gradle | 以Wrapper为准 | 以Wrapper为准 | 使用项目自带gradlew |
| Git | 2.30+ | 2.40+ | https://git-scm.com/downloads |

### 1.2 Android SDK 要求

请确保已安装 Android SDK Platform：

- **Android 36 (API 36)**

### 1.3 版本检查

```bash
# 检查JDK版本
java -version
# 应输出：openjdk version "17.x.x"

# 检查Git版本
git --version
# 应输出：git version 2.x.x

# 检查Gradle版本（在项目根目录）
./gradlew --version
# 以项目 Gradle Wrapper 输出为准
```

### 1.3 环境变量配置

```bash
# Windows (PowerShell)
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-17.0.12-hotspot")
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\YourName\AppData\Local\Android\Sdk")

# 添加到PATH
$env:Path += ";$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\tools"

# 验证配置
java -version
adb --version
```

---

## 二、依赖库清单

### 2.1 核心依赖

| 依赖库 | 版本 | 用途 | 是否已包含 |
|---------|------|------|-----------|
| AndroidX Core KTX | 1.17.0 | Android核心库 | ✅ |
| AndroidX AppCompat | 1.7.1 | 向后兼容 | ✅ |
| Material Design | 1.13.0 | UI组件 | ✅ |
| ConstraintLayout | 2.1.4 | 布局管理 | ✅ |
| Lifecycle Runtime KTX | 2.8.7 | 生命周期管理 | ✅ |
| RecyclerView | 1.3.2 | 列表显示 | ✅ |

### 2.2 Kotlin协程

| 依赖库 | 版本 | 用途 | 是否已包含 |
|---------|------|------|-----------|
| Kotlin Coroutines Android | 1.8.1 | 异步编程 | ✅ |

### 2.3 网络与序列化

| 依赖库 | 版本 | 用途 | 是否已包含 |
|---------|------|------|-----------|
| OkHttp | 4.12.0 | HTTP客户端 | ✅ |
| OkHttp Logging Interceptor | 4.12.0 | 日志拦截 | ✅ |
| Retrofit | 2.11.0 | REST API | ✅ |
| Retrofit Gson Converter | 2.11.0 | JSON转换 | ✅ |
| Gson | 2.10.1 | JSON序列化 | ✅ |

### 2.4 后台任务

| 依赖库 | 版本 | 用途 | 是否已包含 |
|---------|------|------|-----------|
| Work Runtime KTX | 2.9.1 | 后台任务 | ✅ |

### 2.5 测试依赖

| 依赖库 | 版本 | 用途 | 是否已包含 |
|---------|------|------|-----------|
| JUnit | 4.13.2 | 单元测试 | ✅ |
| AndroidX JUnit | 1.3.2 | Android测试 | ✅ |
| Espresso Core | 3.7.0 | UI测试 | ✅ |

### 2.6 自动安装说明

项目使用Gradle版本目录（Version Catalog），所有依赖会自动下载和配置，无需手动安装。

---

## 三、项目克隆和导入

### 3.1 克隆项目

```bash
# HTTPS方式（推荐）
git clone https://github.com/ZG0704666/Aries-AI.git
cd Aries-AI

# SSH方式（需要配置SSH密钥）
git clone git@github.com:ZG0704666/Aries-AI.git
cd Aries-AI
```

### 3.2 配置本地仓库

```bash
# 配置用户信息
git config user.name "你的名字"
git config user.email "your.email@example.com"

# 配置分支追踪
git config branch.main.name main
```

### 3.3 创建功能分支

```bash
# 切换到main分支
git checkout main

# 拉取最新代码
git pull origin main

# 创建功能分支（带你的名字）
git checkout -b feature/ui-tree-张三
```

---

## 四、VSCode配置

### 4.1 安装推荐插件

打开VSCode，按`Ctrl+Shift+X`打开扩展市场，搜索并安装以下插件：

| 插件名称 | 用途 | 是否必需 |
|----------|------|---------|
| Kotlin Language | Kotlin语法高亮 | ✅ 必需 |
| Android Code Snippet | Android代码片段 | ⭐ 推荐 |
| Gradle Language Support | Gradle语法支持 | ⭐ 推荐 |
| GitLens | Git增强 | ⭐ 推荐 |
| Error Lens | 错误信息增强 | ⭐ 推荐 |
| Rainbow Brackets | 彩虹括号 | ⭐ 可选 |

### 4.2 VSCode设置

创建或编辑`.vscode/settings.json`：

```json
{
  "kotlin.languageServer.enabled": true,
  "editor.formatOnSave": true,
  "editor.codeActionsOnSave": {
    "source.fixAll": true
  },
  "files.exclude": {
    "**/.gradle": true,
    "**/build": true,
    "**/local.properties": true
  },
  "git.enableSmartCommit": true,
  "git.postCommitCommand": "no verify"
}
```

### 4.3 VSCode调试配置

创建`.vscode/launch.json`：

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "android",
      "request": "launch",
      "name": "Launch App",
      "appLaunchActivity": ".MainActivity"
    }
  ]
}
```

---

## 五、Android Studio配置

### 5.1 必需插件

打开Android Studio，进入`File > Settings > Plugins`，搜索并安装：

| 插件名称 | 用途 | 是否必需 |
|----------|------|---------|
| Kotlin | Kotlin语言支持 | ✅ 必需 |
| .gitignore | Git忽略文件生成 | ⭐ 推荐 |
| Markdown Navigator | Markdown文件预览 | ⭐ 推荐 |
| Rainbow Brackets | 彩虹括号 | ⭐ 可选 |

### 5.2 Gradle配置

#### 5.2.1 配置Gradle JVM

打开`File > Settings > Build, Execution, Deployment > Build Tools > Gradle`：

```
Gradle JDK:
  ☑ Use Gradle JDK
  ☑ Use project JDK (17)

Gradle VM options:
  -Xmx2048m -Dfile.encoding=UTF-8
```

#### 5.2.2 配置编译选项

打开`File > Settings > Build, Execution, Deployment > Compiler > Kotlin Compiler`：

```
Language version: 2.0
Target JVM version: 11
API version: 1.7
```

### 5.3 同步Gradle

1. 打开项目后，Android Studio会自动同步Gradle
2. 等待同步完成（右下角进度条）
3. 如果同步失败，尝试：
   - 点击`File > Invalidate Caches / Restart`
   - 删除`.gradle`和`build`文件夹，重新同步

---

## 六、编译和运行

### 6.1 编译Debug版本

```bash
# Windows (PowerShell)
.\gradlew assembleDebug

# Linux/Mac
./gradlew assembleDebug
```

### 6.2 编译Release版本

```bash
# Windows (PowerShell)
.\gradlew assembleRelease

# Linux/Mac
./gradlew assembleRelease
```

### 6.3 运行测试

```bash
# 运行所有测试
.\gradlew test

# 运行单元测试
.\gradlew testDebugUnitTest

# 运行Android测试
.\gradlew connectedAndroidTest
```

### 6.4 安装到设备

```bash
# 安装Debug版本
.\gradlew installDebug

# 安装Release版本
.\gradlew installRelease

# 或者直接安装APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 6.5 运行应用

#### 方式1：通过Android Studio

1. 连接Android设备或启动模拟器
2. 点击`Run > Run 'app'`
3. 选择设备，点击运行按钮

#### 方式2：通过命令行

```bash
# 启动应用
adb shell am start -n com.ai.phoneagent/.MainActivity

# 查看日志
adb logcat | grep "PhoneAgent"
```

---

## 七、常见问题排查

### 7.1 Gradle同步失败

**问题**：Gradle sync失败，提示网络错误

**解决方案**：

1. 配置国内镜像源，编辑`gradle/wrapper/gradle-wrapper.properties`：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip
```

2. 或者配置阿里云镜像：

```properties
distributionUrl=https\://maven.aliyun.com/repository/gradle-plugin
```

### 7.2 依赖下载失败

**问题**：依赖下载缓慢或失败

**解决方案**：

1. 配置Maven镜像，编辑`build.gradle.kts`：

```kotlin
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    google()
    mavenCentral()
}
```

2. 使用代理（如果需要）：

```properties
# gradle.properties
systemProp.http.proxyHost=proxy.example.com
systemProp.http.proxyPort=8080
systemProp.https.proxyHost=proxy.example.com
systemProp.https.proxyPort=8080
```

### 7.3 编译错误

**问题**：编译失败，提示找不到符号

**解决方案**：

1. 清理项目：

```bash
.\gradlew clean
```

2. 删除`.idea`文件夹：

```bash
# Windows
Remove-Item -Recurse -Force .idea

# Linux/Mac
rm -rf .idea
```

3. 重新导入项目到Android Studio

### 7.4 设备连接问题

**问题**：adb无法识别设备

**解决方案**：

1. 重启adb服务：

```bash
adb kill-server
adb start-server
```

2. 检查USB调试是否开启：
   - 进入`设置 > 开发者选项`
   - 开启`USB调试`

3. 检查授权：
   - 设备上弹出授权对话框，点击`允许`

### 7.5 内存不足错误

**问题**：编译时提示内存不足

**解决方案**：

1. 增加Gradle内存：

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m
```

2. 增加Android Studio内存：

```
File > Settings > Appearance & Behavior > System Settings > Memory
Heap Size: 4096 MB
```

## 📚 八、相关文档

- [FEISHU_COLLABORATION.md](./FEISHU_COLLABORATION.md) - 飞书协作文档模板
- [CODING_STANDARDS.md](./CODING_STANDARDS.md) - 代码规范
- [GIT_WORKFLOW.md](./GIT_WORKFLOW.md) - Git工作流
- [README.md](./README.md) - 项目概述

---

## 🆘 九、快速检查清单

在开始开发前，请确认：

- [ ] JDK 17已安装并配置
- [ ] Android Studio Hedgehog+已安装
- [ ] 已通过 `gradlew` 验证Gradle Wrapper可用
- [ ] 已安装 Android SDK Platform 36 (API 36)
- [ ] 项目已克隆到本地
- [ ] VSCode推荐插件已安装
- [ ] Android Studio推荐插件已安装
- [ ] Gradle同步成功
- [ ] 可以编译Debug版本
- [ ] 可以运行测试

---

**文档版本**：v1.2
**最后更新**：2026-01-26
**维护人**：ZG666
