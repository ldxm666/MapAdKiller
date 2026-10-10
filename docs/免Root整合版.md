# 免 Root 整合版构建

本页说明已实现的构建流程和设置入口。当前模块正在完成地图功能回归，免 Root 宿主启动、设置连接与导航等实机验证仍待进行；生成 APK 或静态检查通过不代表这些验证已经完成。

免 Root 构建使用 [JingMatrix/LSPatch v1.2](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2) 官方发布的命令行工具，支持 Android 9 及以上。脚本固定官方 CLI 的 SHA256，不使用旧版 API 100 补丁器。

运行需要 Python 3.10 及以上、Java 21 及以上、Android SDK build-tools。官方 v1.2 CLI 的 class version 是 65，Java 17 无法运行。原始地图 APK 请在本地自行提供，构建脚本不会下载地图，也不会安装、卸载应用或修改输入 APK。

```powershell
.\tools\build-nonroot.ps1 `
  -ModuleApk .\output\MapClean-lsp-v2.1.2.apk `
  -OriginalApks @('C:\APKs\amap-original.apk', 'C:\APKs\baidu-original.apk') `
  -OutputDirectory .\output\nonroot-integrated `
  -Java 'C:\JDK\bin\java.exe' `
  -AndroidSdk 'C:\Android\Sdk'
```

`integrated` 是默认模式。它分别生成高德和百度两份内嵌当前模块的地图 APK，首次启动使用模块默认配置，之后可在各地图内调整开关，不需要 Root 或 LSPatch 管理器。两份 APK 内部都有完整模块和 Native Hook 入口；不会把两家地图合成一个包名。

整合版设置入口在地图内：高德首页长按「更多工具」，百度长按底栏「我的」，即可打开 MapAdKiller 设置。两家地图也都支持长按底栏「首页」，作为隐藏上述入口后的备用方式；百度隐藏整个底栏后，还可长按首页固定搜索栏。入口只在 LSPatch 向宿主投递可写配置服务后启用；普通 Vector / LSPosed 模块使用独立模块设置页。

内嵌模式的配置数据库存在各地图内部。修改高德整合版的开关不会自动同步到百度整合版，应分别从各地图内部打开设置，或使用配置导出 / 导入迁移。单独安装 MapAdKiller 设置 APK，也不会自动修改这两份内嵌地图的设置。

开关修改后按设置页提示重启地图，地图标注等原生样式在重新启动后重新加载。更改内嵌模块代码需要使用新模块 APK 重新运行构建。构建报告会记录内嵌模块 SHA256，确保两家地图使用同一份新模块。

如需尝试管理器集中加载模块的方式，以同一条命令增加 `-Mode manager`，输出到另一目录：

```powershell
.\tools\build-nonroot.ps1 `
  -ModuleApk .\output\MapClean-lsp-v2.1.2.apk `
  -OriginalApks @('C:\APKs\amap-original.apk', 'C:\APKs\baidu-original.apk') `
  -OutputDirectory .\output\nonroot-manager `
  -Mode manager `
  -Java 'C:\JDK\bin\java.exe' `
  -AndroidSdk 'C:\Android\Sdk'
```

`manager` 模式仍然免 Root。它生成两份由 LSPatch 管理器加载模块的地图 APK，并提供官方管理器和当前 MapAdKiller 设置 APK。安装后可在 LSPatch 管理器中给两家地图启用 MapAdKiller；独立模块设置页的服务连接和配置共享需要实际设备验证。本次整合版的可调设置使用 `integrated` 宿主内服务。普通 APK 安装无需 Shizuku，Shizuku 仅用于管理器的自动安装能力。

官方工具不允许同时选择 `--manager` 和 `--embed`。内嵌模式与管理器模式分别构建、分别说明；内嵌模式的开关在各宿主内部调整。更新模块 APK 后，内嵌模式需要重建两个地图 APK；管理器模式可以更新模块，再重启地图，不必仅为模块更新重新补丁。管理器模式不会启用宿主内嵌设置入口，其独立设置服务还需要实际设备验证。

脚本会校验签名、包名与版本、补丁配置、原 APK 完整 SHA256、内嵌模块完整 SHA256、现代 Java/Native 入口、API 102、Native 库不压缩及 16 KB 对齐。报告 `nonroot-verification.json` 中的 `deviceRuntimeTested` 默认是 `false`：静态校验不能代替手机启动、登录、导航和地图效果测试。

签名绕过默认使用官方建议的级别 2。仅当实测宿主保护不兼容时才选择 `-SignatureBypass 3`；级别 3 会修改 arm64 原生代码，存在自校验不兼容的可能。

补丁后安装签名与地图官方原版不同，不能直接覆盖官方签名的安装。替换原安装前应由使用者备份需要的数据；脚本不执行卸载。登录、支付、升级和厂商完整性检查可能受到签名变化影响，需要在实际使用设备上验证。构建产物和原地图 APK 不应提交到源代码仓库。
