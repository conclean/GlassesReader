<div align="center">

  <img src="app-icon.png" alt="GlassesReader App Icon" width="120" />

  # GlassesReader

  **一款将手机屏幕文字实时同步到 Rokid 智能眼镜的 Android 应用。无需开发线，安卓手机安装即可使用**

  [![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-blue.svg)](https://kotlinlang.org/)
  [![Android](https://img.shields.io/badge/Android-12%2B-green.svg)](https://www.android.com/)
  [![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.5.1-orange.svg)](https://developer.android.com/jetpack/compose)
  [![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

  <img src="info.png" alt="功能示意图" width="600" />

</div>

## 📖 项目简介

项目主页 👉 [glassesreader.conclean.top](https://glassesreader.conclean.top/)


GlassesReader 是一款基于 Kotlin 与 Jetpack Compose 构建的 Android 工具应用，通过无障碍服务抓取前台应用的屏幕文字，并通过蓝牙实时推送到 Rokid 智能眼镜显示。应用采用 Material Design 3 设计规范，提供简洁直观的用户界面。

### ✨ 核心特性

- 🔍 **智能文本采集**：监听屏幕内容变化，自动解析可见文字（优先适配微信读书，同时兼容其他阅读场景）
- 👓 **实时同步显示**：将采集的文本实时推送到 Rokid 眼镜端自定义页面显示
- 🔄 **自动重连**：应用启动时自动尝试重连已配对的设备，连接参数持久化保存
- 🎛️ **双控制入口**：
  - 主页圆形 FAB 按钮：主要服务控制入口
  - 悬浮窗开关按钮：可拖曳的浮窗按钮，与主页按钮状态同步
- ⚙️ **丰富的设置选项**：亮度调节、字体大小、文本处理选项等

## 🚀 快速开始

### 下载安装

1. 前往 [Releases](https://github.com/conclean/GlassesReader/releases) 页面下载最新版本的 APK
2. 在 Android 设备上安装 APK（需要允许"安装未知来源应用"）

### 首次使用

1. **权限授权**：首次启动应用会停留在"权限引导"页，依次完成：
   - ✅ 悬浮窗权限授权
   - ✅ 无障碍服务启用（在系统设置中找到 `ScreenTextService` 并启用）
   - ✅ 通知权限授权（Android 13+）
   - ✅ 蓝牙与定位权限授权

2. **设备连接**：
   - 前往"设备连接"页，点击"扫描设备"
   - 选择目标 Rokid 眼镜设备
   - ⚠️ **重要**：连接前请先断开官方应用的蓝牙配对，或将眼镜进入配对模式
   - 等待连接成功（连接过程可能需要 10 秒左右）
   - 首次连接成功后，连接参数会自动保存，下次启动应用时会自动尝试重连

3. **启动服务**：
   - 当权限与连接全部就绪时，点击主页右下角圆形 FAB 按钮启动读屏服务
   - 服务启动后，手机悬浮窗和眼镜端页面将同步显示识别文本

### 日常使用

- **控制服务**：
  - 主页圆形 FAB 按钮：主要控制入口，点击可开启/暂停读屏服务
  - 悬浮窗开关：可拖曳的浮窗按钮，与主页按钮状态同步（可在"应用设置"中隐藏）

- **调整显示**：
  - 在"显示设置"页调整眼镜端亮度（0-15）、字体大小（12-48 sp）
  - 配置文本处理选项（删除空行、删除换行符、删除首行/尾行）

## 🛠️ 技术栈

- **开发语言**：Kotlin
- **UI 框架**：Jetpack Compose + Material Design 3
- **架构模式**：MVVM（Model-View-ViewModel）
- **最低 SDK**：API 29 (Android 10)
- **目标 SDK**：API 35 (Android 15)

### 主要依赖

- [Rokid CXR-M SDK](https://developer.rokid.com/)：智能眼镜连接与自定义页面管理
- [EasyFloat](https://github.com/princekin-f/EasyFloat)：悬浮窗管理
- Jetpack Compose：声明式 UI 框架
- Retrofit + OkHttp：网络请求（用于未来功能扩展）

## 📁 项目结构

```
app/src/main/java/com/app/glassesreader/
├── MainActivity.kt                    # 主界面，包含权限引导、设备连接、显示设置
├── accessibility/
│   ├── ScreenTextPublisher.kt        # 文本发布器，管理文本流
│   └── service/
│       └── ScreenTextService.kt      # 无障碍服务，抓取屏幕文字
├── sdk/
│   ├── BluetoothHelper.kt            # 蓝牙扫描与设备管理工具
│   ├── CxrConnectionManager.kt       # Rokid SDK 蓝牙连接管理（初始化、连接、自动重连）
│   └── CxrCustomViewManager.kt       # 眼镜端自定义页面管理（打开、更新、关闭）
├── service/
│   └── overlay/
│       └── TextOverlayService.kt     # 前台服务，管理悬浮窗和文本订阅
└── ui/
    ├── components/
    │   ├── DeviceList.kt             # 设备列表组件
    │   └── FloatingToggle.kt         # 悬浮窗开关组件
    ├── screens/
    │   └── DeviceScanActivity.kt     # 设备扫描页面
    └── theme/                        # Material Design 3 主题配置
```

## 🔧 开发说明

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 8 或更高版本
- Android SDK API 29-35

### 构建步骤

1. 克隆仓库：
```bash
git clone https://github.com/conclean/GlassesReader.git
cd GlassesReader
```

2. 使用 Android Studio 打开项目

3. 同步 Gradle 依赖

4. 连接 Android 设备或启动模拟器（需要 Android 10+）

5. 运行项目

### 权限说明

#### 系统权限
- `SYSTEM_ALERT_WINDOW`：创建悬浮窗，用于显示开关按钮
- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`：Android 14+ 前台服务所需
- `POST_NOTIFICATIONS`（Android 13+）：显示前台服务常驻通知
- `ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION`：蓝牙扫描所需
- `BLUETOOTH`、`BLUETOOTH_ADMIN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN`（Android 12+）：蓝牙通信

#### 系统服务
- **无障碍服务** `ScreenTextService`：监听屏幕内容变化事件并整理文本
- **前台服务** `TextOverlayService`：通过 EasyFloat 创建开关浮窗并维持采集服务

## 📝 功能模块

### 1. 权限引导（SETUP）
- 检查并引导用户授权悬浮窗权限
- 检查并引导用户启用无障碍服务
- 检查并引导用户授权通知权限（Android 13+）
- 检查并引导用户授权蓝牙相关权限

### 2. 设备连接（CONNECT）
- 扫描附近的 Rokid 蓝牙设备
- 显示设备连接状态（已连接/未连接）
- 显示眼镜端自定义页面运行状态（已启动/未启动）
- **自动重连**：应用启动时自动尝试重连已配对的设备
- 提供"重新扫描"功能

### 3. 显示设置（DISPLAY）
- **亮度调节**：0-15 档位，实时同步到眼镜端
- **字体大小**：12-48 sp，可调节眼镜端显示字体
- **文本处理选项**：
  - 删除空行
  - 删除换行符
  - 删除首行/尾行（可设置删除行数）

### 4. 应用设置（SETTINGS）
- **悬浮窗开关按钮**：控制手机端悬浮窗的显示/隐藏
  - 当权限或连接未完成时，开关禁用并提示
  - 当所有条件满足时，开关启用，用户可手动开启悬浮窗
  - 悬浮窗隐藏时，仍可通过主页 FAB 按钮控制服务

## ⚠️ 已知限制与注意事项

### 蓝牙连接限制
- **独占连接**：Rokid 眼镜在同一时间只能与一个应用建立 SDK 连接。如果官方应用已连接，需要先断开其配对，再由本应用发起连接。
- **连接超时**：如果 `initBluetooth()` 在 10 秒内未收到回调，会判定为连接超时。可能原因：
  1. 设备已被其他应用占用
  2. SDK 内部错误
  3. 设备不支持多连接
- **自动重连失败**：如果自动重连失败，应用会弹出提示，引导用户前往设备连接页面手动连接

### 文本采集限制
- 文本采集依赖目标应用的无障碍节点，若页面采用 Canvas 自绘等方案可能无法完整捕获
- 眼镜端自定义页面依赖蓝牙连接，如连接断开将退回占位文本

### 使用建议
- 连接前确保官方应用已断开蓝牙配对
- 如果连接失败，尝试将眼镜进入配对模式后重新扫描连接
- 建议在连接成功后，先测试文本采集功能是否正常

## 📚 技术文档

- [Rokid CXR-M SDK 文档](./sdk/doc/)
  - [设备连接](./sdk/doc/设备连接.md)
  - [控制与监听设备状态](./sdk/doc/控制与监听设备状态.md)
  - [数据操作](./sdk/doc/数据操作.md)
  - [自定义页面场景](./sdk/doc/自定义页面场景.md)

## 🙏 致谢

- [Rokid](https://www.rokid.com/) - 提供智能眼镜 SDK 支持
- [EasyFloat](https://github.com/princekin-f/EasyFloat) - 悬浮窗管理库

## 📮 反馈与支持

如果你在使用过程中遇到问题或有功能建议，欢迎：
- 提交 [Issue](https://github.com/conclean/GlassesReader/issues)
- 发送邮件反馈

---

<div align="center">

**如果这个项目对你有帮助，请给个 ⭐ Star 支持一下！**

Made with ❤️ by [conclean]

</div>
