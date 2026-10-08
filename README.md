# MapAdKiller 2.1.0-beta1

高德 / 百度 / 腾讯地图 LSPosed 模块，libxposed API 102，versionCode 210。

本版保留已验证的高德重构版本，百度以 2.0.0 为基线修复。

- 高德：首页信息流、天气、扫街榜、打车营销卡及通勤入口精简；修复工具排列、上下拖动和底部按钮背景，增加好友动态开关。
- 百度：改用原生组件与 Presenter Hook；修复页面异常、配置读取及开关对应关系；底部剩余入口均分，「我的」页逐卡控制。
- 百度：隐藏左上角 V22 轮播活动图标及「天天做任务」任务横幅，拦截创建、轮播与恢复显示入口。

实测适配高德 **17.00.0.2005**、百度 **22.0.0（1650）**。腾讯部分沿用旧实现，本轮未重新验证。
未知高德、百度版本跳过专用 Hook；不声明适配其他版本。

## 下载与安装

下载 [本版 Release](https://github.com/ldxm666/MapAdKiller/releases/tag/210-2.1.0-beta1) 中的 APK 或完整交付 ZIP。
需要 root 与支持 libxposed API 102 的 LSPosed。覆盖安装 APK，保留模块启用状态及地图作用域，强停目标地图后重新打开。
更改开关后同样强停目标地图再打开，无需重启手机。

## 构建与说明

使用 JDK 与 Android SDK build-tools，按本机工具路径调整 `app/build.ps1` 后执行：

```powershell
.\app\build.ps1
```

输出 `output/MapClean-lsp-v2.1.0-beta1.apk`。源码 ZIP 不包含签名密钥；首次构建会生成调试密钥。
API 编译桩仅供编译，运行时使用 LSPosed 提供的 API。

[修复与验证范围](docs/REFACTOR-2.1.0-beta1.md) · [更新日志](CHANGELOG.md) · [原版历史说明](docs/README-v2.0.2-historical.md)

原项目作者：ldxm666。许可证：GPL-3.0-or-later，见 [LICENSE](LICENSE)。
