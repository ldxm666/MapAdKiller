# MapAdKiller 2.3.2 构建说明

本文覆盖四种产物的完整构建流程：**模块版**、**三个国内版内置包**、**Play 版内置包**。

所有命令都在仓库根目录执行，路径按 Windows + PowerShell 写；Linux/macOS 只需换分隔符。

---

## 一、环境

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17（或 8+） | `javac --release 8` 编译，产物跑在 Android 8.0+ |
| Android SDK build-tools | **35.0.0** | `app/build.ps1` 里写死了这个版本 |
| Android platform | **android-34** | 提供 `android.jar`（compile-only，不进包） |
| NDK | **26.3.11579264** | 只在重编 `libamapbldpatch.so` 时需要 |
| LSPatch CLI | **v1.2-487** | `lspatch-v1.2-487-release.jar`，打内置版必需 |

默认路径（脚本里可直接改）：

```
Sdk = %LOCALAPPDATA%\Android\Sdk
Jdk = JAVA_HOME 或 C:\Program Files\Java\jdk-17
NDK = %LOCALAPPDATA%\Android\Sdk\ndk\26.3.11579264
```

LSPatch jar 需要自己下载：

```
https://github.com/JingMatrix/LSPatch/releases/tag/v1.2
sha256 d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165
```

---

## 二、模块版（`MapClean-lsp-v2.3.2.apk`）

```powershell
cd MapAdKiller
powershell -NoProfile -ExecutionPolicy Bypass -File app\build.ps1
```

产物：
```
output\MapClean-lsp-2.3.2.apk        ← 发布用
app\dist\BMapClean.apk               ← 同内容，历史路径
```

六个步骤：准备 API 桩 → 编译 → d8 → aapt2 → zipalign+签名 → 落盘。

**版本号只改三处**，脚本会从 `Version.java` 读并回填：

```
app/src/io/github/ldxm666/mapclean/Version.java    NAME / LABEL / CODE
app/AndroidManifest.xml                            android:versionCode / versionName
app/META-INF/xposed/module.prop                    version / versionCode
```

> `app/debug.keystore` **必须存在**。脚本不会自动生成 —— 曾经自动生成过一次，
> 结果密钥丢了，老用户无法覆盖升级（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
> 证书 SHA-256 应为 `cbecdaeb31836c4ab2ffe9271b47148810d1335f3a70782dc9d2d29d986554ed`。

---

## 三、国内版内置包（高德 / 百度 / 腾讯）

内置版 = 把模块 APK 塞进宿主 APK，用 LSPatch 打补丁，用户不需要框架。

### 3.1 准备原始宿主 APK

从设备拉取当前安装的官方版：

```bash
adb shell pm path com.autonavi.minimap    # 高德
adb shell pm path com.baidu.BaiduMap      # 百度
adb shell pm path com.tencent.map         # 腾讯
adb pull <上面输出的路径> original.apk
```

2.3.2 对应的基线版本：

| 宿主 | 版本 | versionCode |
|---|---|---|
| 高德地图（国内版） | 17.02.0.2010 | 170200 |
| 百度地图 | 22.1.20 | 1665 |
| 腾讯地图 | 11.7.0 | 6013 |

### 3.2 构建

```powershell
$JAR = "path\to\lspatch-v1.2-487-release.jar"

# 高德
python tools\build_variant.py --Target amap `
  --OriginalApk "path\to\amap-1702002010-base.apk" `
  --OutputDirectory output\nonroot-amap-232 `
  --LspatchJar $JAR --SignatureBypass 2

# 百度
python tools\build_variant.py --Target baidu `
  --OriginalApk "path\to\baidu-22.1.20-base.apk" `
  --OutputDirectory output\nonroot-baidu-232 `
  --LspatchJar $JAR --SignatureBypass 2

# 腾讯
python tools\build_variant.py --Target tmap `
  --OriginalApk "path\to\tmap-11.7.0-base.apk" `
  --OutputDirectory output\nonroot-tmap-232 `
  --LspatchJar $JAR --SignatureBypass 2
```

每个约 3–5 分钟。产物在 `output\nonroot-<target>-232\<宿主名>-2.3.2-免root内置版.apk`。

### 3.3 精简构建做了什么

每个变体只保留自己那半的代码，其余包整体裁掉或替换成 no-op 桩：

| 变体 | 保留 | 裁掉 |
|---|---|---|
| `amap` | `mapadkiller` 全量、`mapclean` 界面 | `bmapclean` 全部 |
| `baidu` | `bmapclean` 全量 | `mapadkiller` 全部 |
| `tmap` | `tmapclean` 全量 | `mapadkiller`、`bmapclean` 大部分 |

> **`mapclean` 包在所有变体里都保留** —— 它是内置版的设置界面与悬浮球。
>
> 一个 APK 只能声明一个 Xposed 入口（`META-INF/xposed/java_init.list`），
> 百度与腾讯的 hook 靠共享源码里的显式转发到达。曾经有人为了让精简构建能编译，
> 把这两个转发删了 —— 精简构建是过了，但完整模块的百度从此完全没反应。
> 现在 `TARGETS[*]["invariants"]` 会在构建期检查转发是否还在，缺了直接失败。

### 3.4 验证

```powershell
python tools\verify_module.py --Apk "output\nonroot-amap-232\高德地图-2.3.2-免root内置版.apk"
```

构建脚本自己也会写 `variant-verification.json`，含每个 split 的 sha256、
精简计划（`slimPlan.removed` / `.stubbed`）和 LSPatch 输入哈希。

---

## 四、Play 版内置包（高德 17.02.2.2014）

### 4.1 为什么单独一套流程

Play 版是 **App Bundle 拆分包**，不是单个 APK：

```
base.apk                    164,456,253 B
split_config.arm64_v8a.apk   83,378,338 B   ← 所有 native 库都在这里
split_config.xxxhdpi.apk        615,811 B
split_config.zh.apk           1,065,369 B
```

而且它和国内版是**两次独立构建**：8 个 DEX 全部不同，`libamapr.so` 也不同
（32,984,120 vs 32,983,424 字节）。所以 native 补丁地址不能共用。

### 4.2 拉取 split

```bash
adb shell pm path com.autonavi.minimap
# 输出四行，逐个 pull 到同一个目录，保持原文件名
adb pull /data/app/.../base.apk              play-170222014\base.apk
adb pull /data/app/.../split_config.arm64_v8a.apk play-170222014\split_config.arm64_v8a.apk
adb pull /data/app/.../split_config.xxxhdpi.apk   play-170222014\split_config.xxxhdpi.apk
adb pull /data/app/.../split_config.zh.apk        play-170222014\split_config.zh.apk
```

### 4.3 构建

`--OriginalApk` 指向**目录**，脚本会自动按 `base.apk` 优先排序：

```powershell
python tools\build_variant.py --Target amap `
  --OriginalApk "path\to\play-170222014" `
  --OutputDirectory output\nonroot-amap-play-232 `
  --LspatchJar $JAR --SignatureBypass 2
```

产物是一个**目录**，四个文件：

```
高德地图-2.3.2-免root内置版\
    base.apk                    170,063,729 B
    split_config.arm64_v8a.apk   83,398,690 B
    split_config.xxxhdpi.apk        656,643 B
    split_config.zh.apk           1,077,529 B
```

发布时打包成 zip（含 `安装说明.txt`），文件名 `Amap-Play-2.3.2-nonroot-embedded.zip`。

### 4.4 native 偏移（Play 版）

`app/native/amap_buildings.c` 里是**双变体表**，运行时按三重自检选择：

```c
static const patch_variant VARIANTS[] = {
    { "17.02.0", 0x1f40868UL, 0x18b17e0UL, 0xe84148UL },  // 国内版
    { "17.02.2", 0x1f40b08UL, 0x18b1a74UL, 0xe842c8UL },  // Play 版
};
```

三关全过才写内存：
1. 槽值 `== base + fn_process`
2. 该地址四条指令是已知序言
3. gadget 处是 `mov x0, xzr; ret`

都不匹配 → **完全不碰**，只记一行 `anchor_mismatch`，高德零崩溃。

> 旧 gadget `0xe84148` 在 Play 版里已变成 `ret; stp…`（某个函数的中间），
> 照抄会跳进无效位置。这就是选择必须靠自检、不能靠版本号的原因。

新增一个版本 = 加一行，然后用 `elf_scan.py` 走同一条链重推：

```
xref "BuildingStyleLayer" → factory → constructor → vtable → slot[4]
```

工具在 `tools/`，推导记录见 `docs/`。

### 4.5 重编 native 库

改了 `amap_buildings.c` **必须**重编，否则 APK 里还是旧补丁，症状是"改了没生效"：

```powershell
python app\native\build_buildings.py
# NATIVE OK arm64-v8a 6912 bytes -> app\libs\arm64-v8a\libamapbldpatch.so
```

只有 arm64-v8a —— 里面每个常量都是 arm64 `libamapr.so` 的地址，
32 位或 x86 版本会得到错误地址，运行时自检会拒绝打补丁。

---

## 五、安装（内置版）

### 5.1 单 APK（国内版）

```bash
adb install -r -d -g "高德地图-2.3.2-免root内置版.apk"
```

### 5.2 split（Play 版）

```bash
adb install-multiple -r -d -g base.apk split_config.arm64_v8a.apk split_config.xxxhdpi.apk split_config.zh.apk
```

**只装 base.apk 会失败**：`INSTALL_FAILED_MISSING_SPLIT`。

部分国产 ROM（HyperOS / MIUI）的 `pm` 没有 `install-multiple`，`adb install-multiple`
又会被 `INSTALL_FAILED_USER_RESTRICTED` 拦。这时用 session API：

```bash
SID=$(su -c 'cmd package install-create -r -d -g' | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
su -c "cmd package install-write -S 170063729 $SID base ./base.apk"
su -c "cmd package install-write -S 83398690 $SID config.arm64_v8a ./split_config.arm64_v8a.apk"
su -c "cmd package install-write -S 656643 $SID config.xxxhdpi ./split_config.xxxhdpi.apk"
su -c "cmd package install-write -S 1077529 $SID config.zh ./split_config.zh.apk"
su -c "cmd package install-commit $SID"
```

内置版签名与官方不同，**装之前必须卸载官方版**（会清除登录态与离线地图）。

---

## 六、发布清单

| 文件 | 大小 | 放哪 |
|---|---|---|
| `MapClean-lsp-2.3.2.apk` | 4.7 MB | 主仓库 Release + 模块仓库 Release |
| `高德地图-2.3.2-免root内置版.apk` | 175 MB | 主仓库 Release |
| `百度地图-2.3.2-免root内置版.apk` | 220 MB | 主仓库 Release |
| `腾讯地图-2.3.2-免root内置版.apk` | 171 MB | 主仓库 Release |
| `Amap-Play-2.3.2-nonroot-embedded.zip` | 196 MB | 主仓库 Release（tag `v2.3.2-play`） |

> 模块仓库（`Xposed-Modules-Repo/io.github.ldxm666.mapadkiller`）**只发模块 APK**，
> 不发内置版。tag 格式 `2302-2.3.2`。

GitHub 会剥掉非 ASCII 文件名，Release 资产请用 ASCII 名上传：

```
MapClean-lsp-v2.3.2.apk
Amap-2.3.2-nonroot-embedded.apk
Baidu-2.3.2-nonroot-embedded.apk
Tencent-2.3.2-nonroot-embedded.apk
Amap-Play-2.3.2-nonroot-embedded.zip
```

---

## 七、常见问题

**构建报"需要class, interface或enum"** — 文件被写成了带 BOM 的 UTF-8。
PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会加 BOM。用 Python 字节写入，
或 `New-Object System.Text.UTF8Encoding($false)`。

**临时目录必须 ASCII** — `aapt2` / `d8` 在非 ASCII 路径上会失败。

**`libamapbldpatch.so` 没进包** — `pack_native_libraries` 会检查它存在。
如果改了 `.c` 却没重编，APK 里仍是旧补丁，症状是"改了没生效"。

**百度/腾讯内置版里为什么有 `libamapbldpatch.so`** — 打包脚本无条件复制所有
`app/libs/<abi>/*.so`。这两个变体的 `mapadkiller` 包已被裁掉，dex 里没有调用方，
所以它是死重量（736 字节），不影响功能。

**改完开关不生效** — 高德的建筑与帧率是**安装期门控**，改完必须强停重开高德。
面板文案里写明了这一点。
