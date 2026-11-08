# MapAdKiller 2.3.4 构建说明

当前工作树为 **2.3.4 / 2304，尚未发布**；公开 Release 仍为 2.3.3。

主源码位于 `app/src`，使用 Kotlin 1.9.23；API 桩、回归测试和部分构建工具仍有 Java。模块、三个国内内置包和高德 Play 拆分内置包使用同一套源码。版本事实源为 `app/src/io/github/ldxm666/mapclean/Version.kt`（NAME/LABEL/CODE = 2.3.4/2.3.4/2304），并保持 Manifest、module.prop 一致。

## 环境与路径

当前脚本以 Windows PowerShell / Python 3.10+ 为基线，并非直接兼容 Linux/macOS 的脚本。

| 依赖 | 要求 |
| --- | --- |
| JDK | 17，Java/Kotlin JVM target 8 |
| Android SDK | platform android-34、build-tools 35.0.0 |
| Kotlin | 1.9.23；使用 Gradle 8.9 分发包 lib 目录中的 embeddable compiler 及依赖 |
| Kotlin 运行库 | `app/libs/kotlin-stdlib-1.9.23.jar`，必须进入 DEX |
| libxposed | `app/libs/dependencies.json` 固定的 API 102、service、interface JAR |
| LSPatch | v1.2 build 487，仅内置版构建需要 |
| Android NDK | 26.3.11579264，仅修改 native 源码后重编需要 |

`app/build.ps1` 目前包含本机 JDK、SDK、KotlinLib 绝对路径。自行构建时须将脚本开头的 `$Jdk`、`$Sdk`、`$KotlinLib` 改为自己的安装路径；同时核对 KotlinJars 数组中 compiler、stdlib、reflect、script-runtime、trove4j、annotations、daemon 文件均存在。临时编译目录使用 ASCII 路径，避免 aapt2/d8 的非 ASCII 路径问题。`tools/build_variant.py` 也会探测 Kotlin 编译器路径，迁移环境时请核对其工具链解析逻辑。

## 完整模块

在项目根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File app/build.ps1
python tools/verify_module.py --ModuleApk output/MapClean-lsp-v2.3.4.apk --ExpectedVersion 2.3.4 --ExpectedVersionCode 2304 --Java D:/JDK/bin/java.exe --OutputReport output/module-verification.json
```

产物：`output/MapClean-lsp-v2.3.4.apk`，另复制到历史路径 `app/dist/BMapClean.apk`。

生产构建依赖本地 `app/debug.keystore`，不会自动创建/替换。生产证书 SHA-256 为 `cbecdaeb31836c4ab2ffe9271b47148810d1335f3a70782dc9d2d29d986554ed`。源码归档不包含签名私钥；第三方开发构建必须使用自己的密钥并明确调整签名校验配置，因此不能覆盖官方模块。不要把私钥或口令提交到仓库。

构建依次准备 API 桩、编译 Kotlin、合并必要 service/interface 与 Kotlin runtime、d8、打包资源和 native、16 KiB zipalign、签名并验证。不要将测试 DEX 或 API compile-only 桩打入生产 APK。

## 国内版内置包

准备合法取得的原始宿主：高德 17.02.0.2010、百度 22.1.20、腾讯 11.7.0（6013）。原始 APK 不在源码归档中。LSPatch CLI 来源：[JingMatrix/LSPatch v1.2](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2)，SHA-256：`d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165`。

```powershell
$env:JAVA_HOME = 'D:/JDK'
$jar = 'path/to/lspatch-v1.2-487-release.jar'
python tools/build_variant.py --Target amap --OriginalApk path/to/amap-base.apk --OutputDirectory output/nonroot-amap-234 --ArtifactName Amap-2.3.4-nonroot-embedded.apk --LspatchJar $jar --SignatureBypass 2
python tools/build_variant.py --Target baidu --OriginalApk path/to/baidu-base.apk --OutputDirectory output/nonroot-baidu-234 --ArtifactName Baidu-2.3.4-nonroot-embedded.apk --LspatchJar $jar --SignatureBypass 2
python tools/build_variant.py --Target tmap --OriginalApk path/to/tencent-base.apk --OutputDirectory output/nonroot-tmap-234 --ArtifactName Tencent-2.3.4-nonroot-embedded.apk --LspatchJar $jar --SignatureBypass 2
```

可以显式提供 `--Java`、`--Javac`、`--Jar`、`--AndroidSdk`、`--BuildToolsVersion`。每个变体独立裁剪其它地图实现并编译；共享面板、配置、目标地图转发入口、宿主白名单和必要功能类必须保留，构建期校验失败即停止。产物旁包含 `variant-verification.json`、LSPatch 许可和来源说明。

## 高德 Google Play 拆分包

准备同一次安装导出的 `base.apk`、`split_config.arm64_v8a.apk`、`split_config.xxxhdpi.apk`、`split_config.zh.apk`，放在同一目录。不要混用国内版 native 库或不同版本 split。

```powershell
python tools/build_variant.py --Target amap --OriginalApk path/to/play-splits --OutputDirectory output/nonroot-play-234 --ArtifactName Amap-Play-2.3.4-nonroot-embedded.apk --LspatchJar $jar --SignatureBypass 2
```

输出同名目录中的四个 APK，需共同安装。国内/Play 高德的功能模块 DEX 应一致，原宿主及 native 锚点独立校验。Play 内置版支持范围见发布说明，不能仅因 DEX 一致就认定所有宿主路径已经实机验证。

## 测试、源码归档与发布

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tests/run-android.ps1 -BuildDirectory 'ASCII_PATH_FROM_BUILD_OK'
python tools/build_source_zip.py --SourceDirectory path/to/clean-committed-tree --OutputZip output/MapAdKiller-2.3.4-source.zip
```

Android 回归需要 adb 设备；BuildDirectory 使用模块构建最后输出的 `BUILD OK src=...`。发布验证还须将内置包中的生产模块解出，使用独立测试 DEX 运行针对性测试，避免测试副本掩盖裁剪错误。

源码包从已审核的提交树生成，包含 Kotlin runtime、必要编译依赖、native 源码/库、变体桩和测试；排除设备抓取、个人配置、地图原包、输出 APK、凭据和私钥。公开 release 应附 SHA256SUMS、源码和第三方许可；发布前核对版本、证书及所有宿主内嵌模块，不通过则不发布。详见 [2.3.4 验证说明](docs/VALIDATION-2.3.4.md) 和 [回归依赖台账](docs/REGRESSION.md)。

## 2.3.4 安装兼容与生产回归

高德可通过 `--CompatibilityKeystore path/to/host.keystore` 生成标准、无重叠 ZIP 布局，口令从 `--CompatibilityPasswordEnv` 指定的环境变量读取。必须使用与已安装内置版一致的宿主签名，不能拿模块签名替代；该选项会改变外层 APK 字节，必须重新检查 SHA-256 与签名。它是兼容性缓解措施，POCO F3 / MIUI 14 / Android 13 的实机验收仍待反馈。Play 四个 split 必须使用同一签名并一起安装。

```powershell
pwsh -NoProfile -File tests/run-android.ps1 -BuildDirectory 'ASCII_PATH_FROM_BUILD_OK' -Serial DEVICE_SERIAL
python -m unittest discover -s tests -p test_check_anchors.py -v
python tests/run-production.py --ModuleApk output/MapClean-lsp-v2.3.4.apk --AmapApk output/fix-234-cn/Amap-2.3.4-nonroot-embedded.apk --AmapPlayDirectory output/fix-234-play/Amap-Play-2.3.4-nonroot-embedded --BaiduApk output/fix-234-baidu/Baidu-2.3.4-nonroot-embedded.apk --TencentApk output/fix-234-tmap/Tencent-2.3.4-nonroot-embedded.apk --Adb path/to/adb --Serial DEVICE_SERIAL --AndroidSdk path/to/sdk
```

生产回归工具只向 adb 临时目录写入独立测试 DEX 和生产模块副本，不安装地图、不修改地图用户配置，也不启用框架作用域。它分别测试完整模块及四类内嵌模块，并检查国内/Play 功能 DEX 是否一致；通过不等同于所有官方签名宿主的框架注入及 UI 验收。测试异常会输出完整堆栈和非零退出码。
