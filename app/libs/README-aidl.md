# libxposed-service-aidl.dex 来源说明（provenance）

## 这是什么

`classes2.dex` 的内容：`io.github.libxposed.service` 下的 **AIDL 桩类**，共 13 个：

```
IXposedService{,$Default,$Stub,$Stub$Proxy,$_Parcel}
IHotReloadCallback{,$Default,$Stub,$Stub$Proxy}
IXposedScopeCallback{,$Default,$Stub,$Stub$Proxy}
```

## 为什么必须自带

模块的**设置页 App 进程**不会被 LSPosed 注入（LSPosed 只注入模块作用域内的目标 App）。
而 `libs/service-classes.jar` 里只有上层封装（`XposedServiceHelper` / `XposedProvider` /
`RemotePreferences`…），**不含 AIDL 桩**——AIDL 在受注入的进程里由框架提供。

于是在我们自己的 App 进程里：

```
D XposedProvider: binder received: android.os.BinderProxy@...
E XposedServiceHelper: java.lang.NoClassDefFoundError:
    Failed resolution of: Lio/github/libxposed/service/IXposedService$Stub;
  at io.github.libxposed.service.XposedServiceHelper.onBinderReceived(XposedServiceHelper.java:40)
  at io.github.libxposed.service.XposedProvider.call(XposedProvider.java:65)
Caused by: java.lang.ClassNotFoundException: io.github.libxposed.service.IXposedService$Stub
```

daemon 明明把 binder 推过来了，we 却在解析 AIDL 时抛异常 → `onServiceBind` 永不回调
→ 设置页恒显「服务未连接」、开关写不进去。真机 LSPosed 2.1.1 / Android 16 实测。

## 提取方式（可复现）

来源是**已被真机证明可用**的成品：`_src/MapAdKiller-master/releases/MapAdKiller-v1.1.0.apk`
（它的设置页能正常显示「已激活」，即该 dex 与台上这套 LSPosed 的 AIDL 事务码匹配）。

```powershell
apktool d -f -o .scratch\work\makdec <该 apk>
# 只保留 AIDL 类，其余 smali 全部删除（避免与本模块 classes.dex 重复定义）
#   保留：smali\io\github\libxposed\service\{IXposedService,IHotReloadCallback,IXposedScopeCallback}*.smali
apktool b .scratch\work\makdec -o .scratch\work\aidl.apk
# 取 apk 里的 classes.dex（13740 字节，dexdump 验证：正好 13 个类，无重复）
```

固化脚本：`scripts/tools/extract_libxposed_aidl.ps1`（台账 REGISTRY.md 已登记）。

## 校验

```
dexdump -f libs\libxposed-service-aidl.dex   # 应只有上面 13 个 Class descriptor
```

**不要**替换成别处下载的同名 jar：AIDL 事务码必须与设备上运行的 LSPosed 版本一致。
换 LSPosed 大版本时，用上面同一套步骤从当时能正常工作的模块 APK 重新提取。
