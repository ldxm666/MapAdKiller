<div align="center">

# MapAdKiller

**高德、百度、腾讯地图的广告精简与界面定制。**

[![Version](https://img.shields.io/badge/version-2.3.4-2ea44f?style=flat-square)](https://github.com/ldxm666/MapAdKiller/releases/tag/v2.3.4)
[![libxposed](https://img.shields.io/badge/libxposed-API%20102-0969da?style=flat-square)](https://github.com/libxposed/api)
[![License](https://img.shields.io/badge/license-GPL--3.0--or--later-blue?style=flat-square)](LICENSE)
[![Telegram 交流群](https://img.shields.io/badge/Telegram-交流群-26A5E4?style=flat-square)](https://t.me/MapAdkiller)
[![Telegram 通知频道](https://img.shields.io/badge/Telegram-通知频道-0A84C1?style=flat-square)](https://t.me/MapAdkillerr)

</div>

## 当前版本

**2.3.4 · versionCode 2304 · 2026-11-08**。支持免 root 内置版和现代 Xposed 模块版，各地图单独配置，可备份与恢复。

| 地图 | 包名 | 本轮适配基线 |
| --- | --- | --- |
| 高德国内版 | `com.autonavi.minimap` | 17.02.0.2010 |
| 高德 Google Play 版 | `com.autonavi.minimap` | 17.02.2.2014 |
| 百度地图 | `com.baidu.BaiduMap` | 22.1.20 |
| 腾讯地图 | `com.tencent.map` | 11.7.0（6013） |

其它地图版本不保证锚点一致。模块最低 Android 8.0，并需要支持 API 102 的框架；内置版使用 LSPatch，需要 Android 9.0 或更高版本，且不能低于宿主自身要求。

## 2.3.4 更新重点

- **高德国内/Play 底栏开关修复**：保留原生完整标签模型、控制器及路由，隐藏当前入口后安全返回首页，补齐恢复与冷启动路径。
- **百度我的页开关独立生效**：页面私有渲染列表替代共享数据与全局数组过滤，顶部图标宫格可单独开启，卡片可动态恢复。
- **配置刷新补齐**：百度我的页/通勤/工具宫格、高德首页统一处理本地写入、模块外部配置回调和批量恢复。
- **原生状态完整恢复**：保留百度底栏触摸监听，恢复腾讯首页入口行高，按原始可见性恢复高德、腾讯相关组件。
- **Android 13 兼容修复**：替换腾讯反射路径中不支持的 `ClassValue`；JS、地图样式与 native 库缓存改用内容摘要。
- **高德内置包安装兼容调整**：采用标准 ZIP 布局，包体有所增加；POCO F3 / MIUI 14 / Android 13 尚待实机安装反馈。
- 模块与四类内置包同步构建；完整模块 27 组 / 8599 项检查通过，Android 13/16 共 120 组生产 DEX 回归通过。

详细变更、证据和已知限制见 [2.3.4 发布说明](docs/RELEASE-2.3.4.md)、[验证说明](docs/VALIDATION-2.3.4.md)、[回归台账](docs/REGRESSION.md) 和 [完整更新日志](CHANGELOG.md)。Kotlin 主源码迁移、设置 UI 重构和横屏导航栏缩放由 2.3.3 引入，本次延续；3D 建筑隐藏、60/90/120 帧率及首次 Play 适配由 2.3.2 系列提供。

## 下载

统一从 [v2.3.4 Release](https://github.com/ldxm666/MapAdKiller/releases/tag/v2.3.4) 下载。模块仓库另提供 [模块版 2304-2.3.4](https://github.com/Xposed-Modules-Repo/io.github.ldxm666.mapadkiller/releases/tag/2304-2.3.4)。

| 文件 | 安装方式 |
| --- | --- |
| `MapClean-lsp-v2.3.4.apk` | 模块版：配合 LSPosed / 支持现代 API 102 的框架，三家地图共用 |
| `Amap-2.3.4-nonroot-embedded.apk` | 免 root：高德国内版单 APK |
| `Baidu-2.3.4-nonroot-embedded.apk` | 免 root：百度单 APK |
| `Tencent-2.3.4-nonroot-embedded.apk` | 免 root：腾讯单 APK |
| `Amap-Play-2.3.4-nonroot-embedded.zip` | 免 root：高德 Play 拆分包，四个 APK 一起装 |
| `MapAdKiller-2.3.4-source.zip` | 源码及编译依赖，不含签名私钥和地图原始 APK |
| `SHA256SUMS.txt` | 下载文件 SHA-256 校验值 |

**模块版与内置版选择一种。** 模块 APK 约 5.4 MiB；内置版包含完整宿主，体积明显更大。模块依赖框架注入，不重新签名官方地图；内置版是重新签名的地图安装包，不需要框架。不要对内置地图重复启用外部模块。

## 安装与升级

### 模块版

1. 安装 `MapClean-lsp-v2.3.4.apk`。
2. 在框架中启用模块，勾选需要处理的地图作用域。
3. 强制停止并重新打开对应地图。可以打开模块 App 配置，也可以从地图里的悬浮球进入面板。

模块沿用原发布证书，旧模块可覆盖升级。框架未连接时先检查作用域、API 102 支持及目标地图是否正在运行。

### 免 root 内置版

1. 先备份模块配置、登录相关资料与离线数据，再选择对应地图内置包。
2. 官方地图与内置版签名不同，不能直接覆盖；切换版本前确认备份，卸载会清除应用数据。同项目同签名内置版通常可覆盖更新。
3. 安装后打开地图，通过悬浮球或长按底部“我的”约 1 秒进入设置。

高德国内版和 Play 版包名相同，只能装一个。Play ZIP 解压后四个 APK 一起安装，不能只装 base.apk；可用拆分包安装器，或在解压目录执行：

```sh
adb install-multiple -r base.apk split_config.arm64_v8a.apk split_config.xxxhdpi.apk split_config.zh.apk
```

内置版签名与官方不同，可能影响系统校验、支付/账号联动、系统卡片或厂商服务；模块版也需按机型验证这些功能。不要把模拟导航或静态策略测试当作真实支付、车机、OEM 联动的完整验收。

### 高德首次抓取官方数据

工具宫格、首页卡片和底栏依赖官方下发配置。首次打开可能先进入原生采集模式：正常浏览首页/搜索/我的，等待“已获取官方数据”提示与自动重启；系统拦截自启时手动打开地图即可。采集未完成可下次继续，也可在面板提示条中重新抓取。采集期间改开关可能不会立即反映到界面。

## 设置入口与功能

模块 App 提供“首页、配置、备份、关于”。配置页按地图切换，支持分组与搜索；三家地图的开关独立，备份页支持 JSON 导入/导出。

地图内面板提供首页、工具、内容、地图、我的、底栏、省电、其他、外观等分类，具体以目标地图为准。高德有横屏导航栏缩放与巡航速度/持续时间设置；百度有通勤与原生工具布局；腾讯标注分基础与丰富/3D 图层。

悬浮球与面板可以调整大小、宽度和透明度。“关闭悬浮窗”会保存关闭状态，关闭面板的 × 仅收起面板；需要再次进入时可长按“我的”。

**按每项说明判断生效方式**：导航缩放、腾讯主页标注等支持运行中应用；省电/后台等在安装 hook 时决定的选项、实验 native 补丁，需强停重开。地图更新可能改变配置和锚点，遇到异常先关闭相关选项并提供版本和复现步骤。

## 省电与后台设置的影响

推送长连接、消息处理、后台任务、埋点、桌面小组件及第三方标注过滤等有独立开关。面板中“关闭/拦截”类选项开启表示阻止对应行为，关闭表示放行原生行为，请阅读说明再调整。

拦截推送可能影响行程提醒、消息和家人地图通知；拦截小组件刷新可能影响桌面卡片。需要系统通知、卡片或设备联动时，先放行有关项目并重开地图。第三方标注过滤及建筑/帧率选项具有版本和性能边界，不适用于所有机型；本版本不作零耗电、零掉帧或安全软件绝不误报的承诺。

## 已知限制与验证边界

- **灵动岛**：已修正部分误拦截，但 HyperOS 高德导航通知存在时系统卡片仍未显示；ColorOS 和手表联动尚未完成实机验证。
- **百度打车支付**：针对浮层和通知路径作兼容修正，无待支付订单，微信/支付宝点击无响应尚未确认根因及完整修复。
- **腾讯标注**：基础图层同时包含 POI、路名、地名，只在主页控制；不支持任意分类筛选，也不保证所有原生标签都来自这个图层。
- **导航/巡航**：横屏模拟导航与定位回放已验证；未完成真实道路和所有车机的驾驶验证。请在安全停车状态调整设置。
- **高德语音搜索**：兼容选项的首轮展开已验证，重复唤起与完整识别仍待验证。
- **POCO F3 安装**：标准 ZIP 布局已完成解析和结构检查，但尚无该 ARM 机型安装验收结果。
- **腾讯底栏**：逐项入口设置需重新打开地图，让宿主重新绑定。
- **Play 版**：独立构建、四包签名/结构及功能 DEX 对照通过；本轮未重新安装 Play 整包逐项实机测试。
- 百度部分详情页推荐/FAQ/团购横幅仍有未覆盖路径；高德开屏 logo 图层问题未解决。首页原生工具宫格最多两行，更多入口请使用“更多工具”。

## 构建、反馈与交流

- 构建依赖与命令：[BUILD.md](BUILD.md)。主源码使用 Kotlin 1.9.23，保留必要 Java 编译桩和测试。
- 历史变更：[CHANGELOG.md](CHANGELOG.md)；本次验证：[VALIDATION-2.3.4.md](docs/VALIDATION-2.3.4.md)。
- [GitHub Issues](https://github.com/ldxm666/MapAdKiller/issues)：附地图版本、系统版本、模块/内置方式、开关状态与复现步骤；日志先去除账号、位置、订单与支付信息。
- [Telegram 通知频道](https://t.me/MapAdkillerr) · [Telegram 交流群](https://t.me/MapAdkiller)。

感谢 libxposed、LSPosed / LSPatch、Kotlin 及相关开源项目。项目代码按 [GPL-3.0-or-later](LICENSE) 发布，依赖遵循各自许可证；内置包附 LSPatch 许可和来源说明。地图商标、数据、素材和原应用代码属于各自权利人；地图账号、支付与其它服务仍受原应用及服务条款约束。
