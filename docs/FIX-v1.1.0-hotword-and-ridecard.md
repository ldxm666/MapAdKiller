# v1.1.0 — 搜索框热词轮播 & 首页打车浮窗（DEX 实证 + 真机验证）

> 目标：高德地图 17.00.0.2005（com.autonavi.minimap）
> 证据来源：设备实拉 base.apk（195,437,758 B）→ jadx 反编译 → 真机 DiagPage 树台账 + logcat
> 验证设备：KernelSU + LSPosed 2.1.1 / Android 16 / 1080x2400

---

## 1. 问题一：搜索框里的文字会「流动」，有时是广告

### 现象
首页搜索框（`com.autonavi.minimap:id/txt_hotword`）里的字自己换：
「灌县古城」→ 下一个预置词 → …（预置词由服务端下发，混着运营/广告词，带品牌小图标）。

### DEX 实证（jadx）
目标类：`com.autonavi.bundle.amaphome.components.searchbar.BaseSearchBar`
（运行时子类 `com.autonavi.bundle.amaphome.components.SearchBarV2`，
 真机树：`SearchBarV2 → DtLinearLayout → DtLinearLayout(layout_searchbar_content) → DtTextView(id=txt_hotword)`）

所有写进 `mHotWordTxtView` 的路径**只有两个出口**：

| 方法 | 谁在调 |
|---|---|
| `private void setHotWordTxt(String word, String color, c95 data, boolean anim[, PlayAnimViewListener])` | `setPreSetWord(...)` 的 6 个分支（新词 / 频控 / 空词 / 轮播）全部汇到这里；方法尾部还写 `PreSetWordManager.b().c = word` |
| `private void setPreWordTextView(String word, boolean z, boolean z2[, PlayAnimViewListener])` | 上面两个重载都调它；`refreshHotWordTxt()`（onResume 贴回上次的词）也调它。再往下才是 `PreSetWordAnimManager.a(view, word, …)` —— 轮播动画那一层 |

### 精准 hook
`HotWordGuard.install(cl)` 挂 **BaseSearchBar 上这两个方法名的全部重载**（共 4 条），
命中时把**第一个 String 参数**换成 App 自己的默认提示词：

```
com.autonavi.minimap:string/title_search_hint = "查找地点、公交、地铁"
```

- 运行时用 `getIdentifier` 解析（不硬编码资源 id），失败回退同串字面量；
- `chain.proceed(Object[])` 只调一次（proceed 放在 try 之外，异常交给框架 protective 模式）；
- 附带把 `mHotWordIconImg` / `mRightHotWordIconImg`（热词品牌图标）GONE，每实例只做一次；
- `PreSetWordManager.b().c` 里存的也变成了默认词，所以 resume 回来仍是静态文本。

### 真机日志
```
HOOKED  com.autonavi.bundle.amaphome.components.searchbar.BaseSearchBar.setHotWordTxt*(2)
hotword guard installed hooks=4
HOTWORD-FREEZE '灌县古城' -> '查找地点、公交、地铁' (轮播/运营词已关闭)
PAGE ... DtTextView id=txt_hotword 420x56 vis=V tv='查找地点、公交、地铁'
PAGE ... DtImageView id=img_hotword_icon      0x0   vis=G
```

### 开关
设置页 →「高德 · 首页推荐内容」→ **关闭首页搜索框热词轮播（文字流动 / 运营广告词）**
（键 `hotword_off`，**默认开 = 关闭**；关掉开关即恢复原热词轮播，已真机双向验证）。

---

## 2. 问题二：首页总会冒出「去XXX 打车」打车浮窗

### 现象
首页信息流底部那张卡：标题「去幸福路步行街」+ 标签「有座不拥挤 / 行程有保障」+ 按钮「打车」。

### 真机树实证（DiagPage / uiautomator）
```
uiautomator: ViewGroup [37,1934][1043,2176]   ← 整张卡 1006x242
AJX LBL 台账:
  LBL 去幸福路步行街 :: Html(613x55) > Container(613x123) > Container(1006x210) ×4
                        > Container(1006x242) > AjxAbsoluteLayout(1006x242)   ← 列表 item 根
  LBL 有座不拥挤     :: Html(150x39) > Container(617x47) > Container(617x58) > … 同一张卡
  LBL 行程有保障     :: 同上
```

### 为什么只能走视图层（不是偷懒）
- 卡内文案**服务端下发**：`有座不拥挤 / 行程有保障` 在 8 个 dex 的字符串表、`resources.arsc`、
  以及 APK 内所有 assets/JS 里**都不存在**（已全量扫描）；`ride_card/carCard` 命中的是
  小米运动卡 / 华为 HiCar，与此卡无关；
- 卡片本身是 AJX/JS 渲染（`ajx3.widget.view.Html/Container`），Java 层没有可过滤的数据结构
  （`hookListAdapter` 早已注明：数据在 JS 引擎里）。

### 精准 hook（双判据，缺一不可）
`HomeTweaks.rideCardOf(t, v)`：

1. **文案锚点**：`有座不拥挤 / 行程有保障 / 随叫随到 / 立即用车 / 一键叫车 / 去打车 / 打车券`，
   或 `去XXXX` 标题（3~22 字）；标题型**必须**同一张卡里还有打车 CTA（`打车 / 立即用车 / 一键叫车…`）；
2. **结构闸门**：上溯到 AJX 列表 item 根（`AjxAbsoluteLayout`，父级是 AjxList/RecyclerView），
   宽 ≥ 70% 屏宽、高 ≤ 40% 屏高 —— 只可能是横向整宽浮窗，不可能是整页容器；
   尺寸还没出来时（AJX 先绑视图后量尺寸）只允许**服务保障标签**先手摘除。

命中后走模块既有的 `hideItem(item, rule)`：GONE + 高度归零 + 弱引用记账 + 每轮 reassert 压回。

两条触发路径：
- `onTextSet`（AJX 文本落点，首帧之前，卡片一次都画不出来）；
- `applyPass → rideCardSweep()`（锚点表兜底复扫，item 被 AJX 重建后重新定位）。

### 真机日志
```
BIND-HIDE ride_card_off [37,391 1006x242]
RIDE-CARD-SWEEP '有座不拥挤' [37,391 1006x242]
RIDE-CARD-SWEEP '去幸福路步行街' [37,391 1006x242]
```
（复扫后建设路/幸福路步行街那张卡在树里彻底消失，不留空洞）

### 开关
设置页 →「高德 · 首页推荐内容」→ **关闭首页「去XXX 打车」打车浮窗（打车运营卡）**
（键 `ride_card_off`，**默认开 = 摘除**；关掉开关即恢复该卡）。

---

## 3. 改动文件

| 文件 | 说明 |
|---|---|
| `Config.java` | 新增 `K_HOTWORD_OFF` / `K_RIDE_CARD_OFF`（默认 true = 生效） |
| `HotWordGuard.java` | **新增**：BaseSearchBar 热词出口的精准 hook |
| `HomeTweaks.java` | 新增 `ANCHOR_RIDE_CARD` / `RIDE_CTA` / `rideCardOf` / `ajxItemOf` / `subtreeHasAnyText` / `rideCardSweep`；onTextSet + applyPass 接线 |
| `AmapHooks.java` | 安装步骤 ⑨：`HotWordGuard.install(cl)` |
| `MainActivity.java` | 两个新开关 + 说明行；页脚版本 v1.1.0 |
| `build.ps1` | 构建临时目录改走 `$env:TEMP\makbuild`（原路径在工作区外，沙箱不可写） |

## 4. 验证步骤（复现即可）

```powershell
cd MapAdKiller-master\app
.\build.ps1
adb install -r .\dist\MapAdKiller.apk
adb shell am force-stop com.autonavi.minimap
adb shell am start -n com.autonavi.minimap/com.autonavi.map.activity.SplashActivity
adb logcat -s LSPosedFramework | findstr "MapAdKiller" | findstr "HOTWORD-FREEZE RIDE-CARD"
```

预期：
- `txt_hotword` 恒为「查找地点、公交、地铁」，不再流动；
- 树里不再出现「去…」+「打车」浮窗卡；
- 关掉任一开关并强停重开，对应行为立即恢复。
