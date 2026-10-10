# libxposed API 102 依赖与 AIDL 来源

当前构建直接使用 Maven Central 发布的完整官方 `102.0.0` AAR 中的 `classes.jar`。各 JAR 与对应官方 AAR 的 `classes.jar` 逐字节一致，不再从历史模块 APK 提取 AIDL DEX。

| 本地文件 | 官方坐标 | 构建用途 |
| --- | --- | --- |
| `api-102.0.0.jar` | `io.github.libxposed:api:102.0.0` | 仅编译；由框架提供运行时 API，禁止打入模块 APK |
| `service-classes.jar` | `io.github.libxposed:service:102.0.0` | 设置页服务客户端、Provider、RemotePreferences；合并进模块 DEX |
| `interface-102.0.0.jar` | `io.github.libxposed:interface:102.0.0` | 完整官方 AIDL、`HookedProcess` 及 Parcelable；合并进模块 DEX |

`app/build.ps1` 的 javac 使用三份 JAR，d8 只合并模块代码、service 和 interface；API JAR 作为 `--lib`。无需手工复制 `classes2.dex`。d8 根据容量生成的 DEX 文件编号不代表 AIDL 的来源。

原 `libxposed-service-aidl.dex` 只含历史提取的 13 个桩类，缺少 API 102 的 `HookedProcess` Parcelable，已经移除，不参与构建。旧版“从可运行 APK 抽取并随框架版本替换 AIDL”的步骤不再适用。

构建使用官方 `io.github.libxposed.service.IXposedService` 协议；Vector 内部 IPC 包名变化不应改写此公共协议。依赖与元数据的静态核验不能替代具体框架设备测试。

官方 AAR 下载地址：

- [API 102.0.0](https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar)
- [Service 102.0.0](https://repo.maven.apache.org/maven2/io/github/libxposed/service/102.0.0/service-102.0.0.aar)
- [Interface 102.0.0](https://repo.maven.apache.org/maven2/io/github/libxposed/interface/102.0.0/interface-102.0.0.aar)

复现时从相应 AAR 中提取 `classes.jar`，保存为上表本地文件名，并核对 `dependencies.json` 所列的官方 AAR 与提取后 JAR 的 SHA256。正式构建后运行 `tools/verify_module.py`，核对签名、API 是否误打包、完整 service/interface 类、作用域和 Native Hook 库。
