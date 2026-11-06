# 高德 17.02.0.2010 适配：落点清单与锚点核对（v2.4.0 工作稿）

目标：官方高德 **17.02.0.2010 / versionCode 170200 / targetSdk 35 / 单 APK / arm64-v8a**。
模块版本号**锁 2.3.1 / 2301**，未改。

---

## 一、落点（`文件#方法`）

### 1. 帧率上限（实验，默认关）

| 文件 | 方法 | 动作 |
|---|---|---|
| `app/src/io/github/ldxm666/mapadkiller/AmapFpsCap.java` | `Pacer#intercept` ← hook `GLMapEngine#OnEGLRenderFrame(II)V` | **节流点**。GL 线程 `GNaviMap-GL-0`，固定节拍 park |
| 同上 | `Pacer#intercept` ← hook `AChoreographer#doFrame(J)V` | 只计数不节流（实测频率 9–26 fps，不是渲染循环） |
| 同上 | hook `GLMapEngine#getAdviseFPS(I)I` | 返回目标帧率 |
| 同上 | hook `GLMapEngine#setMaxFps / setMinFps / setRenderFps / setViewRenderFps` `(II)V` | 改 args[1] |
| 同上 | hook `GLMapEngine#setRenderFpsWithTimer(IIZ)V` | 改 args[1] + args[2]=true |
| 同上 | hook `GLMapEngine#setFpsControllerEnable(IZ)V` | args[1]=false |
| 同上 | hook `GLMapEngine#OnEGLAutoDropFrame(IIZ)V` | 短路 |
| 同上 | hook `VMapSceneWrapper#setMaxRenderFPS / setMinRenderFPS` `(String,I)V`、`setGlobalMaxRenderFPS(I)V` | 改 fps 槽 |
| `app/src/io/github/ldxm666/mapadkiller/AmapHookArgs.java` | `arg` / `argPlusFlag` / `box` | 参数改写（一律 `Chain#proceed(Object[])`） |
| `app/src/io/github/ldxm666/mapadkiller/AmapTuneGate.java` | `on` / `forced` / `target` | 开关闸门 + `debug.mapclean.amap_tune` / `amap_fps` 调试通道 |
| `app/src/io/github/ldxm666/mapadkiller/Config.java` | `K_AMAP_FPS_ON` / `K_AMAP_FPS_TARGET`、`defaultVisible` | 新键 + 默认关 |
| `app/src/io/github/ldxm666/mapadkiller/MainHook.java` | `onPackageReady`（PKG_AMAP 分支） | 安装期门控 |

### 2. 去除建筑物（实验，默认关）

| 文件 | 方法 | 动作 |
|---|---|---|
| `app/native/amap_buildings.c` | `patch_thread` | **真正生效**：`libamapr.so` vtable `0x1f40848` 的 `[4]` 槽（`0x1f40868`）改写为 `0xe84148`（`mov x0,xzr; ret`），写前两重自检 |
| `app/src/io/github/ldxm666/mapadkiller/AmapBuildingPatch.java` | `install` / `load` / `loadFrom` | 加载 .so（linker → 宿主 nativeDir → 模块 APK 解压）+ Java 兜底 hook |
| 同上 | `javaHooks` ← `AMapController#setNormalBuildVisibility / setBuildTextureVisibility / setBldAndModelVisibility` `(IZ)V` | args[1]=false（兜底，本版不触发） |
| 同上 | `javaHooks` ← `VMapSceneWrapper#setProcessBuilding / setProcessBuildingNormal / setIsBuildNormalOn / setBuildTextureVisible` `(String,Z)V` | args[1]=false |
| 同上 | `javaHooks` ← `VMapSceneWrapper#setGlobalProcessBuilding / setGlobalProcessBuildingNormal / setGlobalIsBuildNormalOn / setGlobalBuildTextureVisible` `(Z)V` | args[0]=false |
| `app/native/build_buildings.py` | — | 构建 `libs/arm64-v8a/libamapbldpatch.so`（硬失败、ABI 校验） |
| `tools/build_variant.py` | `pack_native_libraries` / `verify_native_entries` | 泛化 native 打包 + STORED/非空/存在三重断言 |

### 3. 开屏广告（默认拦截，面板可关）

| 文件 | 方法 | 动作 |
|---|---|---|
| `app/src/io/github/ldxm666/mapadkiller/AmapAlimama.java` | `install` | 门控从 `Config.explicit()` 改为 `Config.visible()` ⇒ **默认拦** |
| 同上 | `block` ← `AlimamaRequestHolder#sendSplashScreen(SplashScreenRequest, falcon.base.a, AosResponseCallback)V` | 短路 + 补 onFailure 放锁 |
| 同上 | `block` ← `AlimamaRequestHolder#sendH5Log(H5LogRequest, falcon.base.a, AosResponseCallback)V` | 短路（不放锁） |

### 4. 去更新（默认生效，无开关）

| 文件 | 方法 | 动作 |
|---|---|---|
| `app/src/io/github/ldxm666/mapadkiller/AmapUpdateBlock.java` | `install` | 装两个 no-op |
| 同上 | `suppress(3)` ← `AppUpgradeController#w(Lcom/amap/bundle/appupgrade/e;ZZ)V` | 唯一调用方 = `AppUpdateCallBack.b(Object)`；不弹升级框 |
| 同上 | `suppress(1)` ← `AppUpgradeController#v(Z)V` | 另一个 AlertView 构造点 |

### 5. 两处面板入口（同一功能两个界面）

| 文件 | 位置 |
|---|---|
| `app/src/io/github/ldxm666/mapclean/AmapPanel.java` | `groups()` 新增 `Group("实验", …)` + `Metric(目标帧率)` —— 免 root 内置版悬浮面板 |
| `app/src/io/github/ldxm666/mapclean/MainActivity.java` | `newDrawer("高德 · 实验")` + `addChoiceRow(...)` —— **模块版配置页（LSPosed 用户唯一的入口）** |

---

## 二、锚点核对结果（对 17.02.0.2010 实际查询）

### 存在且签名未变（可直接沿用）

`GLMapEngine`：`getAdviseFPS(I)I`、`setMaxFps/setMinFps/setRenderFps/setViewRenderFps(II)V`、
`setRenderFpsWithTimer(IIZ)V`、`setFpsControllerEnable(IZ)V`、`OnEGLAutoDropFrame(IIZ)V`、
`OnEGLRenderFrame(II)V`
`VMapSceneWrapper`：`setProcessBuilding/setProcessBuildingNormal/setIsBuildNormalOn/setBuildTextureVisible(String,Z)V`、
`setGlobalProcessBuilding/setGlobalProcessBuildingNormal/setGlobalIsBuildNormalOn/setGlobalBuildTextureVisible(Z)V`、
`setMaxRenderFPS/setMinRenderFPS(String,I)V`、`setGlobalMaxRenderFPS(I)V`
`AMapController`：`setNormalBuildVisibility(IZ)V`、`setBuildTextureVisibility(IZ)V`、
`setBldAndModelVisibility(IZ)V`、`setMapCenter(III)Z`
`AlimamaRequestHolder`：`sendSplashScreen` 3 参 + 2 参、`sendH5Log` 3 参 + 2 参；`param/SplashScreenRequest`、`param/H5LogRequest`
`AppUpgradeController`（classes4，33 方法）：`w(e,ZZ)V`、`v(Z)V`

实测 `event=install_done pkg=com.autonavi.minimap ok=52 miss=0`。

### 新增（17.02 有、17.00 没有）

- `com.autonavi.jni.ae.gmap.vsync.AChoreographer#doFrame(J)V` —— 17.02 新增的 vsync 桥；
  实测**不是**渲染循环（9–26 fps），只当观察点。
- `GLMapEngine#setRenderFpsWithTimer` 在 17.02 **真的被调用**（17.00 零调用），
  所以 fps 参数改写这一层在本版不再是死代码。

### 缺失 / 签名变化

**本轮没有**。相较 17.00.0.2005，上述 Java 锚点一个不缺、签名一个没变。
唯一「消失」的是 native 偏移（全部重定位，见下）：
`0x159feec→0x18a7c6c`、`0x15a94d8→0x18b1684`、`0x1b93e08→0x1f40848`、
`0x15a9634→0x18b17e0`、`0x1b93e28→0x1f40868`。

---

## 三、验证状态（**不要当成全部通过**）

| 项 | 状态 | 证据 |
|---|---|---|
| native 补丁落点 | ✅ 真机 | `event=building_patch slot=0x6d787a3868 old=0x6d781147e0 new=0x6d776e7148 base=0x6d76863000`，三地址与 ELF 重定位逐一吻合 |
| .so 加载 | ✅ 真机 | `event=building_native from=linker` |
| 帧率 60 / 90 / 120 | ✅ 真机 | 60→60、120→119、90→80~82、关→117~120（模块 App 开关路径，`debug.*` 全空） |
| 模块 App 开关默认关 | ✅ 真机 | 截图 + `hook_skipped key=amap_building_on reason=switch_off` |
| 开屏广告请求拦截 | ✅ 真机 | `event=alimama_blocked … release_lock=true` + `event=alimama_callback_released` |
| 开屏页视觉 | ⚠ 未取证 | 拦/不拦都是无 logo 的白（见 `SPLASH_WHITE.md`），结论待定 A/B |
| 负向验证 `anchor_mismatch` | ❌ 未做 | 机制已静态证明：把 `0x18b17e0` 改成 `0x18b17e1` 后反汇编为 `mov w8,#0x17e1`；运行期待验 |
| 建筑 5m/10m/50m A/B | ❌ 未做 | 仅目视确认导航态无楼块 |
| 去更新手动检查 | ❌ 未做 | hook 已装（`HOOKED AppUpgradeController.w/.v`），未触发到一次真实调用 |
| 免 root 变体 | ✅ 本地 | 构建 EXIT=0，nested module 内 `.so` STORED；打包断言三例全部咬人 |
---

## 四、建筑去除：三档缩放同视角 A/B（固定视角脚本）

**要点：同一相机位置拍两次，只差一个开关。** 上一轮 AmapTune 就是在这里翻的车 ——
A/B 两张图一张 453 m、一张 283 m（车跑了 170 米），把"换了地方"当成"楼没了"。

### 固定视角的手段：deeplink 定坐标

```bash
# 固定到一个楼块密集的城市路口（成都春熙路），zoom 由后续手势统一决定
adb shell am start -a android.intent.action.VIEW \
  -d "androidamap://viewGeo?sourceApplication=ab&lat=30.6571&lon=104.0817"
```
deeplink 落点由坐标决定，与上一次的地图状态无关，所以两次跑必然同起点。

### 缩放三档（每条命令独立执行，**记录每档的捏合次数**）

高德无对外 zoom 接口，用捏合：
```bash
# 放大到 ~10 m 档（街区内，楼块最大）
adb shell input swipe 540 1500 540 900 300     # 先用滑动把地图挪到目标街区中心
# 捏合放大：用 MCP 的 perform_gesture action=pinch_out，或
adb shell input motionevent DOWN 540 1200 ; ...  # 复杂，优先用 MCP 手势
```
实操以 MCP 的 `perform_gesture` 为准（`action=pinch_out` / `pinch_in`），
每档记录 `pinch_out × N`，两轮必须用**完全相同的次数**。

### 两轮

| 轮次 | 前置 | 要拍 |
|---|---|---|
| **A（有楼块）** | `adb shell setprop debug.mapclean.amap_tune 0` → 强停高德 → 冷启 | 三档各一张 |
| **B（去楼块）** | `adb shell setprop debug.mapclean.amap_tune 1` → 强停高德 → 冷启 | 三档各一张 |

> 为什么用 `amap_tune` 而不是面板开关：面板开关是**安装期门控**，改它同样要重启，
> 但要多走一遍 UI；调试属性一条命令就切换，且不污染用户配置（测完必须清空）。

### 判定（不许自我说服）

1. 先对齐三档的两张图：**同一档的 A/B 必须能看出是同一个路口**（路网形状、POI 名称一致）。
   对不上就重拍，不要用"差不多"下结论。
2. B 轮里 3D 立体楼块与扁平楼块多边形**都消失**，路网 / 绿地 / 地名 / POI 保留。
3. 收尾：`setprop debug.mapclean.amap_tune ""`，强停高德再冷启一次，
   确认 `event=building_patch` 消失且楼块回来 —— **证明属性确实清干净了**。