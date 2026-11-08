# 跨模块回归台账

本台账对应 2.3.4 工作树。变更入口、下游范围及验收命令须一起维护；静态扫描或单次编译成功不能代替行为验收。当前执行证据见 [VALIDATION-2.3.4.md](VALIDATION-2.3.4.md)。

## 审查基准与范围

- 基准提交：`e9aab757a63b1868dba17b8e4707b5cdf4616781`，公开版本 2.3.3。
- 变更集合取以下结果的并集，包含已提交、暂存、未暂存及未跟踪文件；不能只检查最近一次 diff。

```powershell
git diff --name-only e9aab757a63b1868dba17b8e4707b5cdf4616781 HEAD
git diff --cached --name-only
git diff --name-only
git ls-files --others --exclude-standard
```

本轮对整个 `app/src` 的 172 个 Kotlin 文件扫描配置读写、监听、隐藏/恢复、集合修改、缓存身份及 Android API 使用，复核相关运行路径；连同变体构建、DEX 检查及测试工具复核。没有把这种扫描写成“所有代码没有其他缺陷”，也未改动第三方 native 实现。旧交接文档和设备私有证据不作为可发布源码。

可重复执行的风险定位命令：

```powershell
rg -n 'invalidate|OnSharedPreferenceChangeListener|fun refresh' app/src
rg -n 'removeAllViews|\.clear\(|\.remove\(|visibility =|setOnTouchListener|setOnClickListener' app/src
rg -n 'ClassValue|lastModified\(|hashCode\(|cache|FileOutputStream' app/src
rg -n 'MineData|MineJs|Array\.prototype\.(push|concat)\s*=' app/src
```

命中不等于缺陷。例如模块自建 UI 的清空、永久屏蔽的广告视图、临时 JSON 副本与已停用的实验代码应区别处理；没有证据的运行路径不按猜测重写。

## 执行命令约定

以下命令在仓库根目录执行。`BUILD_DIR` 是完整模块构建输出的 ASCII 临时目录；`DEVICE_SERIAL` 明确指定测试设备。

```powershell
pwsh -NoProfile -File app/build.ps1
pwsh -NoProfile -File tests/run-android.ps1 -BuildDirectory BUILD_DIR -Serial DEVICE_SERIAL
python -m unittest discover -s tests -p test_check_anchors.py -v
```

前两项需要本地工具链、签名材料和 adb 设备。构建写入本地临时目录及 `output`；Android 测试写入 adb 临时目录，不改变地图用户设置。生产 DEX 检查另执行：

```powershell
python tests/run-production.py --ModuleApk output/MapClean-lsp-v2.3.4.apk --AmapApk output/fix-234-cn/Amap-2.3.4-nonroot-embedded.apk --AmapPlayDirectory output/fix-234-play/Amap-Play-2.3.4-nonroot-embedded --BaiduApk output/fix-234-baidu/Baidu-2.3.4-nonroot-embedded.apk --TencentApk output/fix-234-tmap/Tencent-2.3.4-nonroot-embedded.apk --Adb path/to/adb --Serial DEVICE_SERIAL --AndroidSdk path/to/sdk
```

此命令不安装地图、不改变配置或框架作用域，只把独立测试 DEX 与生产模块副本放入本次调用专属的 adb 临时目录，完成后删除。输出逐组退出码及 JSON 结果；不得把测试副本通过视为生产 APK 通过。

## 模块依赖与传播

### 配置与设置入口

- 下游：高德原生首页/底栏/导航缩放；百度我的页/通勤/工具宫格；腾讯对应刷新消费者；模块及全部内置版。
- 依赖证据：`rg -n 'refreshViews|onConfigChanged|BaiduToolGrid.refresh|BaiduCommute.refresh|BaiduMineCards.refresh|AmapTabLayout.refresh|AmapNaviScale.refresh' app/src`。
- 验收：Android 全套命令；重点 `AmapConfigRefreshTest`、`BaiduConfigRefreshTest`、`CfgStoreTest`、`BaiduCommuteTest`、`ConfigBackupTest`；生产检查覆盖完整模块及四类内嵌模块。
- 传播规则：共享配置入口或批量恢复变化，必须重跑以上全部消费者；不允许仅验证设置 UI 的勾选状态。

### 高德导航、首页与底栏

- 下游：完整模块、高德国内内置版、高德 Play 内置版。
- 依赖证据：`rg -n 'AmapTabLayout|AmapNativeHome|AmapNaviScale|AmapCruise|AmapTaxiSkin' app/src tools/build_variant.py`。
- 验收：`AmapTabLayoutTest`、`AmapConfigRefreshTest`、`AmapNaviScaleTest`、`AmapCruiseTest`、高德协议/打车/地点数据组；生产检查中的 module、amap-cn、amap-play。
- 传播规则：高德行为变化必须分别构建国内和 Play 包，核对功能 DEX 一致；DEX 一致只证明裁剪一致，不代替宿主独立适配。

### 百度卡片、Talos 与原生布局

- 下游：完整模块、百度内置版；共享配置或 native 提取变化还影响其它内置版。
- 依赖证据：`rg -n 'BaiduMineCards|BaiduTalos|BaiduPageScripts|BaiduTabRouting|BaiduPoiLayer' app/src tools/build_variant.py`。
- 验收：`BaiduMineCardsTest`、`BaiduConfigRefreshTest`、`BaiduCacheIdentityTest`、`BaiduTabRoutingTest`、`BaiduCommuteTest`、`BaiduPanelLayoutTest`、`BaiduPaymentTest`、`BaiduLabelStyleTest`；生产检查 module、baidu。
- 实际页面脚本验收：使用生产 Kotlin 适配器生成两代已取得的页面 bundle，`node --check` 后执行 `node tests/BaiduMineCardsRuntimeTest.cjs <adapted-bundles...>`。原始 bundle 是私有测试输入，不随源码发布。
- 传播规则：我的页过滤/事件协议变化必须同时跑全部 256 种组合、关闭再开启、原始数据保留与旧/新模板两种结构；不得只测“全开”或“全关”。

### 腾讯反射、底栏与首页卡片

- 下游：腾讯所有 hook 消费者、完整模块、腾讯内置版。
- 依赖证据：`rg -n 'H\.(field|call|method)|TmapSeekChips|TmapTabBar|TmapHomeFav' app/src/io/github/ldxm666/tmapclean`。
- 验收：`TmapStateRecoveryTest`、`TmapAnnotationsTest`、`TmapPanelLayoutTest`；生产检查 module、tencent；腾讯已登记锚点还须执行下列静态校验：

```powershell
python tools/check_anchors.py --Apk path/to/tencent-11.7.0-6013.apk --Anchors tools/anchors-tmap-11.7.0.json --Report path/to/anchor-report.json
```

- 传播规则：反射缓存变化必须在 Android 13 以及较新系统运行；只在 Android 16 通过不能覆盖 `ClassValue` 一类 API 下限问题。
- 时机边界：整栏开关与已跟踪首页组件可即时刷新；腾讯底栏逐项入口依赖宿主重新绑定，必须重新打开地图；Kuikly 地点详情等组件按其原生重建时机生效。

### 共享 native 缓存与构建裁剪

- 下游：完整模块及全部四类内置模块。
- 依赖证据：`rg -n 'NativeLibraryCache|NativeLabelAssets|MODULE_DROP|DROP_|KEEP_' app/src tools/build_variant.py tools/module_prune.py`。
- 验收：`NativeLibraryCacheTest` 在全部五种生产模块中运行；完整构建、全部变体构建及模块验证命令都必须成功。
- 传播规则：不能只用未裁剪的 classes.jar 测试；须解出各宿主实际嵌入的 APK，检查版本/签名/ZIP/元数据及功能 DEX。

## 缺陷回归点

| TEST-ID | 保护行为 | 执行资产 |
| --- | --- | --- |
| TEST-MINE-234 | 独立卡片组合、单开宫格、原始数据身份、生命周期与动态恢复 | `BaiduMineCardsTest.java`、`BaiduMineCardsRuntimeTest.cjs` |
| TEST-CFG-BAIDU-234 | 外部配置回调、本地失效与批量恢复到达同一消费者 | `BaiduConfigRefreshTest.java` |
| TEST-CFG-AMAP-234 | 配置回调到达原生首页，工具宫格恢复宿主原始可见性 | `AmapConfigRefreshTest.java` |
| TEST-TMAP-STATE-234 | 完整输入列表、未知标签、原始行高、恢复失败重试及宿主可见性 | `TmapStateRecoveryTest.java` |
| TEST-COMPAT-API33-234 | 腾讯反射路径可在 Android 13 加载并执行 | Android 13 上的腾讯状态、标注、面板三组 |
| TEST-BAIDU-TOUCH-234 | 语音原生 TouchListener 隐藏后重新开启仍响应 | `BaiduTabRoutingTest.java` |
| TEST-CACHE-NATIVE-234 | 相同 APK/库长度但内容不同、等长损坏缓存修复 | `NativeLibraryCacheTest.java` |
| TEST-CACHE-BAIDU-234 | 相同时间戳/长度的 JS/STY 更新与配置变化不复用旧缓存 | `BaiduCacheIdentityTest.java` |
| TEST-TOOLS-ANCHORS-234 | 构造器不误判丢失，DEX 查询失败不得判通过 | `test_check_anchors.py` |

## 交付判据

每条实际命令记录退出码。失败、未运行、宿主版本未验证必须单独列出；不把未运行写成通过。修复后重跑对应模块及其下游；若再次发现缺陷并更改源码，旧产物和旧报告失效，须重新构建受影响变体。
