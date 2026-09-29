# 搜索页去广告 — 进度交接（v1.1.0 工作分支）

> **v1.1.0 追加**：用户报的两个 bug 已闭环 —— ①首页搜索框「文字流动/广告词」用
> `HotWordGuard` 在 BaseSearchBar 的两个词出口上精准替换成默认提示词；
> ②首页「去XXX 打车」打车浮窗按「卡内文案 + AJX item 结构」双判据整卡摘除。
> 开关 `hotword_off` / `ride_card_off`（默认开），真机双向验证通过。
> 证据、hook 点与日志见 [FIX-v1.1.0-hotword-and-ridecard.md](FIX-v1.1.0-hotword-and-ridecard.md)。

> 打包时间：2026-09-29 19:1x · 目标 App：高德地图 17.00.0.2005（com.autonavi.minimap）
> 本包 = `MapAdKiller-master` 全量源码 + `app/dist/MapAdKiller.apk` + `releases/MapAdKiller-v1.1.0-wip.apk`

---

## 1. 这一轮新增/改动

| 文件 | 状态 | 说明 |
|---|---|---|
| `app/src/.../SearchCleaner.java` | 新增（35 KB） | 搜索框内页去广告主体：结构指纹判页 + AJX 文案锚点判广告 + 视图层清扫 + 帧驱动持续钉住 |
| `app/src/.../DiagPage.java` | 新增 | 取证：debugLog 打开时按拍输出整页台账（类名/id/尺寸/文本/父链），交付版零开销 |
| `HomeTweaks.java` | 改 | ① 新增 `ResumeSink` 登记表 + 前 3 次 resume 心跳；② 文本入口 `onTextSet` 第一条就把文案交给 SearchCleaner |
| `Config.java` | 改 | 新增 `K_SEARCH_AD = "search_ad"`（默认开） |
| `MainActivity.java` | 改 | 设置页新增抽屉「高德 · 搜索框内页」开关 |
| `AmapHooks.java` | 改 | 第 ⑧ 步安装 SearchCleaner + DiagPage；新增 `installContainerProbe` 容器探测 |

## 2. 已经真机验证的事实（都是 dumpsys / uiautomator / logcat 实证）

1. **搜索全流程不跳 Activity**：点搜索框 → 搜索页，`topResumedActivity` 恒为
   `com.autonavi.map.activity.SplashActivity`；实际 resume 的页面容器是
   **`NewMapActivity`**（真机 `RESUME#1 act=NewMapActivity sinks=2`）。
   → 用 Activity 类名做页面作用域在这里不可行。
2. **搜索页整页是 AJX3 画布**：uiautomator 里只有
   `ajx3.widget.view.SpringHorizontalScrollView / List` + 一个
   `android.support.v7.widget.RecyclerView`，**节点资源 id 全是空的**
   （404 节点里带 id 的只有壳级容器 root_view / floating_layer / fragment_container）。
   → 现有 `ViewKiller` 那套「按 :id/ 名匹配」在搜索页命中率≈0。
   → 搜索页里那支输入框是**无资源名的原生 EditText**（ui xml: EditText [156,140][1026,203]）。
3. **首页那支「搜索框」不是 EditText**：是 LinearLayout + TextView(`txt_hotword`)，
   所以「树里有搜索域 EditText」可以当作搜索页的判据之一。
4. **同一个方法只能挂一个 Hook**（关键坑，真机撞出来的）：
   SearchCleaner 与 HomeTweaks 各自 hook 了一次 `Activity.onResume`，
   结果**只有先注册的那个回调会跑** —— SearchCleaner 的探针一条不出，
   HomeTweaks 的 TOOL-KEY 照常刷屏；TreeDump 同样 hook onResume，同样零输出。
   → 现在全局只保留 HomeTweaks 一处 onResume Hook，其余组件通过
     `HomeTweaks.addResumeSink()` 登记（已验证 `sinks=2`）。
5. 搜索页运营词（出行节 / 扫街券 / 券包 / 满减 / 会场 …）与首页同源下发，
   但本模块原有规则是**首页 /「我的」页作用域**（要认领 AJX 列表根），
   在搜索页既抢不到也不该抢 —— 所以独立成 SearchCleaner 这一层。

## 3. 还没坐实的一环（继续做就从这里开始）

- 真机上**还没抓到用户说的那支「流动广告」节点**：
  - 单账号复现不到（广告是概率下发；本地搜索页只出现历史记录 + 空白带）
  - 已知最像的一次是搜索落地页「高德邀你畅玩十一」会场流
    （顶部「高德邀你畅玩十一 × 可口可乐」轮播条 + 「十一扫街券限时放送」弹窗 + 底栏主会场/酒店民宿/甄选美食/旅游景区）
  - 该页顶栏带搜索框（与用户截图一致），所以判定为「搜索框内页广告」
- 当前 SearchCleaner 已能覆盖**文案类**运营卡；若广告是**画布绘制/WebView 渲染**，
  需要按下面的取证路径拿到它的原文，再补一行锚点即可。

## 4. 下一步取证路径（照做即可闭环）

1. 手机：设置页 → 打开「调试日志」；强停高德重开；手动走到**广告出现的那一刻**
2. `adb logcat -s LSPosedFramework | findstr MapAdKiller` 捞这几类行：
   - `RESUME#n act=... sinks=...` —— resume 时机
   - `PAGE . <类名> id=... WxH vis=V tv='...'` —— 整页台账（DiagPage 每 3 秒一拍，共 12 拍）
   - `SEARCH-TXT '...' 类名 id=... WxH < 父链` —— 搜索页每条文案 + 父链
   - `SEARCH-PROBE ... searchEdit/homeTab/searchId/ajxHScroll/ajxList => isSearchPage=` —— 判页依据
3. 把广告那几行的原文/类名丢回来，往
   `SearchCleaner.AD_TOKENS` / `CLASS_PREFIXES` / `ID_PREFIXES` 各补一行，
   重新 `.appuild.ps1` → `adb install -r` → 强停高德验证。

## 5. 构建 / 安装（照旧，无 Gradle）

```powershell
cd MapAdKiller-master\app
.\build.ps1            # 产物 app\dist\MapAdKiller.apk 与 releases\MapAdKiller-v1.1.0.apk
adb install -r app\dist\MapAdKiller.apk
adb shell am force-stop com.autonavi.minimap
```

- 版本号按要求**保持不变**：`module.prop` = 1.1.0 / versionCode 110，
  构建脚本仍按 `MapAdKiller-v1.1.0.apk` 落地。
- 原来的 1.1.0 产物已另存：`releases/MapAdKiller-v1.1.0-v1.1.0-prev.apk`
```
