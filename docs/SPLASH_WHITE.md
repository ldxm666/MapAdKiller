# 开屏白屏：先定性，再动手

目标机型状态：小米 13 Pro（nuwa）/ Android 14 / 高德 **17.02.0.2010**（versionCode 170200）/ LSPosed 模块模式。

> 用户原话：「开屏白屏问题请根除，之前就是白屏导致卡屏进入地图非常慢导致！」
> 这句话里其实混了两件事：**白屏**（视觉）和**进入地图慢**（耗时）。本文件先把两者拆开，
> 再决定要不要动手。

---

## 一、先分清 A 和 B —— 这决定后面所有工作有没有意义

| | 定义 | 判据 | 正确动作 |
|---|---|---|---|
| **A** | 白屏就是启动耗时本身的可视表现 | 白屏结束 ≈ `Displayed` | 优化启动耗时，或让这段时间有内容可看。**把白色改成黑色/透明一律不算修复** |
| **B** | 首帧之后白屏还在（窗口背景没被内容覆盖） | 白屏明显晚于 `Displayed` | 是真的 bug，去清窗口背景 / 系统 SplashScreen |

**在拿到 `Displayed` 与白屏结束时刻之前，不允许改任何一行代码。**
理由：这两类的修法相反。按 B 去改主题背景，对 A 完全无效；按 A 去砍初始化，对 B 是白费且危险。

---

## 二、已经拿到的静态事实（不需要设备，已确认）

### 2.1 启动的是 activity-alias，不是独立 Activity

`aapt2 dump xmltree --file AndroidManifest.xml base.apk`：

```
E: activity-alias
  android:name = com.autonavi.map.activity.SplashActivity
  android:targetActivity = com.autonavi.map.activity.NewMapActivity
  android:theme = 0x7f0f0068            ← Theme.Splash
```

⇒ 桌面图标点下去，**没有第二个 Activity**，只是 NewMapActivity 换了个主题启动。
所以「SplashActivity 卡住 / 白屏」实际上是 **NewMapActivity 的冷启动期**。
launcher 的 `Displayed` 报的也是 `com.autonavi.minimap/...SplashActivity`（alias 名）。

### 2.2 开屏主题声明的**是有品牌层的白底**，不是纯白

`res/values` 里 `0x7f0f0068 = style/Theme.Splash`：

```
parent = 0x01030006                    （framework 主题，无 windowSplashScreen* 属性）
android:windowBackground   = @drawable/fullscreen_splash_drawable_en
android:windowNoTitle      = true
android:windowAnimationStyle = @style/NoAnimation
```

`res/drawable/fullscreen_splash_drawable_en.xml` 解出来是：

```xml
<layer-list>
  <item android:drawable="@android:color/white"/>        <!-- 0x0106000b = 白 -->
  <item><bitmap android:gravity="center"           src="@drawable/…cf5"/></item>
  <item><bitmap android:gravity="bottom|center_h"  src="@drawable/…cfc"/></item>
</layer-list>
```

⇒ **设计上开屏 = 白底 + 居中 logo + 底部 logo**。
「白屏」如果连 logo 都没有，说明这张 layer-list **只画出了第一层**，后两层 bitmap 没上屏。
这就是一个具体的、可查的问题，而不是「高德本来就这样」。

### 2.3 值得注意的时序线索（待设备确认）

模块在 01:27/01:31 两次冷启动抓的连拍（`screencap` 间隔约 0.25 s，从 `am start` 后立刻开始）：

| 帧 | 文件大小 | 内容 |
|---|---|---|
| b1–b4（0.0–1.9 s） | 48,853 B ×4，逐字节相同 | 97.4% 纯白，非白像素仅 0.64% 且**没有集中的 logo 区域** |
| b5（≈2.4 s） | 188,510 B | 59.7% `(244,245,247)` —— 首页骨架 |
| b6（≈3.0 s） | 959,370 B | 地图 |

两点推论（**都需要设备复核**）：
1. 白屏期间**没有品牌 logo** ⇒ 与 2.2 的 layer-list 设计不符，属于 B 的嫌疑；
2. 但白屏恰好覆盖整个冷启动期 ⇒ 也符合 A。
   两者可以同时成立：**白是启动期的窗口背景色，且本该盖在它上面的 logo 层没画出来**。
   这种情况下用户观感就是「一直白到地图出来」，而「进入地图慢」是另一个独立问题。

---

## 三、设备到手后的测量步骤（照抄执行）

### 3.1 冷启动耗时与首帧时刻

```bash
adb shell am force-stop com.autonavi.minimap
T0=$(adb shell date '+%m-%d %H:%M:%S.000')
adb shell am start -W -n com.autonavi.minimap/com.autonavi.map.activity.SplashActivity
#  → 记下 ThisTime / TotalTime / WaitTime
adb shell logcat -d -v time -T "$T0" | grep -E "Displayed com.autonavi.minimap|Fully drawn"
#  → 记下 "+XXXms" 的绝对时间戳 = 真正首帧
```

### 3.2 白屏区间（逐帧）

方式一（推荐，能定帧）：
```bash
adb shell screenrecord --time-limit 8 --bit-rate 8000000 /sdcard/splash.mp4 &
adb shell am start -n com.autonavi.minimap/com.autonavi.map.activity.SplashActivity
# 录完 pull 回本地，ffmpeg 抽帧：
#   ffmpeg -i splash.mp4 -vsync 0 frames/f%04d.png
# 逐帧判定「第一帧非白」「最后一帧白」，换算成毫秒
```

方式二（快，够用于判 A/B）：
```bash
adb shell "am start -n com.autonavi.minimap/com.autonavi.map.activity.SplashActivity >/dev/null 2>&1; \
  for i in 1 2 3 4 5 6 7 8 9 10; do screencap -p /sdcard/f\$i.png; sleep 0.15; done"
# 每帧的采集时刻可从 mtime 拿到（stat -c %y），比 sleep 标称值准
```

### 3.3 判定

把 `Displayed` 的时刻与「最后一帧白」的时刻相减：

- `|差| ≤ 150 ms` ⇒ **A**：白屏就是启动耗时。
- 白屏比 `Displayed` **晚 > 300 ms** ⇒ **B**：首帧已出，白还在，是真的没被覆盖。
- 两者都成立（白覆盖启动期 **且** logo 缺失）⇒ 先按 B 修 logo 层，再单独谈启动耗时。

---

## 四、如果是 B：候选修法（按成功率排序，**每次只上一条**）

### 4.1 让 layer-list 真正画出来（最高优先级）
现象是「只画了白底层」。先确认系统 SplashScreen 是否抢在前面：
```bash
adb shell dumpsys activity activities | grep -A3 SplashActivity   # 看有没有 SplashScreen 记录
adb shell settings get global window_animation_scale               # 动画缩放影响观感
```
对照组：把 `window_animation_scale` 设为 0 再冷启，若 logo 层出现 ⇒ 是被系统 SplashScreen 顶掉了。

### 4.2 覆盖主题里的 `windowSplashScreenBackground`（Android 12+）
theme 没有声明任何 `windowSplashScreen*`，Android 14 会用默认策略接管启动首帧。
若确认是系统层，可在模块里 hook `Activity#getSplashScreen` 相关路径或对 `NewMapActivity`
的 `onCreate` 前置 `setTheme`。**注意：这会影响布局，必须复验首页正常。**

### 4.3 清窗口背景
`NewMapActivity.onCreate` 最开头把 decor 背景置空。**只有在 3.3 判出 B 且 4.1/4.2 都无效时才做。**
若判出 A，这一步纯粹是把白换黑，禁止。

---

## 五、如果是 A：那需求是「更快」或「白期间有内容」

不许做：
- 把白改成黑/透明冒充修好；
- 跳过任何初始化换「看起来快」（会把慢启动换成随机崩溃）。

应当做：
1. 用模块已有的 `AmapStartProbe`（`event=phase name=…`）分段看主线程耗时；
2. 用 `Perf.dump()` 的 `event=slow_hook_rank` 看 hook 自身占用（阈值 8 ms，每 id 最多 4 条）；
3. 只在**有数字**的前提下改：改完必须给出「白屏 X ms → Y ms」或「Displayed X ms → Y ms」。

---

## 六、交付时必须写清的三件事

1. 判的是 A 还是 B，**依据是哪个时间戳差多少毫秒**；
2. 每条改动单独 A/B 的结果（上了哪条、白屏时长变化多少）；
3. 若结论是 A 且已无可优化，**如实写「未修复，原因是启动耗时」**，不要凑一个「已优化」。