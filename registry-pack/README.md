# MapAdKiller

高德 / 百度 / 腾讯地图去广告与界面精简 LSPosed 模块，libxposed API 102。

当前测试版 **2.1.0-beta1（versionCode 210）**：保留高德重构版本，百度以 2.0.0 为基线修复页面异常、开关和排列；隐藏 V22 活动图标及任务横幅。
实测高德 17.00.0.2005、百度 22.0.0（1650）；腾讯沿用原实现，本轮未重新验证。

- [源码](https://github.com/ldxm666/MapAdKiller)
- [本版 APK 与完整 ZIP](https://github.com/ldxm666/MapAdKiller/releases/tag/210-2.1.0-beta1)
- [更新日志](CHANGELOG.md)
- [修复及验证说明](https://github.com/ldxm666/MapAdKiller/blob/master/docs/REFACTOR-2.1.0-beta1.md)

覆盖安装 APK，在 LSPosed 中保留模块启用状态与地图作用域；强停目标地图后重新打开。更改开关后同样重启目标地图。

原项目作者：ldxm666。许可证：GPL-3.0-or-later。
