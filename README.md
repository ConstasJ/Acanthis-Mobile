# Acanthis Mobile

Acanthis 轻小说阅读器的原生移动客户端，基于 **Compose Multiplatform (CMP)** 与 **Kotlin Multiplatform (KMP)** 构建（Android 优先，所有核心逻辑与 UI 保留在 `commonMain`，便于后续扩展 iOS / Desktop）。

## 项目结构

采用 AGP 9 双模块架构：

- `:composeApp`（`com.android.kotlin.multiplatform.library`）：共享 KMP + CMP 模块
  - `commonMain`：Compose UI、网络层（Ktor 3）、本地数据库（Room KMP + SQLite Bundled）、偏好设置（DataStore）、章节 HTML 解析（Ksoup）、图片缓存（Coil 3）、依赖注入（Koin）
  - `androidMain`：OkHttp 引擎、Room / DataStore 路径与设备信息 `actual` 实现
  - `commonTest`：纯 Kotlin 单元测试（URL Rebase、HTML 章节块解析等）
- `:androidApp`（`com.android.application`）：Android 应用薄外壳，负责 `Application`、`MainActivity` 与打包配置（`applicationId = io.acanthis.app`）

## 环境要求

- JDK 21 LTS（不要使用 Java 8 或非 LTS 版本）
- Android SDK（`compileSdk = 36`，`minSdk = 24`）
- `local.properties` 配置 `sdk.dir=<Android SDK 路径>`

## 常用命令

### 运行单元测试

```powershell
.\gradlew.bat :composeApp:testAndroidHostTest
```

### 构建 Android Debug APK

```powershell
.\gradlew.bat :androidApp:assembleDebug
```

产物输出：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`

### 在模拟器或真机运行

```powershell
android emulator start medium_phone
adb install -r androidApp\build\outputs\apk\debug\androidApp-debug.apk
```
