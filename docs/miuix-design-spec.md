# Miuix 0.9.4 设计规范提取

> 用途：给纯 Java/XML 的 Android 项目复刻 HyperOS 视觉语言。
> 源码：`C:\Users\Administrator\Downloads\Compressed\miuix-0.9.4`（Compose Multiplatform / Kotlin）。
> **不复用其代码**，只取设计参数。所有数值标了来源文件与行号；推导值标「推算」；源码里没有的写「源码未见」。

## 0. 提取坐标

| 内容 | 源码位置 |
|---|---|
| 颜色 token | `miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/theme/Colors.kt:341-505` |
| 字体 | `.../theme/TextStyles.kt:157-249` |
| 缓动曲线 | `.../anim/MiuixEasing.kt`、`DecelerateEasing.kt`、`AccelerateEasing.kt`、`SinOutEasing.kt` |
| 按压反馈 | `.../utils/PressFeedback.kt`、`.../utils/MiuixIndication.kt` |
| 滚动回弹 | `.../utils/SpringUtils.kt` |
| 圆角 / squircle | `miuix-squircle/.../SquirclePath.kt`、`SquircleBackground.kt` |
| 组件本体 | `miuix-ui/.../basic/*.kt`、`.../layout/*.kt`、`.../overlay/*.kt` |
| 设置项 | `miuix-ui/.../basic/Component.kt`（BasicComponent 是所有 preference 的底座） |
| 页面转场 | `miuix-nav/.../transition/NavTransitions.kt`、`.../runtime/NavSettleEasing.kt` |
| 模糊 / 高光 | `miuix-blur/.../BlurDefaults.kt`、`.../highlight/HighlightStyle.kt` |

---

## 1. 颜色 token

**53 个语义 token**，浅/深两套，全部来自 `Colors.kt`。ARGB 十六进制，8 位含 alpha。

| token | 语义 | 浅色 | 深色 |
|---|---|---|---|
| `primary` | 主色（开关选中轨道 / 按钮 / 滑块） | `#FF3482FF` | `#FF277AF7` |
| `onPrimary` | 主色上的内容 | `#FFFFFFFF` | `#FFFFFFFF` |
| `primaryVariant` | 主色变体 | `#FF3482FF` | `#FF0073DD` |
| `onPrimaryVariant` | 主色变体上的内容 | `#FFAECDFF` | `#FF99C7F1` |
| `error` | 危险态 | `#FFE94634` | `#FFF12522` |
| `onError` | 危险态上的内容 | `#FFFFFFFF` | `#FFFFFFFF` |
| `errorContainer` | 危险态容器 | `#FFFDF6F4` | `#FF2E0603` |
| `onErrorContainer` | 危险态容器上的内容 | `#FF410002` | `#FFFFDAD6` |
| `disabledPrimary` | 禁用主色（开关轨道） | `#FFC2D9FF` | `#FF253E64` |
| `disabledOnPrimary` | 禁用主色上的内容（开关滑块） | `#FFF3F8FF` | `#FF677993` |
| `disabledPrimaryButton` | 按钮禁用主色 | `#FFC2D9FF` | `#FF253E64` |
| `disabledOnPrimaryButton` | 按钮禁用主色上的内容 | `#FFFFFFFF` | `#FF677893` |
| `disabledPrimarySlider` | 滑块禁用主色 | `#FFB8CFF5` | `#FF44587C` |
| `primaryContainer` | 主色容器 | `#FF5D9BFF` | `#FF338FE4` |
| `onPrimaryContainer` | 主色容器上的内容 | `#FFFFFFFF` | `#FFFFFFFF` |
| `secondary` | 次色（开关未选中轨道） | `#FFE6E6E6` | `#FF505050` |
| `onSecondary` | 次色上的内容（开关未选中滑块） | `#FFFFFFFF` | `#FFFFFFFF` |
| `secondaryVariant` | 次色变体 | `#FFF0F0F0` | `#FF434343` |
| `onSecondaryVariant` | 次色变体上的内容 | `#FF303030` | `#FFD9D9D9` |
| `disabledSecondary` | 禁用次色 | `#FFF0F0F0` | `#FF3F3F3F` |
| `disabledOnSecondary` | 禁用次色上的内容 | `#FFFCFCFC` | `#FF797979` |
| `disabledSecondaryVariant` | 禁用次色变体 | `#FFF2F2F2` | `#FF404040` |
| `disabledOnSecondaryVariant` | 禁用态文字（标题/副标题/箭头共用） | `#FFB2B2B2` | `#FF707170` |
| `secondaryContainer` | 次色容器（输入框底 / 进度条底） | `#FFF0F0F0` | `#FF434343` |
| `onSecondaryContainer` | 次色容器上的内容（输入框 label） | `#FFA9A9A9` | `#FF7C7C7C` |
| `secondaryContainerVariant` | 次色容器变体 | `#FFF0F0F0` | `#FF4F4F4F` |
| `onSecondaryContainerVariant` | 次色容器变体上的内容 | `#FFA8A8A8` | `#FF959595` |
| `tertiaryContainer` | 三级容器（强调底） | `#FFEAF2FF` | `#FF2B3B54` |
| `onTertiaryContainer` | 三级容器上的内容 | `#FF3482FF` | `#FF4788FF` |
| `tertiaryContainerVariant` | 三级容器变体 | `#FFEAF2FF` | `#FF505050` |
| `background` | 页面背景 | `#FFFFFFFF` | `#FF242424` |
| `onBackground` | 页面主文字 / 按压水波纹颜色 | `#FF000000` | `#E6FFFFFF` |
| `onBackgroundVariant` | 分组标题文字（SmallTitle） | `#FF8C93B0` | `#FF787E96` |
| `surface` | 表面（页面底） | `#FFF7F7F7` | `#FF000000` |
| `onSurface` | 表面上的文字 | `#FF000000` | `#FFF2F2F2` |
| `surfaceVariant` | 表面变体 | `#FFFFFFFF` | `#FF242424` |
| `onSurfaceSecondary` | 次要文字 | `#CC000000` | `#CCFFFFFF` |
| `onSurfaceVariantSummary` | **设置项副标题（summary）** | `#99000000` | `#80FFFFFF` |
| `onSurfaceVariantActions` | **设置项右侧值文字 / 箭头** | `#66000000` | `#66FFFFFF` |
| `disabledOnSurface` | 禁用表面文字 | `#FFB2B2B2` | `#FF666666` |
| `surfaceContainer` | **卡片背景** | `#FFFFFFFF` | `#FF242424` |
| `onSurfaceContainer` | 卡片内文字 | `#FF000000` | `#E6FFFFFF` |
| `onSurfaceContainerVariant` | 卡片内次要文字 | `#FF959595` | `#FF737373` |
| `surfaceContainerHigh` | 高对比卡片 | `#FFE8E8E8` | `#FF242424` |
| `onSurfaceContainerHigh` | 高对比卡片文字 | `#FFA2A2A2` | `#FF666666` |
| `surfaceContainerHighest` | 最高对比卡片 | `#FFE8E8E8` | `#FF2D2D2D` |
| `onSurfaceContainerHighest` | 最高对比卡片文字 | `#FF000000` | `#FFE9E9E9` |
| `outline` | 边框 / 描边 | `#FFD9D9D9` | `#FF404040` |
| `dividerLine` | **分割线** | `#FFE0E0E0` | `#FF393939` |
| `windowDimming` | 弹层遮罩 | `#4D000000` | `#99000000` |
| `sliderKeyPoint` | 滑块刻度点（未选中） | `#4DA3B3CD` | `#4D7A8AA6` |
| `sliderKeyPointForeground` | 滑块刻度点（已选中） | `#FF6EB5FF` | `#FF5DAAFF` |
| `sliderBackground` | **滑块轨道背景** | `#0F000000` | `#26FFFFFF` |

### 1.1 常用取值速查（直接抄）

| 用途 | 浅色 | 深色 |
|---|---|---|
| 页面背景 | `#FFFFFFFF` | `#FF242424` |
| 卡片背景 | `#FFFFFFFF` | `#FF242424` |
| 卡片内主文字 | `#FF000000` | `#E6FFFFFF` |
| 卡片内副标题 | `#99000000` | `#80FFFFFF` |
| 右侧值 / 箭头 | `#66000000` | `#66FFFFFF` |
| 分割线 | `#FFE0E0E0` | `#FF393939` |
| 强调色 | `#FF3482FF` | `#FF277AF7` |
| 开关选中轨道 | `#FF3482FF` | `#FF277AF7` |
| 开关未选中轨道 | `#FFE6E6E6` | `#FF505050` |
| 开关滑块（选中/未选中） | `#FFFFFFFF` | `#FFFFFFFF` |
| 开关禁用轨道（选中） | `#FFC2D9FF` | `#FF253E64` |
| 危险态 | `#FFE94634` | `#FFF12522` |
| 禁用文字 | `#FFB2B2B2` | `#FF707170` |

> **一处源码与文档打架**：`docs/zh_CN/guide/colors.md` 写深色 `onError = #FF690005`，源码 `Colors.kt:457` 是 `Color.White`（`#FFFFFFFF`）。以源码为准。
> 另：`surfaceContainer` 与 `background` 浅色下都是白色 —— HyperOS 的卡片不是靠底色区分，是靠 **12dp 页边距 + 16dp 圆角** 切出来的。深色下两者都是 `#242424`，同理。

**复刻要点**：53 个 token 在 XML 里落成两套 `values/colors.xml` + `values-night/colors.xml`，命名用 `miuix_` 前缀全小写下划线（`miuix_on_surface_variant_summary`）。`onSurfaceSecondary` / `onSurfaceVariantSummary` / `onSurfaceVariantActions` 三个是**带 alpha 的黑/白**，不要烘成实色 —— 叠在 `#FFF7F7F7` 和 `#FFFFFFFF` 上观感不同，直接用 `#99000000` 这类 ARGB 让系统自己合成。分割线用 `ColorStateList` 交给 `View.setBackground`，避免夜间模式重建 Activity 时闪一帧。

---

## 2. 圆角

单位 dp。全部来自各组件的 `Defaults` 对象。

| 用途 | 半径 | 源码 |
|---|---|---|
| **卡片 / 分组卡片** | **16** | `Card.kt:191` `CardDefaults.CornerRadius` |
| **按钮** | **16** | `Button.kt:169` `ButtonDefaults.CornerRadius` |
| **输入框** | **16** | `TextField.kt:376` `TextFieldDefaults.CornerRadius` |
| **弹窗列表 / ListPopup** | **16** | `ListPopup.kt:604` |
| **Snackbar** | **16** | `Snackbar.kt:497` |
| **对话框** | **32** | `DialogContentLayout.kt:457` `DialogDefaults.CornerRadius` |
| 对话框（大屏兜底） | `(roundedCorner - outsideMargin.width)` 钳位到 **32~48** | `DialogContentLayout.kt:303-305` |
| **底部弹层（仅上两角）** | **28** | `BottomSheetContentLayout.kt:878` |
| **分段控件 TabRow** | **12** | `TabRow.kt:521` `TabRowCornerRadius` |
| **分段控件（带轮廓）** | **8**（外框 = 8 + 5 = 13） | `TabRow.kt:526`，`contourPadding = 5.dp` `TabRow.kt:226` |
| **IconButton** | **40**（= 直径，正圆） | `IconButton.kt:94` |
| **FAB** | `CircleShape`（正圆） | `FloatingActionButton.kt:40` |
| **开关** | `CircleShape`（全胶囊） | `Switch.kt:85` |
| **滑块轨道** | `CircleShape`（全胶囊） | `Slider.kt` `.clip(CircleShape)` |
| Snackbar 内动作按钮 | **50** | `Snackbar.kt:506` |
| 底部弹层把手 | **2** | `BottomSheetContentLayout.kt:730` |
| 头像 / 图标底 | 源码未见独立 token（用 IconButton 的 40dp 正圆或 Card 的 16dp） | — |

### 2.1 Squircle（超椭圆）参数

`miuix-squircle` 的连续曲率圆角，**不是** `RoundedCornerShape` 的四分之一圆弧。

| 参数 | 值 | 说明 |
|---|---|---|
| `SquircleDefaults.Extension` | **1.1** | 角部区域 = `cornerRadius × 1.1`；1.0 等于标准圆弧 |
| `ExtensionMin` / `ExtensionMax` | 1.0 / 2.0 | 钳位区间 |
| `SQUIRCLE_CONTROL` | **0.643** | 三次贝塞尔控制点比例（`SquirclePath.kt:33`） |

Path 构造（`SquirclePath.kt:74-97`，可直接翻成 Java）：

```
tile   = cornerRadius × 1.1        // 钳到 min(w,h)/2
handle = tile × (1 - 0.643) = tile × 0.357

moveTo(tile, 0)
lineTo(w - tile, 0)
cubicTo(w - handle, 0,        w, handle,        w, tile)
lineTo(w, h - tile)
cubicTo(w, h - handle,        w - handle, h,    w - tile, h)
lineTo(tile, h)
cubicTo(handle, h,            0, h - handle,    0, h - tile)
lineTo(0, tile)
cubicTo(0, handle,            handle, 0,        tile, 0)
close()
```

组件里实际用的是 `squircleSurface`（填充 + 裁剪），`Card.kt:176`、`Button.kt:76`、`IconButton.kt:71`、`TabRow.kt:158`、`DialogContentLayout.kt:358` 全部走这个。

**复刻要点**：API 33+ 才能用 `RuntimeShader`；纯 View 体系不用 shader，直接把上面 8 段 path 写进自定义 `ShapeDrawable` / `Path`，`onDraw` 里 `canvas.clipPath()` 后画背景 —— 一条 path 走天下，16dp / 28dp / 32dp 全参数化。要零成本就退一步用 `GradientDrawable.setCornerRadius(16dp)`，圆弧与 squircle 在 16dp 半径、~360dp 宽的卡片上肉眼差 0.5~1dp，只在 28dp 以上的大圆角（对话框、底部弹层）才明显。**对话框和底部弹层必须上 squircle path**，那是唯一一眼能看出「这不是原生 Material」的地方。

---

## 3. 间距与尺寸

### 3.1 页面级

| 项 | 值 | 来源 |
|---|---|---|
| **页面左右边距** | **12** | 示例全站统一 `Modifier.padding(horizontal = 12.dp)`（38 处） |
| 卡片间距（上下） | **12** | `Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp))` |
| 卡片距页面左右 | 12 | 同上 |
| 分组标题 SmallTitle 内边距 | **水平 28 / 垂直 8** | `SmallTitle.kt:39` `PaddingValues(28.dp, 8.dp)` |

> 28 = 12（页边距）+ 16（卡片内边距）。分组标题的文字左边缘因此与卡片内文字左边缘对齐。

### 3.2 卡片 / 设置项行

| 项 | 值 | 来源 |
|---|---|---|
| 卡片内 padding | **16 四边** | `Component.kt:298` `BasicComponentDefaults.InsideMargin = PaddingValues(16.dp)` |
| Card 自身 `insideMargin` | **0** | `Card.kt:196`（padding 由行自己带） |
| **设置项行最小高度** | **56** | `Component.kt:186` `.heightIn(min = 56.dp)` |
| 左侧图标与文字间距 | **8** | `Component.kt:206` `spacerPx = 8.dp` |
| 文字与右侧控件间距 | **8** | 同上 |
| 右侧控件最大宽度 | 剩余宽度的 **60%** | `Component.kt:216` `endHardCap = (widthAfterStart - spacerPx) * 6 / 10` |
| 右侧值文字与开关之间 | **8** | `SwitchPreference.kt` `.padding(end = 8.dp)` |
| 底部附加内容上方间距 | **8** | `Component.kt:290` `Spacer(8.dp)` |
| 卡片内分割线缩进 | **水平 16** | 示例 `HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))` |
| 分割线粗细 | **0.75** | `Divider.kt` `DividerDefaults.Thickness` |

### 3.3 控件尺寸

| 控件 | 尺寸 | 来源 |
|---|---|---|
| **图标** | **24 × 24** | `Icon.kt:204` `Modifier.size(24.dp)` |
| **最小触摸目标** | 行 56 / IconButton 40 / Button 40 | 见下 |
| 开关轨道 | **49 × 28** | `Switch.kt:146` `.size(49.dp, 28.dp)` |
| 开关滑块 | **20 × 20** | `Switch.kt:152` `.size(20.dp)` |
| 开关滑块位移 | 未选中 **4** / 选中 **25** | `Switch.kt:96` |
| 滑块轨道高 | **28** | `Slider.kt:1352` `SliderDefaults.MinHeight` |
| 滑块拖拽命中半径 | knobRadius + 14 | `Slider.kt:185` `knobRadius + thumbRadius * 0.5f` |
| 复选框 | **26 × 26** | `Checkbox.kt:173` `.requiredSize(26.dp)` |
| 按钮最小尺寸 | **58 × 40**，内边距 **16 × 13** | `Button.kt:158/164/174` |
| IconButton | **40 × 40**，圆角 40 | `IconButton.kt:84/89/94` |
| FAB | **60 × 60** 最小，阴影 4 | `FloatingActionButton.kt:69/72/75` |
| 输入框内边距 | **16 × 16** | `TextField.kt:379` `DpSize(16.dp, 16.dp)` |
| 输入框聚焦边框 | **2** | `TextField.kt:382` |
| 输入框浮动 label 字号 | **10**（常态 17） | `TextField.kt:385/388` |
| 顶部栏折叠高度 | **52** | `TopAppBar.kt:409` `CollapsedHeight` |
| 顶部栏小标题居中高度 | **50** | `TopAppBar.kt:412` |
| 顶部栏标题左内边距 | **26** | `TopAppBar.kt:400` `TitlePadding` |
| 顶部栏导航图标内边距 | **16** | `TopAppBar.kt:403` |
| 顶部栏动作图标内边距 | **16** | `TopAppBar.kt:406` |
| 顶部栏大标题下内边距 | **4** | `TopAppBar.kt:415` |
| 顶部栏副标题下内边距 | **8** | `TopAppBar.kt:418` |
| TabRow 高度 | **42** | `TabRow.kt:511` |
| TabRow 项间距 | **9** | `TabRow.kt:94` |
| TabRow 项宽区间 | **76 ~ 98** | `TabRow.kt:531/541` |
| TabRow（带轮廓）高度 | **45**，间距 **5**，项宽 **62 ~ 84** | `TabRow.kt:516/219/536/546` |
| Tab 内水平内边距 | **12** | `TabRow.kt:382` |
| 底部导航栏项高 | **64** | `NavigationBar.kt:442` |
| 底部导航栏图标 | **26**，上内边距 8，下 8 | `NavigationBar.kt:445/451/454` |
| 浮动导航栏 | 图标 **28**，内边距 10，项间距 12，水平内 12，外侧 36 | `NavigationBar.kt:524/527/521/518/512` |
| 对话框最大宽 | **420** | `DialogContentLayout.kt:451` |
| 对话框外边距 | **12 × 12** | `DialogContentLayout.kt:462` |
| 对话框内边距 | **24 × 24** | `DialogContentLayout.kt:467` |
| 底部弹层最大宽 | **640** | `BottomSheetContentLayout.kt:883` |
| 底部弹层外边距 | **0 × 0** | `BottomSheetContentLayout.kt:888` |
| 底部弹层内边距 | **24 × 0** | `BottomSheetContentLayout.kt:893` |
| 底部弹层把手 | 宽 **45**（按下 55）× 高 **4**，容器高 24 | `BottomSheetContentLayout.kt:783/784/735` |
| 底部弹层关闭阈值 | 位移 **150** / 速度 **800 dp/s** | `BottomSheetContentLayout.kt:400/405` |
| ListPopup 最小宽 / 最小高 | **200 / 50** | `ListPopup.kt:221/227` |
| Snackbar 内边距 | **12 四边**，外 `start12 end12 top8` | `Snackbar.kt:500/503` |
| Snackbar 距底 | **12** | `Snackbar.kt:146` |
| 线性进度条高 | **6** | `ProgressIndicator.kt` |
| 圆形进度条 | 直径 **30**，描边 **4** | 同上 |
| Badge 圆点 | **6**（大号 16） | `Badge.kt:182/185` |
| 设置项箭头 | **10 × 16** | `ArrowPreference.kt:102` |

**复刻要点**：一套 spacing token 就够 —— `4 / 8 / 12 / 16 / 24 / 28`。行高 56dp 是硬底线，`android:minHeight="56dp"` 写在行布局根上，别写在子 View 上，否则 `wrap_content` 会塌。右侧控件「最多占 60% 宽」这条要用自定义 `ViewGroup.onMeasure` 或 `ConstraintLayout` 的 `layout_constraintWidth_percent="0.6"` 实现 —— 这是 HyperOS 设置项长标题不把开关挤出去的关键。分割线用 0.75dp 而不是 1dp，再配 `dividerLine` 色，才不会有 Material 那种硬黑线。

---

## 4. 字体

字号 sp，来自 `TextStyles.kt:157-249`。**源码未声明字体族**（用平台默认），HyperOS 实机是 MiSans，库本身不指定。

| 层级 | token | 字号 | 字重 | 行高 | 字间距 |
|---|---|---|---|---|---|
| 页面大标题 | `title1` | **32** | 默认 | 未指定 | 0 |
| 页面标题 | `title2` | **24** | 默认 | 未指定 | 0 |
| 区块标题 | `title3` | **20** | 默认 | 未指定 | 0 |
| 小标题 | `title4` | **18** | 默认 | 未指定 | 0 |
| **正文 / 设置项标题** | `main` | **17** | 默认 | 未指定 | 0 |
| 段落 | `paragraph` | **17** | 默认 | **1.2 em** | 0 |
| **按钮文字** | `button` | **17** | 默认 | 未指定 | 0 |
| **设置项标题（实参）** | `headline1` | **17** | **Medium(500)** | 未指定 | 0 |
| 次标题 | `headline2` | **16** | 默认 | 未指定 | 0 |
| 正文小 | `body1` | **16** | 默认 | 未指定 | 0 |
| **设置项副标题** | `body2` | **14** | 默认 | 未指定 | 0 |
| **分组标题（SmallTitle）** | `subtitle` | **14** | **Bold(700)** | 未指定 | 0 |
| 脚注 | `footnote1` | **13** | 默认 | 未指定 | 0 |
| 最小文字 | `footnote2` | **11** | 默认 | 未指定 | 0 |
| Tab 文字 | `body1` | **16** | 默认 | 未指定 | 0 |
| Tab 文字（带轮廓） | `body2` | **14** | 默认 | 未指定 | 0 |
| 输入框浮动 label | — | **10** | 默认 | 未指定 | 0 |

实际用法（`Component.kt:88-104`）：

```kotlin
Text(title,  fontSize = headline1.fontSize /* 17sp */, fontWeight = FontWeight.Medium, color = onBackground)
Text(summary, fontSize = body2.fontSize  /* 14sp */, color = onSurfaceVariantSummary)
```

分组标题（`SmallTitle.kt`）：`style = subtitle`（14sp / Bold），`color = onBackgroundVariant`。

**数字（等宽）**：源码未见 tabular figures 或 `fontFeatureSettings` 配置。数值展示走默认比例数字。

**复刻要点**：`values/dimens.xml` 里落 `text_page_title=32sp` … `text_footnote=11sp` 共 9 档。字重只有两档要用：**500（设置项标题）** 和 **700（分组标题）**，其余 400。`letterSpacing` 全 0，别加 —— HyperOS 的密度感来自字号阶梯和行高，不来自字距。中文字体在 XML 里用 `android:fontFamily="sans-serif"` + `android:textFontWeight` 会掉到 Roboto，要么内置 MiSans subset（推荐，17sp/14sp/11sp 三档常用字足够），要么接受系统默认。行高只有 `paragraph` 明确写了 1.2em，其余走平台默认（约 1.2~1.3 倍字号），复刻时统一按 `lineSpacingMultiplier="1.2"` 处理即可。

---

## 5. 动效

### 5.1 缓动曲线全表

| 名称 | 数学定义 | 等价 Android 实现 | 用在哪 |
|---|---|---|---|
| `FastOutSlowInEasing` | Compose 内置 = **cubic-bezier(0.4, 0.0, 0.2, 1.0)** | `PathInterpolator(0.4f, 0f, 0.2f, 1f)` | Checkbox / RadioButton 全部状态过渡 |
| `DecelerateEasing(1.0)` | `1 - (1-t)²` | `DecelerateInterpolator(1.0f)` | 通用减速 |
| `DecelerateEasing(1.5)` | `1 - (1-t)³` | `DecelerateInterpolator(1.5f)` | **对话框 / 弹层进出（最常用）** |
| `AccelerateEasing(1.0)` | `t²` | `AccelerateInterpolator(1.0f)` | 退场 |
| `SinOutEasing` | `sin(t · π/2)` | 自定义 `Interpolator { sin(it * PI/2) }` | 遮罩淡入淡出 |
| `NavProgrammaticEasing` | 欠阻尼弹簧阶跃响应，`response=0.8, damping=0.95`，烘进定长 tween | 见 5.4 | **页面 push/pop** |
| `LinearEasing` | `t` | `LinearInterpolator()` | Tab 指示器滑动、进度条 |

### 5.2 时长全表（毫秒）

| 时长 | 用在哪 |
|---|---|
| **50** | TopAppBar 标题 / 副标题透明度与偏移 |
| **100** | 底部弹层把手按下放大 |
| **150** | 弹层关闭回弹、遮罩淡出、Slider 拖拽压暗、ScrollBar 拖拽高亮、Checkbox 部分过渡、ListPopup alpha 退场、Popup 遮罩退场 |
| **200** | Tab 指示器滑动、ListPopup alpha 进场、Popup 缩放、Checkbox 部分过渡 |
| **250** | 对话框遮罩淡出、Popup 遮罩淡出 |
| **260** | 对话框退场 |
| **300** | 对话框遮罩淡入、Popup 遮罩淡入、NavigationBar 指示器、Checkbox 主过渡、RadioButton 过渡 |
| **500** | **页面 push/pop（程序化）**、ScrollBar 淡出 |
| **800 / 1000 / 1250 / 1600** | 进度条循环 |

### 5.3 弹簧参数全表

`folmeSpring(damping, response)` 的换算：`stiffness = (2π / response)²`（`MiuixEasing.kt:20-25`）。

| 场景 | dampingRatio | stiffness | response(s) | 推算稳定时间(1%) | 源码 |
|---|---|---|---|---|---|
| **按压沉降 SinkFeedback（默认）** | **0.8** | **600** | — | ~235ms | `PressFeedback.kt:33` |
| 按压倾斜 TiltFeedback | 0.6 | 400 | — | ~383ms | `PressFeedback.kt:86` |
| Checkbox / RadioButton 按压 | 0.99 | 986.96 | — | ~222ms | `Checkbox.kt:143` |
| **开关滑块位移** | **0.7** | **987** | — | ~209ms | `Switch.kt:87` |
| **开关滑块缩放** | **0.6** | **987** | — | ~244ms | `Switch.kt:88` |
| **开关轨道换色** | **0.99** | **438.6** | — | ~222ms | `Switch.kt:113` |
| **滑块进度（拖拽中）** | 0.9 | 1755 | — | ~122ms | `Slider.kt:125` |
| **滑块进度（静止）** | 0.96 | 322 | — | ~267ms | `Slider.kt:127` |
| 滑块滑块缩放 | 0.6 | 987 | — | ~244ms | `Slider.kt:331` |
| 按压水波纹 进入 | 1.0 | 986.96 | **0.2** | ~222ms | `MiuixIndication.kt:30` |
| 按压水波纹 退出 | 0.95 | 322.27 | **0.35** | ~267ms | `MiuixIndication.kt:31` |
| 悬停 进入 | 1.0 | 109.66 | **0.6** | — | `MiuixIndication.kt:32` |
| 悬停 退出 | 0.96 | 986.96 | **0.2** | — | `MiuixIndication.kt:33` |
| **对话框进场** | 0.9 | 438.65 | **0.3** | ~244ms | `DialogContentLayout.kt:145` |
| 对话框缩放 | 0.88 | 450 | — | ~246ms | `DialogContentLayout.kt:147` |
| **底部弹层进出** | 0.9 | 273.4 | **0.38** | ~300ms | `BottomSheetContentLayout.kt:152/163` |
| 底部弹层内层 | 0.85 | 246.74 | **0.4** | ~333ms | `BottomSheetContentLayout.kt:432/849` |
| ListPopup 比例 | 0.82 | 362.5 | — | ~295ms | `ListPopup.kt:200` |
| Popup 进场缩放 | 0.9 | 438.6 | — | ~244ms | `MiuixPopupUtils.kt:107` |
| Popup 退场缩放 | 0.88 | 450 | — | ~246ms | `MiuixPopupUtils.kt:115` |
| 级联列表 展开 | 0.99 | 195 | **0.45** | — | `CascadingListPopupLayout.kt:61-64` |
| 级联列表 收起 | 0.95 | 986.96 | **0.2** | — | 同上 |
| 级联箭头 展开 | 0.95 | 986.96 | **0.2** | — | 同上 |
| 级联箭头 收起 | 0.95 | 438.65 | **0.3** | — | 同上 |
| TopAppBar 滚动吸附 | 1.0 | **2500** | — | ~92ms | `TopAppBar.kt:253` |
| NumberPicker 选中 | 1.0 | 400 | — | ~230ms | `NumberPicker.kt:208` |
| NavigationRail 指示器 | 1.0 | 322.27 | **0.35** | — | `NavigationRail.kt:595` |
| **Pager 切页** | **≈0.90**（`32.31/(2√322.2)`） | **322.2** | — | — | `PagerGestureUtils.kt:83-87` |
| Snackbar 淡出 | NoBouncy | 1500（`StiffnessMedium`） | — | — | `Snackbar.kt:362` |
| Tooltip | NoBouncy | 1500 | — | — | `Tooltip.kt:253` |
| **页面转场手势** | **1.0（临界阻尼）** | **146** | — | **~500ms** | `NavDriver.kt` `NavDriverSpec` |

### 5.4 页面转场（miuix-nav）

| 项 | 值 | 来源 |
|---|---|---|
| 程序化 push/pop 时长 | **500 ms** | `NavDriverSpec.PROGRAMMATIC_DURATION_MILLIS` |
| 程序化曲线 | `NavSettleEasing(response = 0.8, damping = 0.95)` | `NavSettleEasing.kt:110` |
| 手势 commit / cancel | `spring(dampingRatio = 1.0, stiffness = 146, visibilityThreshold = 0.0025)` | `NavDriverSpec` |
| 全步阈值 | `0.999`（深度单位） | `NavDriverSpec.FULL_STEP_THRESHOLD` |
| 遮罩最大 alpha | **0.5** | `NavDisplayEffects.kt:67` |
| 角裁剪 | `enableCornerClip = true`，半径默认 **0**（跟随设备屏幕圆角） | `NavDisplayEffects.kt:64-65` |

**MiuixDefault 转场几何**（`NavTransitions.kt:44-57`，`d = animatedTop - entryIndex`）：

| 深度段 | translationX | alpha |
|---|---|---|
| `d ≤ 0`（进场/退场页） | `(-d).coerceIn(0,1) × 屏宽`（整屏从右侧滑入，RTL 镜像） | 不变 |
| `0 < d ≤ 1`（被覆盖页） | `-d × 屏宽 × 0.25`（**视差 1/4 屏宽**） | `1 - 0.1 × d`（**最多暗 10%**） |

另有 `Modal`（整屏从底部滑入，`translationY` 线性）与 `None`（瞬切）两个预设。

### 5.5 滚动回弹

`SpringUtils.kt`：

| 项 | 值 |
|---|---|
| 阻尼曲线（触摸期） | `d = x - x² + x³/3`，`x = 归一化输入`，乘回 range |
| 反解（`obtainTouchDistance`） | `range - range^(2/3) × sign(b) × |b|^(1/3)`，`b = range - 3·offset` |
| 弹簧阻尼比 | **1.0（临界）** |
| 标准周期 | **0.4 s** |
| 高速周期（`|v| > 5000 px/s`） | **0.55 s** |
| 帧间隔钳位 | 1ms ~ 16ms |

### 5.6 逐交互动效清单

| 交互 | 动效内容 | 参数 |
|---|---|---|
| **按压反馈（通用）** | 整体缩放到 **0.94** | `spring(0.8, 600)` |
| 按压反馈（Checkbox/Radio 内） | 缩放到 **0.85** | `spring(0.99, 986.96)` |
| 按压反馈（倾斜） | 按触点位置绕对应角旋转 **±8°**，`cameraDistance = 12 × density` | `spring(0.6, 400)` |
| 水波纹 | 矩形纯色覆盖（非圆形 ripple），alpha：hover **0.06** / focus **0.08** / press **0.10** / hold **0.10** | 见 5.3 |
| **开关切换** | 滑块位移 + 放大到 **1.127** + 轨道换色 + 触感反馈 | 见 5.3 |
| **滑块拖动** | 进度跟随、滑块放大 1.127、轨道压暗 **alpha 0.044** | `tween(150)` |
| **列表展开** | 高度 + 透明度，`fadeIn + expandVertically` | Compose 默认（推算 ~300ms） |
| **对话框进出** | 缩放 + 淡入，遮罩独立淡入 | `spring(0.88, 450)` + `tween(300/250, Decelerate(1.5))` |
| **底部弹层进出** | 位移 + 遮罩 | `folme(0.9, 0.38)` + `tween(150)` |
| **滚动回弹** | 越界阻尼 + 临界阻尼弹簧归位 | 见 5.5 |
| **页面切换** | 整屏滑入 + 被覆盖页 1/4 视差 + 10% 变暗 + 0.5 遮罩 | 见 5.4 |
| **Tab 切换** | 指示器位置线性滑动 | `tween(200, LinearEasing)` |
| **顶部栏折叠** | 高度吸附 | `spring(2500)` |

**复刻要点**：View 体系里把 `spring(damping, stiffness)` 换成 `SpringAnimation`（`androidx.dynamicanimation`），`stiffness` 直接照抄，`dampingRatio` 直接照抄 —— 这两个库的物理模型是同一个，数值可以 1:1 平移，观感一致。`folmeSpring(damping, response)` 用 `(2π/response)²` 先算成 stiffness 再喂给 `SpringAnimation`（表里已经算好了）。`tween(ms, easing)` 换成 `ObjectAnimator.setDuration(ms).setInterpolator(new PathInterpolator(...))`，`DecelerateEasing(1.5)` 直接 `new DecelerateInterpolator(1.5f)`。**按压缩放必须用 `View.setScaleX/Y` + `SpringAnimation`，不要用 `ViewPropertyAnimator`** —— 后者固定时长，松手时会跳。最小实现：一个 `StateListAnimator` 挂 `state_pressed`，里面两个 `ObjectAnimator` 对 `scaleX/scaleY` 走 `SpringAnimation`。

---

## 6. 组件规范

### 6.1 设置项行（BasicComponent / SwitchPreference / ArrowPreference）

结构（`Component.kt:184-280`）：

```
Column(minHeight=56dp, padding=16dp all, gravity=center_vertical)
└─ Row/Layout
   ├─ [startAction]            // 图标，宽=内容，居中
   ├─ 8dp spacer（startAction 宽>0 时才有）
   ├─ [center]  Column(gravity=center_vertical)
   │    ├─ title    17sp Medium   onBackground
   │    └─ summary  14sp Normal   onSurfaceVariantSummary
   ├─ 8dp spacer（endActions 宽>0 时才有）
   └─ [end]     Row(gravity=center_vertical, 最大宽度 = 剩余×0.6)
        └─ 值文字（onSurfaceVariantActions） + endAction padding(end=8dp) + 控件
└─ [bottomAction]  上方 8dp spacer
```

| 属性 | 值 |
|---|---|
| 最小高度 | **56** |
| 内边距 | **16**（四边） |
| 垂直排布 | `Arrangement.Center`（内容整体垂直居中） |
| 标题 | 17sp / Medium / `onBackground` |
| 副标题 | 14sp / Normal / `onSurfaceVariantSummary` |
| 右侧值 | `onSurfaceVariantActions`（`#66000000` / `#66FFFFFF`） |
| 箭头 | 10×16 dp，色 `onSurfaceVariantActions` |
| 点击态 | **无水波纹圆扩散**，整行矩形覆盖 `onBackground` @ alpha 0.10 |
| 禁用 | 标题/副标题 → `disabledOnSecondaryVariant`；开关 → `disabledPrimary` / `disabledSecondary` |
| 触感 | 开关切换 `HapticFeedbackType.ToggleOn/Off` |

**开关在行内的行为**：点整行 = 切换开关（`SwitchPreference.kt` `onClick = { onCheckedChange(!checked) }`），开关本身也可单独点。

**复刻要点**：一个 `PreferenceRow` 自定义 ViewGroup 就够：`onMeasure` 里量 start / center / end 三段，end 的 `maxWidth` 钳到 `remaining * 0.6`，center 拿剩下的。行背景用 `StateListDrawable` + `ColorStateList`，pressed 态叠 `#1A000000`（= 10% 黑）而不是 `?attr/selectableItemBackground` —— HyperOS 的按压是**整行矩形**，不是圆形 ripple，这一点是视觉识别度的关键。开关直接 `SwitchCompat` 改 track/thumb 的 `ColorStateList`，或自己写 49×28 的自定义 View（更准，见 6.3）。

### 6.2 分组卡片

```
SmallTitle("分组标题")            // 14sp Bold, onBackgroundVariant, padding(28, 8)
Card(                             // 12dp 页边距 + 12dp 底间距
    cornerRadius = 16dp,
    insideMargin = 0dp,
    color = surfaceContainer
) {
    行1 (56dp min, padding 16)
    HorizontalDivider(padding horizontal 16)   // 0.75dp, dividerLine
    行2
    ...
}
```

| 属性 | 值 |
|---|---|
| 圆角 | **16**（squircle，extension 1.1） |
| 背景 | `surfaceContainer`（浅 `#FFFFFFFF` / 深 `#FF242424`） |
| 内容色 | `onSurfaceContainer` |
| 内部 padding | **0**（每行自带 16） |
| 卡片外边距 | **12 水平 + 12 底部** |
| 分割线 | **0.75dp**，`dividerLine`，**水平缩进 16**（与文字左边缘对齐） |
| 分组标题 | 上 8 / 下 8 / 左 28 / 右 28，14sp Bold，`onBackgroundVariant` |
| 卡片点击态 | 默认 `PressFeedbackType.None`；可选 `Sink`（0.94）/ `Tilt`（8°） |

**复刻要点**：卡片背景必须是 **squircle path 的 `GradientDrawable` 子类或 `ViewOutlineProvider`**，用 `setCornerRadius` 会在 16dp 上差一点点圆度但可接受；真正省事的做法是给 Card 根容器挂一个自定义 `Drawable`，`getOutline` 里 `outline.setConvexPath(squirclePath)`，阴影也能顺带正确。分割线用 `View` + `layout_height="0.75dp"` + `marginHorizontal="16dp"`，别用 `LinearLayout.divider` —— 那个没法缩进。**最后一行后面不要画分割线**（源码里 Divider 是显式插在行之间的，不是每行自带底边）。

### 6.3 开关（Switch）

| 属性 | 值 | 来源 |
|---|---|---|
| 轨道尺寸 | **49 × 28**，`CircleShape` | `Switch.kt:146` |
| 滑块直径 | **20** | `Switch.kt:152` |
| 滑块 X 偏移（未选中） | **4** | `Switch.kt:96` |
| 滑块 X 偏移（选中） | **25** | `Switch.kt:96` |
| 滑块放大（按下/拖动/悬停） | **1.127** | `Switch.kt:105` |
| 位移动画 | `spring(0.7, 987)` | `Switch.kt:87` |
| 缩放动画 | `spring(0.6, 987)` | `Switch.kt:88` |
| 轨道换色动画 | `spring(0.99, 438.6)` | `Switch.kt:113` |
| 轨道 选中 / 未选中 | `primary` / `secondary` | `Switch.kt:229/230` |
| 滑块 选中 / 未选中 | `onPrimary`（白）/ `onSecondary`（白） | `Switch.kt:226/227` |
| 禁用 选中轨道 / 未选中轨道 | `disabledPrimary` / `disabledSecondary` | `Switch.kt:231/232` |
| 禁用 选中滑块 / 未选中滑块 | `disabledOnPrimary` / `disabledOnSecondary` | `Switch.kt:228/229` |
| 拖动 | 可横向拖拽，拖过半程（21dp）即切换 | `Switch.kt:186` |
| 触感 | 拖到端点 / 切换时 `ToggleOn/Off` | `Switch.kt:171-180` |

**几何换算**：轨道 28 高 → 滑块 20 直径，上下各留 4；未选中左 4、选中左 25（= 49 - 20 - 4），行程 21dp。

**复刻要点**：写自定义 View，别拼 `SwitchCompat`。`onDraw` 里 `drawRoundRect(track, 14, 14)` + `drawCircle(thumb)`，位移用 `SpringAnimation(thumbX)` 配 `setStartValue`，`setDampingRatio(0.7f)` `setStiffness(987f)`。`onTouchEvent` 处理 `ACTION_MOVE` 时把 `dragOffset` 钳到 `[-21, 21]`，`ACTION_UP` 判 `|offset| > 10.5` 就翻转。**滑块放大要单独一条 `SpringAnimation` 作用在半径上**（不是 scale，因为半径 20 放大 1.127 是 22.54，`scaleX` 会连带把阴影/抗锯齿糊掉）。

### 6.4 滑块（Slider）

| 属性 | 值 | 来源 |
|---|---|---|
| 轨道高 | **28**（`MinHeight`，可传） | `Slider.kt:1352` |
| 轨道形状 | 全胶囊 `CircleShape` | `Slider.kt:845` |
| 轨道背景 | `sliderBackground`（`#0F000000` / `#26FFFFFF`） | `Slider.kt:1383` |
| 激活段 | `primary`，**线宽 = 轨道高**，`StrokeCap.Round` | `Slider.kt:899-903` |
| 滑块半径 | `轨道高/2 × 0.72` = **10.08**（直径 20.16） | `Slider.kt:923` |
| 滑块颜色 | `onPrimary`（白） | `Slider.kt:1385` |
| 滑块放大 | **1.127**（按下/拖动/悬停） | `Slider.kt:131` |
| 刻度点半径 | `轨道高 / 7.5` = **3.73**（Range 版用 `KeyPointRadius = 3.855`） | `Slider.kt:907/1357` |
| 刻度点色 已选 / 未选 | `sliderKeyPointForeground` / `sliderKeyPoint` | `Slider.kt:1388/1387` |
| 拖拽时轨道压暗 | **alpha 0.044**，`tween(150)` | `Slider.kt:838-841` |
| 进度动画 拖拽中 / 静止 | `spring(0.9, 1755)` / `spring(0.96, 322)` | `Slider.kt:125-129` |
| 禁用 激活段 / 轨道 | `disabledPrimarySlider` / `disabledSecondary` | `Slider.kt:1382/1384` |
| 磁吸阈值 | **0.02**（2%） | `Slider.kt:104` |

**复刻要点**：`SeekBar` 改不动这套几何，自己写。`onDraw`：先 `drawRoundRect` 画轨道（半径 = h/2），再 `drawLine(strokeWidth = h, cap = ROUND)` 从 0 画到 thumbX 作为激活段 —— **激活段是等宽于轨道的圆头线**，这就是 HyperOS 滑块的「胶囊填满」观感，`SeekBar` 的细线做不到。滑块是 `drawCircle`，半径 0.72 × 轨道半径。刻度点用 `drawCircle` 叠在激活段上下（颜色按 `x <= thumbX` 切换）。触摸用 `onTouchEvent` + `setProgress`，进度动画只在**程序化改值**时用 `SpringAnimation`，拖动时直接跟手。

### 6.5 顶部栏（TopAppBar）

| 属性 | 值 |
|---|---|
| 折叠高度 | **52** |
| 小标题（SmallTopAppBar）居中高度 | **50** |
| 标题左内边距 | **26** |
| 导航图标内边距 | **16** |
| 动作图标内边距 | **16** |
| 大标题下内边距 | **4** |
| 副标题下内边距 | **8** |
| 滚动吸附 | `spring(stiffness = 2500)`，约 92ms（推算） |
| 标题/副标题过渡 | `tween(50)` |
| 标题对齐 | 大标题左对齐（`TitlePadding = 26`）；折叠态标题居中（`SmallTopAppBarCenterHeight`） |
| 滚动行为 | 大标题随滚动上移并淡出，折叠为 52dp 条；`heightOffsetLimit` 由大标题实测高度写入 |

**复刻要点**：用 `AppBarLayout` + `CollapsingToolbarLayout` 能出七成效果，但标题左内边距 26dp 和折叠高度 52dp 要手动覆写（`app:expandedTitleMarginStart="26dp"`、`app:collapsedTitleGravity`）。吸附曲线 `AppBarLayout` 自带的是 `LinearOutSlowIn`，要 92ms 的手感得关掉默认并挂 `AppBarLayout.Behavior` 自定义 `setHeaderTopBottomOffset`。**滚动时的 50ms 标题淡入淡出**用 `addOnOffsetChangedListener` 里按 `offset/limit` 算 alpha，配 `tween(50)` 的 `ValueAnimator`。

### 6.6 标签页 / 分段控件（TabRow）

| 属性 | 值 |
|---|---|
| 高度 | **42** |
| 圆角 | **12**（squircle） |
| 项间距 | **9** |
| 项宽区间 | **76 ~ 98**（内容自适应，超出则滚动） |
| 项内水平内边距 | **12** |
| 文字 | 16sp（`body1`） |
| 指示器形状 | **整块选中项背景**（squircle 填充，非下划线） |
| 指示器滑动 | `tween(200, LinearEasing)` |
| 容器背景 | `colors.backgroundColor(false)`，指示器 `colors.backgroundColor(true)` |
| 带轮廓变体 | 高 **45**，圆角 **8**，外框圆角 **13**，间距 **5**，项宽 **62 ~ 84**，轮廓内边距 **5** |
| 项边框 | **未选中项**：1dp squircle 描边，色 `outline`；**选中项**：描边宽 **0** | `TabRow.kt:358-363` |

**复刻要点**：指示器是**一个独立 View 在背景层平移**，不是每项自己换背景 —— 这样 `tween(200)` 才有意义。做法：`FrameLayout` 里放一个 `indicatorView`（squircle 背景），`onLayout` 后按选中项 `left/width` 算目标位置，`ObjectAnimator.ofFloat(indicator, "x", ...)` 配 `LinearInterpolator` + 200ms。项宽自适应 76~98：`onMeasure` 里先量内容宽 + 24（左右各 12），钳到 `[76, 98]`，总宽超容器就开横向滚动。

### 6.7 对话框 / 底部弹层

**对话框**（`DialogContentLayout.kt`）：

| 属性 | 值 |
|---|---|
| 圆角 | **32**（squircle） |
| 最大宽 | **420** |
| 外边距 | **12 × 12** |
| 内边距 | **24 × 24** |
| 遮罩 | `windowDimming` = `#4D000000`（浅）/ `#99000000`（深） |
| 进场 | 缩放 + 淡入，`spring(0.88, 450)`；遮罩 `tween(300, DecelerateEasing(1.5))` |
| 退场 | `tween(260, DecelerateEasing(1.5))`；遮罩 `tween(250)` |
| 大屏判定 | 高 ≥ **480** 且 宽 ≥ **840** |
| 大屏圆角 | `(设备圆角 - 12)` 钳到 **32~48** |
| 按钮区间距 | 底部 **12** |

**底部弹层**（`BottomSheetContentLayout.kt`）：

| 属性 | 值 |
|---|---|
| 圆角 | **28**，仅上两角（`topStart/topEnd = 28`，下两角 `0`） |
| 最大宽 | **640** |
| 外边距 | **0 × 0**（贴边） |
| 内边距 | **24 × 0** |
| 把手 | 宽 **45**（按下 **55**）× 高 **4**，圆角 **2**，容器高 **24** |
| 把手动画 | 按下 100ms 放大到 1.15/55dp，松开 150ms 回 1.0/45dp |
| 关闭阈值 | 位移 **150dp** 或速度 **800 dp/s** |
| 进出 | `folmeSpring(0.9, 0.38)`；遮罩 `tween(150)` |

**复刻要点**：对话框走 `Dialog` + 透明背景 + 自己画 squircle 卡片，圆角 32dp 必须上 squircle path（圆弧在 32dp 上一眼能看出「角太方」）。底部弹层用 `BottomSheetDialog` 但**关掉默认的 `shapeAppearance`**，换成自己的 `topStart/topEnd = 28` 的 `GradientDrawable` 或 squircle path；把手是一个 45×4 的 View，`setOnTouchListener` 里做 `SpringAnimation(translationY)`，松手时按 `translationY > 150dp || velocity > 800dp/s` 判关闭。

### 6.8 悬浮按钮（FAB）

| 属性 | 值 |
|---|---|
| 最小尺寸 | **60 × 60** |
| 形状 | `CircleShape`（正圆） |
| 背景色 | `primary` |
| 阴影 | **4dp**（`FloatingActionButtonDefaults.ShadowElevation`） |
| 按压缩放 | 源码未见（`FloatingActionButton.kt` 直接包 `Surface`，未挂 `SinkFeedback`） |

> FAB 本体不带缩放反馈。要加就用通用 `SinkFeedback`：**0.94 / spring(0.8, 600)**。

**复刻要点**：`FloatingActionButton` 的 `elevation` 走 `View.setElevation(4dp)` + `OutlineProvider` 设为圆。按压缩放挂 `StateListAnimator`：`state_pressed` → `scaleX/scaleY = 0.94`，用 `SpringAnimation` 而不是 `ObjectAnimator`，`dampingRatio 0.8 / stiffness 600`。

---

## 7. HyperOS 特有细节

### 7.1 分层阴影

源码里**没有多层阴影堆叠**。所有阴影都是单层：

| 组件 | 阴影 | 实现 |
|---|---|---|
| FAB | **4dp** | `Surface(shadowElevation)` → `graphicsLayer(shadowElevation)` |
| FloatingToolbar | **4dp** | `Modifier.dropShadow(Shadow(...))` |
| FloatingNavigationBar | **1dp** | `dropShadow` |
| Snackbar | 有（`dropShadow`，值未在常量里） | `Snackbar.kt:405` |
| Card | **0**（无阴影） | 卡片靠底色 + 圆角区分 |

> 「分层阴影」在 Miuix 里由 **7.3 的边框高光（BloomStroke）** 承担，不是多层 `elevation`。

**复刻要点**：`View.setElevation(4dp)` 单层足够。想更像 HyperOS 就叠一个 `layer-list`：底层 `#14000000` 偏移 2dp 模糊 4dp、上层 `#0A000000` 偏移 1dp 模糊 2dp —— 但源码没这么干，**优先单层**。

### 7.2 亚克力 / 模糊（miuix-blur）

| 参数 | 值 | 来源 |
|---|---|---|
| 默认模糊半径 | **20 dp** | `BlurDefaults.BlurRadius` |
| 最大模糊半径 | **150 dp** | `BlurDefaults.MaxBlurRadius` |
| 噪点抖动系数（抗色带） | **0.0045** | `BlurDefaults.NoiseCoefficient` |
| 渐进模糊噪点系数 | **0**（关闭） | `BlurDefaults.ProgressiveNoiseCoefficient` |
| 亮度调整 | `[-1, 1]`，默认 **0** | `BlurColors.brightness` |
| 对比度 | 乘数，默认 **1** | `BlurColors.contrast` |
| 饱和度 | 乘数，默认 **1** | `BlurColors.saturation` |
| 混合色列表 | 按顺序叠在模糊后的背景上 | `BlurColors.blendColors` |
| 平台下限 | **API 33**（`RuntimeShader`） | `docs/guide/blur.md` |

**复刻要点**：纯 View 体系里 `RenderEffect.createBlurEffect(20, 20, Shader.TileMode.CLAMP)` 挂在 `View.setRenderEffect` 上（API 31+），配 `setBackground(new ColorDrawable(0x33FFFFFF))` 做玻璃感。`minSdk` 低于 31 就直接放弃模糊，用 `#E6FFFFFF` / `#E6242424` 的半透明底代替 —— 观感损失比引入 `RenderScript` 或第三方库小得多。噪点 0.0045 是抗渐变色带的关键，API 33+ 可用 `RuntimeShader` 加，低版本忽略。

### 7.3 边框高光（BloomStroke）

`miuix-blur/.../highlight/HighlightStyle.kt`。三档尺寸 × 浅/深两套，共 6 个预设：

| 预设 | 描边色 | 内发光半径 | 主光 (pos, 强度) | 副光 (pos, 强度) |
|---|---|---|---|---|
| `GlassStrokeBigLight` | `#0DFFFFFF`（5%） | **3.5 dp** | `(0.5, 0.5, -0.5)` @ 0.3 | `(0.5, 0.6, -0.5)` @ 0.2 |
| **`GlassStrokeMiddleLight`（默认）** | `#0DFFFFFF` | **2.8 dp** | `(0.5, 0.5, -0.5)` @ 0.4 | `(0.5, 0.8, -0.5)` @ 0.25 |
| `GlassStrokeSmallLight` | `#0DFFFFFF` | **2.6 dp** | `(0.5, 0.5, -0.5)` @ 0.6 | `(0.5, 0.95, -0.5)` @ 0.35 |
| `GlassStrokeBigDark` | `#0DFFFFFF` | **1.7 dp** | 0.4 | 0.25 |
| `GlassStrokeMiddleDark` | `#0FFFFFFF`（6%） | **2.0 dp** | 0.5 | 0.25 |
| `GlassStrokeSmallDark` | `#14FFFFFF`（8%） | **2.3 dp** | 0.6 | — |

- 混合模式：`BlendMode.Plus`（加色）
- 光照模型：圆角矩形 SDF + 半球边缘法线，双方向光
- `dualPeak` 默认 `false`；`true` 时每盏光产生两个 180° 对置的峰（Apple 式镜面扫光）

**复刻要点**：这是 HyperOS 3 卡片「边缘一圈极淡白光」的来源。纯 View 里最省的实现：卡片外层套一个 `layer-list`，顶层是一个 1dp 的 `GradientDrawable` 描边（`setStroke(1dp, 0x0DFFFFFF)`）—— 用**上浅下深的线性渐变**代替方向光：`LinearGradient(0,0,0,h, 0x1AFFFFFF, 0x05FFFFFF)` 做成描边色。2.8dp 内发光在 View 上做不出来，跳过；1dp 描边已经能骗过眼睛。

### 7.4 图标风格

| 项 | 值 |
|---|---|
| 默认尺寸 | **24 × 24 dp** |
| 视图框（viewport） | **1137.6 × 1137.6**（源字体 `misymbol_vf.ttf` 的单位） |
| 粗细档位 | **5 档**：Light `wght=250` / Normal `305` / Regular `330` / Medium `430` / Demibold `500` |
| 默认档 | **Regular（330）** |
| 笔画换算（Light 实测） | 竖笔画宽 63 units → `63 / 1137.6 × 24 ≈ 1.33 dp` |
| 风格 | 单色填充路径（`SolidColor`），圆角线端（`QuadTo` 收角），非描边图标 |
| 颜色 | 由 `contentDescription` 外的 tint 决定，走 `LocalContentColor` |

**复刻要点**：24dp 画布 + 1.33dp 笔画（Light）/ 约 1.6dp（Regular）。纯 View 里最省事是**导出 SVG 转 VectorDrawable**，`android:width/height="24dp"`，`viewportWidth="1137.6"` 直接照抄，路径不用改。找不到图标就用 Material Symbols Rounded + `android:strokeWidth` 调 1.3dp，圆角端点（`strokeLineCap="round"`）。

### 7.5 Squircle 与页面转场

见 2.1 与 5.4。这两条是「一眼 HyperOS」的核心：
1. **大圆角一律 squircle**（对话框 32、弹层 28、卡片 16）
2. **页面转场 = 整屏滑入 + 被覆盖页 1/4 视差 + 10% 变暗 + 0.5 遮罩**，500ms，曲线 `response 0.8 / damping 0.95`

---

## 8. 数值速查

| 类别 | 数量 | 备注 |
|---|---|---|
| 颜色 token | **53**（浅/深各一套，共 106 个值） | 全部 ARGB 8 位 |
| 圆角半径 | **12** 个独立值 | 16 / 28 / 32 / 12 / 8 / 40 / 50 / 2 / 48 / 13 / 5 / 0 |
| 动效时长 | **13** 个不同值 | 50 ~ 1600 ms |
| 弹簧参数 | **27** 组 | dampingRatio + stiffness 对 |
| 缓动曲线 | **7** 条 | 3 条可直接映射 Android 内置插值器 |
| 间距 token | **6** 个 | 4 / 8 / 12 / 16 / 24 / 28 |
| 字号 | **9** 档 | 32 / 24 / 20 / 18 / 17 / 16 / 14 / 13 / 11 |

### 8.1 复刻优先级

| 优先级 | 内容 | 理由 |
|---|---|---|
| **P0** | 53 色 token + 12dp 页边距 + 16dp 卡片圆角 + 56dp 行高 + 16dp 行内边距 | 没有这些就不是 HyperOS，其余都是装饰 |
| **P0** | 开关 49×28 / 滑块 28 高 / 整行矩形按压 10% | 三个最高频交互 |
| **P1** | squircle path（对话框 32 / 弹层 28） | 大圆角处圆弧 vs squircle 肉眼可辨 |
| **P1** | 按压 0.94 + `spring(0.8, 600)` | 触感差异最直接 |
| **P2** | 页面转场 500ms + 1/4 视差 | 只有多页面项目才用得上 |
| **P3** | 模糊 20dp / 边框高光 | API 33+ 才有，且观感增益小于实现成本 |

---

## 附：本文件未覆盖的内容

- 字体族（源码未指定，实机为 MiSans）
- 数字等宽（源码未见）
- 深色模式的动态取色（Monet）映射：`theme/MonetMapping.kt` 有完整实现，但只对 Android 动态色生效，非默认路径
- 阴影的多层堆叠（源码为单层）
- 头像组件（源码无独立组件）
