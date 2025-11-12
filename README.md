# GlassesReader 无障碍文本采集工具

## 项目概述
GlassesReader 是一款基于 Kotlin 与 Jetpack Compose 构建的安卓工具应用，借助无障碍服务抓取前台应用的屏幕文字，并以浮窗形式展示，后续可扩展同步到智能眼镜。目前聚焦于文本采集与本地浮窗显示。

## 核心需求
- 启用无障碍服务后，监听屏幕内容变化，自动解析可见文字（优先适配 QQ 浏览器小说页面，同时兼顾其他阅读场景）。
- 提供常驻后台的浮窗开关，不干扰原有触控操作。
- 提供测试用文字浮窗：透明背景、不拦截触控，便于观察采集结果。

## 权限与服务
- `android.permission.SYSTEM_ALERT_WINDOW`：创建悬浮窗，用于显示开关按钮和文本浮窗。
- `android.permission.FOREGROUND_SERVICE`、`android.permission.FOREGROUND_SERVICE_DATA_SYNC`：Android 14+ 以 dataSync 类型启动前台服务所需。
- `android.permission.POST_NOTIFICATIONS`（Android 13+）：显示前台服务常驻通知。
- `android.permission.ACCESS_COARSE_LOCATION`、`android.permission.ACCESS_FINE_LOCATION`：配合蓝牙扫描掌握设备位置权限要求。
- `android.permission.BLUETOOTH`、`android.permission.BLUETOOTH_ADMIN`、`android.permission.BLUETOOTH_CONNECT`、`android.permission.BLUETOOTH_SCAN`（Android 12+）：用于与智能眼镜进行蓝牙通信。
- 无障碍服务 `ScreenTextService`：监听 `TYPE_VIEW_TEXT_CHANGED`、`TYPE_WINDOW_CONTENT_CHANGED`、`TYPE_VIEW_SCROLLED` 等事件并整理文本。
- 前台服务 `TextOverlayService`：通过 [EasyFloat](https://github.com/princekin-f/EasyFloat) 创建开关浮窗与文本浮窗，维持常驻展示。

## 模块规划
- `MainActivity`：引导授权悬浮窗/无障碍/通知权限，并启动文本服务。
- `accessibility/service/ScreenTextService`：解析屏幕节点并输出文本。
- `service/overlay/TextOverlayService`：以前台服务形式运行 EasyFloat 浮窗。
- `sdk/CxrCustomViewManager`：封装 Rokid 自定义页面的打开、更新与关闭逻辑。
- `ui/components/FloatingToggle`：浮窗开关的文字样式（Reading / Off）。

## 开发里程碑
- [x] 权限声明与工程初始化
- [x] 无障碍文本采集服务完成
- [x] 浮窗控制与 UI 组件完成（开关浮窗可拖曳，文本浮窗透明且不拦截触控，支持滚动查看完整文本）
- [x] 服务间流程整合与状态管理（前台服务 + Flow 数据管线）
- [x] 实机测试并更新使用文档、已知限制（持续跟进）
- [x] 集成智能眼镜 SDK 自定义页面文字同步（进行增量迭代）

## 使用指引
1. 首次启动 App 后，按主界面提示依次授权"悬浮窗""无障碍""通知(13+)"权限。
2. 授权蓝牙与定位等通讯相关权限（Android 12+ 需额外允许扫描/连接）。
3. 在"无障碍服务"页启用 **GlassesReader 屏幕文字采集**。
4. **重要：连接智能眼镜前，请先断开 Rokid 官方应用的蓝牙配对**，然后由本应用发起配对和连接。
5. 返回 App，点击"连接智能眼镜"进入扫描页面，选择设备进行连接。
6. 连接成功后，点击"启动服务"，系统会常驻通知并显示右侧开关浮窗。
7. 应用会自动在眼镜端打开自定义页面，轻点浮窗开关可开始/暂停文字采集；浮窗支持拖动调整位置。
8. 启用后会出现透明文本浮窗（不拦截触控），可在 QQ 浏览器、微信读书等阅读页面观察文字更新，支持滚动浏览完整采集内容，同时最新文本会推送至眼镜自定义页面。
9. 如需暂停，点击浮窗或主界面的"停止服务"，通知与浮窗将一并回收，眼镜端页面会显示占位文案。

## 已知限制
- 文本采集依赖目标应用的无障碍节点，若页面采用 Canvas 自绘等方案可能无法完整捕获。
- 眼镜端自定义页面依赖蓝牙连接，如连接断开将退回占位文本。
- 尚未实现文本增量对比、翻页检测等优化，长篇内容可能重复显示整体段落。
- **蓝牙连接限制**：Rokid 眼镜在同一时间只能与一个应用建立 SDK 连接。如果官方应用已连接，需要先断开其配对，再由本应用发起连接。

## 后续展望
- 与 Rokid 智能眼镜等设备联动（规划中）。
- 增加文本分段、语音播报等辅助功能。
- 支持多语言与自定义快捷操作。

