# MapAdKiller v1.1.0 修复报告

> 目标设备：2509FPN0BC / Android 16 / KernelSU + ZygiskNext + LSPosed 2.1.1
> 目标版本：高德地图 17.00.0.2005 · 百度地图 22.0.0 (versionCode 1650) · 腾讯地图 11.6.0
> 模块版本：**1.1.0 / versionCode 110（本次修复未改动版本号）**
> 分析方法：纯 Python DEX 解析器（自建，见附录 A）+ LSPosed 真机日志 + adb screencap 逐帧取证

---

## 0. 本次交付解决的 4 个问题

| # | 用户报告 | 根因 | 状态 |
|---|---|---|---|
| 1 | 百度地图一启动就闪退 | 模块在安装期反射调用闸门，提前触发目标类静态初始化 → NPE | ✅ 已修 |
| 2 | 百度地图不能秒进 | 同上（闪退前的启动被拖到 25s+） | ✅ 冷启动 1.9s |
| 3 | SDK 更新后开屏广告依旧复现 | 百度闸门被降级成"只观察"；高德闸门是 16.23 的旧混淆名；腾讯锚点任务被断链 | ✅ 已修 |
| 4 | 自动补货 SDK 没啥用 | 拦截面过宽吞掉加载回调（导致卡开屏），且学习表基本学不到东西 | ✅ 重构 |

---

## 1. 百度地图 22.0.0（classes11/12/13.dex 反编译实证）

### 1.1 开屏广告完整链路

```
桌面图标 → WelcomeScreen.Alias0
            └─ WelcomeScreen.onCreate
                 └─ WelcomeScreen.t()                       ← 开屏广告的**唯一决策点**

t() 伪代码（classes11.dex，逐条与字节码对齐）：
    if (intent == null) return;                                   // offset 4-8
    if (!intent.getBooleanExtra("start_up_splash_flag", false))   // offset 9-18
        return;
    SplashAdManager.f();                                          // offset 19
    if (!SplashAdManager.F()) return;                             // offset 37-43  ★闸门 1
    if (!SplashAdManager.y()) return;                             // offset 44-50  ★闸门 2
    if (WelcomeScreen.o())  return;                               // offset 51-57
    ...
    SplashAdManager.G(ctx, hi.a)                                  // offset 218  ★真正发起广告请求
    SplashAdManager.L(true)                                       // offset 224
```

另一条入口（热启动 / 回首页）：

```
HomeSplashPresenter.n(String, boolean, String)   // classes12.dex
    ...  f/z 均为 true 才 new SplashViewContainer(ctx) → 存进字段 E
    SplashAdManager.G(ctx, params)
    SplashAdManager.n(ctx, isHotStart, container, openApiUrl, callback)
```

### 1.2 闸门是纯查询 —— 可以安全钉 false

| 方法 | 实现 | 调用者（全量） |
|---|---|---|
| `SplashAdManager.F()Z` | `com.baidu.baidumaps.splash.c.q()` | WelcomeScreen.t / HomeSplashPresenter.n |
| `SplashAdManager.z()Z` | 读静态布尔 `SplashAdManager.c` | WelcomeScreen.t / HomeSplashPresenter.n |
| `SplashAdManager.w()Z` | `com.baidu.baidumaps.splash.c.p()` | WelcomeScreen.t / operation.j（埋点） |
| `SplashAdManager.y()Z` | `d.get()`（AtomicBoolean） | WelcomeScreen.t / operation.j |

四个方法**都不驱动任何回调**，返回 false 时宿主走的是应用自己"没有广告"的原生分支
（`t()` 在第 43 字节直接 `return-void`），广告请求根本不会发出。

### 1.3 ⚠ 一级红线：绝不能在安装期反射调用这些闸门

```
FATAL EXCEPTION: main
Process: com.baidu.BaiduMap, PID: 25546
java.lang.NoClassDefFoundError: com.baidu.baidumaps.splash.c
    at com.baidu.baidumaps.splash.SplashAdManager.B(SourceFile:16)
    at eg.g.onCreate(SourceFile:308)
    at com.baidu.baidumaps.BaiduMapApplication.onCreate(Unknown Source:8)
Caused by: java.lang.ExceptionInInitializerError
    at com.baidu.baidumaps.splash.SplashAdManager.w(Unknown Source:4)
    at java.lang.reflect.Method.invoke(Native Method)
    at io.github.ldxm666.mapadkiller.BmapHooks.verifyGate(BmapHooks.java:211)
    at io.github.ldxm666.mapadkiller.BmapHooks.install(BmapHooks.java:80)      ← onPackageReady 阶段
Caused by: java.lang.NullPointerException:
  Attempt to invoke virtual method 'android.content.SharedPreferences
  android.content.Context.getSharedPreferences(String,int)' on a null object reference
    at com.baidu.platform.comapi.map.config.CachePreference.<init>(SourceFile:21)
    at com.baidu.platform.comapi.map.config.Preferences.build(SourceFile:4)
    at com.baidu.baidumaps.splash.util.SplashPreference$preferences$2.invoke(SourceFile:3)
    at com.baidu.baidumaps.splash.c.<clinit>(SourceFile:13)
```

**因果链**：`onPackageReady`（Application.onCreate 之前）反射执行 `w()`
→ 触发 `splash.c.<clinit>` → `SplashPreference` → `Preferences.build()`
→ `JNIInitializer.getCachedContext()` 此刻为 null → NPE
→ `ExceptionInInitializerError`，JVM 把 `splash.c` 的类初始化**永久标记为失败**
→ 之后 `BaiduMapApplication.onCreate → SplashAdManager.B()` 一碰 `splash.c` 就
`NoClassDefFoundError` → **闪退**。

**教训（写进代码注释）**：`onPackageReady` 阶段只允许"挂 hook"，
**绝不允许反射执行目标 App 的业务方法**。自检改成"闸门运行时首火校验"：

```java
private static XposedInterface.Hooker gate(final String name, final boolean want) {
    return new XposedInterface.Hooker() {
        private boolean logged;
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object observed = want ? null : chain.proceed();
            if (!logged) {
                logged = true;
                H.log(Log.INFO, MainHook.TAG, "BMAP gate " + name + "() observed=" + observed
                        + (want ? "  -> forced false" : "  (passthrough)"));
            }
            return want ? Boolean.FALSE : observed;
        }
    };
}
```

### 1.4 v1.1.0 卡开屏的真凶不是闸门

- **真凶**：当时把 `IAdLoader.I(...)` 吞掉了。反编译确认那是 ADN 的
  **回调完成方法**（`onAdClose` / `onTimeOver` / `onAdDismissed` / `onAdSkip`），
  吞它 = 回调永不到达 = 宿主死等 = 卡开屏（按返回键才进得去）。
- **v1.1.0 的误判**：把闸门降级成 `OBSERVE`，于是广告 100% 复发（真机日志
  `OBSERVE SplashAdManager.F() -> true`）。
- **正确组合**：闸门强制 false（断源）+ ADN 回调入口只观察不吞（保回调）。
  两者叠加时 `G()` 根本不会被调用，回调链路自然不会成为问题。

---

## 2. 高德地图 17.00.0.2005（classes5/6.dex）

### 2.1 旧 hook 已失效

```
miss  za6.g (no such sig)      ← 旧版按 16.23 的混淆名硬挂
HIT   amap_gate_u96            ← u96 仍在且签名还能挂上（保留为兼容层）
```

`SplashScreenServiceImpl.tryShowSplashView(int, String)` 在 17.00 里只是**转发壳**：

```
tryShowSplashView(I,String)  →  com.autonavi.minimap.g.o(int, String)
```

### 2.2 真正的决策入口 g.o() 与其语义闸门

```
g.o(int, String)
  ├─ if (SplashState ∈ {SHOWING, LANDING}) → g.e(reason) 收尾
  ├─ if (SplashState == FINISHED)          → g.e(reason) 收尾
  ├─ if (isForbidSplashScene)              → g.e(NO_SPLASH)
  ├─ if (!SplashFrequencyController.a/b()) → g.e(NO_SPLASH)
  ├─ BootBizDataPreloader.canShowSplash()  ← ★语义闸门
  │      Boolean == null      → 跳过实时广告段
  │      !a() && !b()         → g.e(NO_SPLASH)   ← 应用自家"无广告"分支
  ├─ …（此处才 new ke5 / 注册 RealTimeFetchCallback / g.m / g.i）
  └─ g.e(SplashFinishReason) 收尾
```

`canShowSplash()` 的**全量调用者只有 3 处**：
`g.o()`（开屏决策）、`com.autonavi.minimap.lite.a.loadPage()`、`eo6.doBizLogic()`（首页预加载）。

### 2.3 必须挂实现类，不能挂接口

`com.autonavi.bundle.amaphome.api.BootBizDataPreloader` 是**接口**（access=0x601），
`canShowSplash()` 是 abstract 方法 → `hookAll` 会跳过 → 日志打
`miss … canShowSplash MISS`。

**实现类是 `com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl`**（classes4.dex，extends WingBundleService）。

修复后真机命中：

```
HIT amap_cansplash_impl#BootBizDataPreloaderImpl#0
```

### 2.4 17.00 仍在的 hook 点（保留）

| 类 | 方法 | 作用 |
|---|---|---|
| `SplashScreenServiceImpl` | `fetchRealTime()` | 实时开屏广告拉取（VOID） |
| `SplashScreenServiceImpl` | `isSplashShowing()` | 状态查询（FALSE） |
| `SplashScreenServiceImpl` | `showSplashMaskView()` | 遮罩展示（VOID） |
| `impl.SplashScreenServiceImpl` | `addContinueLaunchMaskView(Activity,J)` | 续播遮罩（未挂，可后续加） |
| `component.SplashContainerView` | `dispatchDraw/onInterceptTouchEvent…` | 开屏容器（**不覆写 addView**，靠 decor 扫描兜底） |
| `BannerManager` / `BannerParser` / `BackgroundMsgManager` | — | 首页 banner / 运营弹窗 |
| `NativesModuleSplashScreen` | `getLinkageMsg/getCurrentLinkageMsg` | 开屏→AJX 联动数据 |
| `search.inter.splash.SplashModel` | `getData/getTemplate/getXmlUrl/getCssUrl` | 搜索页模板开屏 |

---

## 3. 腾讯地图 11.6.0（classes3/12/18/19.dex）

### 3.1 包内 ADN 实证（类普查）

```
com.qq.e.comm.managers.GDTADManager          initWith(Context,String)Z / isInitialized()Z
                                             initPlugin()V / preRequestDNS()V / getPM() / getSM()
com.qq.e.tg.splash.TGSplashAD                优量汇开屏插件（Tangram）
com.qq.e.comm.plugin.tangramsplash.*         1029 个类
com.tencent.ams.fusion.*                     融合 SDK
com.tencent.map.ama.splash.SplashService     宿主开屏服务
com.tencent.map.init.tasks.optional.SplashRequestTask.run()
com.tencent.map.init.tasks.DecodeSplashTask.run()
com.tencent.map.launch.v2.task.t2.SplashManagerInitTask.run()
```

### 3.2 ⚠ SplashManagerInitTask.run() 不能吞

```smali
SplashManagerInitTask.run()V
    invoke-static {}, com.tencent.map.navisdk.a.eta->b(Runnable)V   # 只往主线程丢一个 lambda
    return-void
```

但它的父类 `com.tencent.map.launch.starter.AnchorTask` 是**启动任务图的锚点**：

```
AnchorTask: ensureCountDownLatch() / await() / countdown() / onFinish() / beforeCountDownLatch()
```

把 `run()` 整个吞掉 = 锚点任务永远不 `onFinish` = 启动任务图悬挂 → "卡开屏"。
**改为放行（只 observe）**；纯预取的 `SplashRequestTask.run()` / `DecodeSplashTask.run()` 继续拦。

### 3.3 TGSplashAD 真实 API（11.6.0）

```
fetchAdOnly()V          ← 纯预取，可吞
preLoad()V              ← 纯预取，可吞
hasPlayedToday()Z       ← 只读查询，可钉 true
fetchAndShowIn(ViewGroup)V   ← 拉取并显示，只观察（吞了回调不来）
```

---

## 4. "自动补货 SDK"（SdkAutoBlock）重构

### 4.1 旧实现的三个硬伤

| 硬伤 | 后果 |
|---|---|
| `AD_METHODS` 含 `load / loadAd / show / fetchAd / init / initialize`，对每个命中前缀的类全吞 | 广告流程已启动但回调不来 → 卡开屏；`show` 被吞 → 弹窗永久占位；`init` 被吞 → SDK 半初始化 → NPE |
| Context 替换判定 `name.contains("start")` | 命中 `startActivity` / `startService` / `getContextWrapper` → 百度闪退 / 腾讯 ANR |
| 学习表只学到 2 个厂商；高德 1906 类扫出 0 个 | 基本没干活 |

### 4.2 新策略：只做两类安全动作

```
① 只读能力闸门（返回 false，完全不动回调链）
   isInitialized / isInit / isSdkReady / isAdReady / canLoadAd / canShowAd
   isReady / isLoadSuccess / hasInit

② 纯初始化入口（空转，不把任何对象交给宿主）
   initSDK / initializeSdk / startWithAppId / startWithAppID

③ Context 替换（严格收窄）
   仅 init* / setup* / *WithAppId 且签名里**确实有 Context 入参**
```

发现阶段（扫 dex 字符串表反推厂商根包名）**保留，但只用于记录**，
不再对"学到的厂商"批量下 hook —— 那是误伤的主要来源。

### 4.3 实测收益（百度 22.0，103MB dex）

| 指标 | 旧 | 新 |
|---|---|---|
| 命中并挂上的方法数 | 73 | **15** |
| dex 扫描耗时 | 5.6 s | **2.2 s** |
| 误伤面 | 大（load/show/init 全吞） | 仅只读查询 + 纯初始化入口 |

---

## 5. SplashProbe —— 三家共用的开屏广告探针（新增）

### 5.1 判定表

类名词根（**全部长词根，禁止 "ad"/"ads" 这种 2-3 字母短串** —— 会命中
`AdapterView`、`AddressPicker` 之类的正常控件）：

```
国内：qq.e / gdtad / tangramsplash / bytedance.sdk / pangle / ttadview / csj / gromore /
      bytedance.msdk / kwad / ksad / kuaishou.ad / baidu.mobads / qumeng / advlib /
      splashcountdown / sigmob / mintegral / mbridge / meishu.sdk / beizi.fusion /
      alimm.tanx / tradplus / klevin / windmill / anythink / topon / jd.ad.sdk /
      youxiao.ssp / octopus.ad / mimo.sdk / smartdigimkt / vlion.ad / ubix / taku.ad /
      wangmai / ruishi / huawei.hms.ads / openalliance.ad
海外：applovin / ironsource / vungle / unityads / facebook.ads / fyber / smaato /
      yandex / google.android.gms.ads
广告位命名：splashadview / adloader / iadloader / adcontainer / adcardview /
            banneradview / operationbanner / splashview
```

角标文本（**整串相等**、长度 ≤ 8，避免命中"广告过滤"这类正常文案）：
`跳过 / 跳转 / 广告 / 关闭广告 / skip / ad`

### 5.2 三个入口，逐层兜底

1. **容器 addView 探测**（百度 `SplashViewContainer`）——帧驱动 90 帧，命中即摘
2. **Activity.onResume 后自动清扫**——600ms / 1600ms / 3000ms 三轮扫整个 decor
3. **帧驱动预绘制探测**——`OnPreDrawListener` 连续盯 30~90 帧

动作统一为"**只摘广告子树，容器与品牌层原样保留**"（`SplashProbe.stripAdChildren`，
命中节点必须是该子树前 4 层，避免误摘整块）。

### 5.3 为什么不能只挂容器方法

高德的开屏容器 `com.autonavi.minimap.component.SplashContainerView`
**只声明了 `dispatchDraw` / `doExistAnim` / `onInterceptTouchEvent` / `recordUserTrack` /
`reportUserTrackIfNeeded`**，压根不覆写 `addView` —— 挂容器方法会得到 `hooks=0`。
所以必须有 decor 级扫描兜底。

---

## 6. 真机验证记录（ADB + LSPosed 日志）

### 6.1 修复前的闪退（完整堆栈）

见 §1.3。

### 6.2 修复后

```
==== 百度地图 ====
crash lines: 0
topResumedActivity=ActivityRecord{…com.baidu.BaiduMap/com.baidu.baidumaps.MapsActivity}
MapsActivity resumed after 1.9s                    ← 秒进
HOOKED  com.baidu.baidumaps.splash.SplashAdManager.F()Z*(1)
HOOKED  com.baidu.baidumaps.splash.SplashAdManager.z()Z*(1)
HOOKED  com.baidu.baidumaps.splash.SplashAdManager.w()Z*(1)
HOOKED  com.baidu.baidumaps.splash.SplashAdManager.y()Z*(1)
HOOKED  com.baidu.baidumaps.WelcomeScreen.t*(1)
HOOKED  com.baidu.baidumaps.operation.splash.HomeSplashPresenter.n*(1)
BMAP splash activity sweep installed
event=install_done pkg=com.baidu.BaiduMap ok=31 miss=3
HIT bmap_gate_F#SplashAdManager#0
BMAP gate F() observed=null  -> forced false       ← 运行时校验：值真的是 false
HIT bmap_gate_w#SplashAdManager#0
BMAP gate w() observed=null  -> forced false
SDK discover pkg=com.baidu.BaiduMap classes=1906 hooked=15 … dex=103MB in 2201ms

==== 高德地图 ====
crash lines: 0
event=install_done pkg=com.autonavi.minimap ok=17 miss=2
HIT amap_cansplash_impl#BootBizDataPreloaderImpl#0  ← 语义闸门真命中
HIT amap_gate_u96 → AMAP splash forced NO_SPLASH     ← 兼容层也命中
amap splash activity sweep installed

==== 腾讯地图 ====
crash lines: 0
AppIcon resumed after 1.8s
event=install_done pkg=com.tencent.map ok=13 miss=0
tmap splash activity sweep installed
```

`miss` 行的含义：`BMAdSplashProvider.x` / `BMAdNativeProvider.x` / `BMAdRewardProvider.x`
（22.0.0 已删除该方法）、`za6.g`（16.23 旧签名，兼容层）、
`BootBizDataPreloader.canShowSplash`（abstract 接口方法，必须挂实现类）。
**均为预期的 no-op，不影响功能。**

---

## 7. ⚠ 装机必读：LSPosed 模块 dex 缓存

真机实测现象：模块 APK 已更新（设备 dex 与本地构建 sha256 完全一致），
但目标 App 里跑的仍是**上一版代码**：

```
HOOKED  com.baidu.baidumaps.splash.SplashAdManager.F()Z*(1)
HIT bmap_gate_F#SplashAdManager#0
OBSERVE SplashAdManager.F() -> true      ← 这行字符串在新 dex 里根本不存在
```

**正确姿势**：

```bash
adb uninstall io.github.ldxm666.mapadkiller     # 先卸干净，清掉 LSPosed 缓存
adb install -r MapAdKiller-v1.1.0.apk
# LSPosed 管理器里重新启用 + 勾选 高德/百度/腾讯
adb shell am force-stop com.baidu.BaiduMap      # 普通 App 作用域无需重启系统
```

**验证新代码生效**：`adb logcat -s LSPosedFramework | grep MapAdKiller`，应看到
`BMAP gate F() observed=null  -> forced false`。看到旧版的
`OBSERVE SplashAdManager.F() -> true` 就是缓存没清，重来一次。

---

## 8. 已知边界

| 边界 | 说明 |
|---|---|
| 服务端概率下发 | "某次没广告"不能作为验证依据，必须看日志 |
| 百度首页左上角"新鲜事"浮动气泡 | 百度自家轮播位，内容走 WebView，SDK 层拦不到（可按宿主资源 id 兜底，未启用） |
| 百度首页"爱去榜/旅游攻略" | 百度自家内容推荐，非广告 SDK 下发 |
| 高德"探索"tab 内嵌 AJX 画布 | 原生视图层无法定位 |
| 腾讯"新人福利"首页横幅 | 宿主自有运营位，可后续按资源 id 加规则 |
| 混淆名漂移 | 目标 App 大版本更新可能重排方法名，未命中会在日志输出 `miss` 行 |

---

## 附录 A：本次使用的分析工具链

没有用 jadx（18 dex / 167K 类，CLI 跑 --single-class 单类也超过 10 分钟）。
改为**自建纯 Python DEX 解析器**（约 500 行），可直接产出伪反汇编：

| 模块 | 作用 | 耗时 |
|---|---|---|
| DEX header 解析（string_ids / type_ids / proto_ids / field_ids / method_ids / class_defs） | 类与成员枚举 | 18 dex / 140K 类 ≈ 3 s |
| ULEB128 class_data 解析 | 方法表 + code offset | — |
| 指令解码器（覆盖 0x00–0xE3 全部 opcode + format 表） | 伪反汇编，含 invoke 目标全名 | — |
| 调用图索引（target method → callers） | 找"谁调用了它" | 百度 627K targets / 47 s |

产出文件在本次会话的工作目录 `work/mak/`：
`dexall.py`（解析+反汇编）、`bmap_callidx.pkl` / `amap_callidx.pkl`（调用图索引）、
`apk/bmap.apk` / `amap.apk` / `tmap.apk` + 解包 dex。

关键查询示例（找闸门全量调用者）：

```python
import pickle
idx = pickle.load(open("bmap_callidx.pkl","rb"))
for t, lst in idx["calls"].items():
    if "SplashAdManager->F()" in t:
        print("->", t)
        for c, m in sorted(set(lst)):
            print("     by", c, "::", m)
```

---

## 附录 B：本次改动的文件

| 文件 | 改动 |
|---|---|
| `BmapHooks.java` | 删除安装期反射自检（闪退元凶）→ `gate()` 运行时校验包装器；闸门 `F/z/w/y` 强制 false；watchdog 判据重写；容器探测接 SplashProbe |
| `AmapHooks.java` | 新增语义闸门 `BootBizDataPreloaderImpl.canShowSplash()=false`；`g.e()` 观察；容器探测框架；decor 清扫接入 |
| `TmapHooks.java` | `SplashManagerInitTask.run()` 改放行；TGSplashAD 真实 API；GDT `getPM` 观察；decor 清扫接入 |
| `SplashProbe.java` | **新增**：共用广告探针 + decor 级清扫 + 帧驱动探测 |
| `SdkAutoBlock.java` | 拦截面从"通用 load/show/init"收窄为"只读闸门 + 纯初始化入口"；Context 替换严格化；已知 SDK 表扩充 |
| `build.ps1` | 临时构建目录改到仓库内 ASCII 路径（原 `%TEMP%` 在沙箱下不可写） |
| `CHANGELOG.md` | 新增「v1.1.0 修订」+「紧急修复（闪退）」两段完整复盘 |
| `README.md` | 功能表更新；新增"更新模块必须卸载重装"的装机提示；已知边界修正 |
| `docs/FIX-REPORT-v1.1.0.md` | **新增**：本报告 |

**版本号全程未改动**：`module.prop: version=1.1.0 / versionCode=110`；
`AndroidManifest.xml: versionName=1.1.0 / versionCode=110`。

---

## 附录 C：构建

```powershell
cd app
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
# 产物：app\dist\MapAdKiller.apk 与 releases\MapAdKiller-v1.1.0.apk
```

工具链：JDK（`D:\JDK`）+ Android SDK build-tools 35.0.0（aapt2 / d8 / zipalign / apksigner）。
临时构建目录 `D:\pojie应用\new2\work\makbuild\makb<时间戳>`（纯 ASCII，避免非 ASCII 路径坑死原生工具）。

当前构建：**136,010 字节**，签名 v1+v2+v3，证书 SHA-256
`cbecdaeb31836c4ab2ffe9271b47148810d1335f3a70782dc9d2d29d986554ed`。
