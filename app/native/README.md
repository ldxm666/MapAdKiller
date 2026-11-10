# app/native —— 原生层说明

2.3.5 高德分类扩展：`configure(1, mask)` 的 bit 0 保持原有“全部 POI 隐藏”含义；bit 1–9 分别为餐饮、购物、医疗、学校与科教文化、交通、住宿、景点与休闲、金融、生活服务。分类模式从宿主 `styleiconslist.data` 指定的 `style_rule_*.data` 读取子键与原生业务编码，不把子键序号当业务编码。仅处理 `style_X_Main*` 的 12024/12029 点规则；目录损坏或类别未知时保留原样。修改设置后需重开地图，已加载的样式不会凭空重新解析。每次改动此逻辑须重建四种 ABI，并运行 `tests/NativeLabelTest.cpp` 和真实 CN/Play 样式对照。

两套互不相干的 native 实现，各自由独立脚本构建：

| 源码 | 产物 | 构建 | 用途 |
|---|---|---|---|
| `label_assets.cpp` + `vendor/zstd` | `libs/<4 个 abi>/libmapclean_labels.so` | `python app/native/build.py` | 改写 `map_assets/style_*X_Main` 样式 protobuf，只去兴趣点（高德/百度地图标注）。走框架的 `native_init` 内联钩子 |
| `amap_buildings.c` | `libs/arm64-v8a/libamapbldpatch.so` | `python app/native/build_buildings.py` | 高德去建筑物。**不使用**框架钩子，只是一个带 constructor 的普通 .so，由 Java 侧 `System.load` 后自己 mprotect 改虚表 |

---

## 一、改 `.c` 之后必须重跑构建并**重新提交 `.so`**

本仓库没有 gradle，构建链（`app/build.ps1`、`tools/build_variant.py`）只**复制** `app/libs/<abi>/*.so`，
不编译。所以：

```
python app/native/build_buildings.py     # 或 build.py（标签库）
git add app/libs/arm64-v8a/libamapbldpatch.so
```

**源码与二进制漂移是这里最危险的 bug**：模块会继续发旧补丁，而唯一症状是「我改的东西没生效」——
与「hook 写错了」完全无法区分，且从 APK 里看不出任何异常。所以 `.so` 与源码一起入库。

`build_buildings.py` 刻意做了硬失败：编译/ strip 失败、产物不是 ELF64 AArch64、产物为空，
都会删掉旧 `.so` 后非零退出 —— 不允许「用上一次的产物冒充这次成功」。

工具链（可复现）：NDK `26.3.11579264`，clang 17.0.2，triple `aarch64-linux-android26`，
`-O2 -fPIC -fvisibility=hidden -Wl,-z,max-page-size=16384`（16 KB 页对齐，Android 15+ 硬要求）。

只在 **arm64-v8a** 出货：`amap_buildings.c` 里每个常量都是 arm64 版 `libamapr.so` 的地址，
其它 ABI 编出来就是错的地址，自检会拒绝，等于白占体积。

---

## 二、高德每升级一个版本，必须重新核对三个偏移

**不要假设沿用**。以下是 17.02.0.2010 的核对流程，四步全在 `handover/tools/elf_scan.py`
（或随便一个 capstone 脚本）里能做完：

```bash
# 1) 从新 APK 抽出 libamapr.so
python -c "import zipfile;z=zipfile.ZipFile('base.apk');open('libamapr.so','wb').write(z.read('lib/arm64-v8a/libamapr.so'))"

# 2) 找图层工厂：字符串 "BuildingStyleLayer" 的 ADRP+ADD 交叉引用
python elf_scan.py xref libamapr.so BuildingStyleLayer
#    → 命中处就是工厂里的 strcmp 调用点。工厂结构：
#      adrp+add "BuildingStyleLayer" / bl strcmp / cbz → mov w20,#0x4b
#      adrp+add "BuildingStyleLayerDepth" / bl strcmp / cbz → mov w20,#0x4c
#      mov w0,#0x90 ; bl operator new ; bl <构造器> ; str w20,[x19,#0xc]

# 3) 反汇编工厂，读出构造器地址，再在构造器里找 vtable
python elf_scan.py dis libamapr.so <工厂地址> 40      # 找到 bl <构造器>
python elf_scan.py dis libamapr.so <构造器地址> 20    # 找 adrp x8,.. / add x8,x8,#0xNNN / str x8,[x19]
#    → vtable = adrp 的页基址 + add 的立即数

# 4) 读虚表，确认 [4] 槽仍是「每帧数据→几何」那个函数
python elf_scan.py vtable libamapr.so <vtable地址>
python elf_scan.py dis libamapr.so <vtable[4]> 8
#    期望形态：stp x29,x30,[sp,#-0x30]! / str x19,[sp,#0x10] / stp x20,x19,[sp,#0x20]
#              / mov x29,sp / ldr x8,[x0,#0x80] / ldrb w9,[x0,#0x7a]
```

三处常量改完，同步改 `amap_buildings.c` 顶部：

| 常量 | 含义 | 17.02.0.2010 |
|---|---|---|
| `VT_SLOT_PROCESS` | `&vtable[4]` | `0x1f40868` |
| `FN_PROCESS` | 该槽原本指向的函数 | `0x18b17e0` |
| `GADGET_ZERO_RET` | `mov x0,xzr; ret` 小工具 | `0xe84148` |

另外两处**必须一并核对**（`FN_PROCESS_PROLOGUE` / `GADGET_WORDS`）：
它们是槽里那个函数的前 4 条指令、以及 gadget 的 2 条指令，写死在源码里当第二重指纹。
版本一变，指令编码几乎必然变，改地址忘了改它们 = 自检永远拒绝 = 功能静默失效。

### 走过的四条错路，别重试

1. 让工厂对楼块返回 `null` → **宿主当场死**（`fg TOP`，无 ART crash、无 tombstone）
2. `vtable[8]`（`mov w0,#1; ret`，看着像 isVisible）→ 画面无变化
3. `BuildingTileLayoutNode` `[4]` → 无变化
4. `VectorStyleLayer` `[8]` → 无变化

---

## 三、失败模式：自检不通过会怎样（**重要，先看这里再报 bug**）

补丁线程在写内存**之前**做两重校验：

1. 虚表槽当前值必须等于 `base + FN_PROCESS`；
2. 该函数前 4 条指令、gadget 前 2 条指令必须与写死的期望值逐字节相同。

任何一条不满足 → **不写任何内存**，只打一行日志然后线程退出：

```
event=anchor_mismatch slot=0x… want=0x… got=0x… base=0x…
event=anchor_mismatch reason=prologue slot=0x… function=0x…
```

⇒ 结果：**楼块还在，但高德完全正常，零崩溃、零副作用**。功能静默失效，不会把宿主搞挂。

所以用户看到「建筑物没去掉」时的正确排查顺序是：

```bash
adb logcat -T "$(date '+%m-%d %H:%M:%S.000')" -s MapAmapBld MapAdKiller
```

| 日志 | 含义 |
|---|---|
| `event=building_native from=linker/host_native_dir/module_apk` | `.so` 已进进程（三种加载路径之一） |
| `event=building_native reason=no_context` / `not_found_in_host` | `.so` 没加载起来 → 查 APK 里 `lib/arm64-v8a/libamapbldpatch.so` 在不在 |
| `event=anchor_mismatch` | 偏移对不上 → 按上面第二节重新核对，别去动 hook |
| `event=already_patched` | 已打过（同进程重复加载），正常 |
| `event=building_patch slot=… old=… new=… base=…` | **成功**。三个地址应分别等于 `base+VT_SLOT_PROCESS` / `base+FN_PROCESS` / `base+GADGET_ZERO_RET` |
| `buildings` 相关一行都没有 | 开关没开，或安装期门控没读到（需强停重开高德） |

---

## 四、负向验证（每次改完偏移都跑一遍）

正向通过不算本事 —— 要证明「改错了它也不乱来」。做法：

1. 把 `amap_buildings.c` 里的 `FN_PROCESS` 末位改一个字节（例如 `0x18b17e0` → `0x18b17e1`）；
2. `python app/native/build_buildings.py`，重新出包，装到设备；
3. 冷启动高德，期望日志：
   ```
   event=building_native from=…
   event=anchor_mismatch slot=0x… want=0x… got=0x… base=0x…
   ```
   且**楼块存在**、高德**正常启动、可交互、无 crash**（`pidof com.autonavi.minimap` 稳定，无 `has died`）；
4. 改回正确值，重跑构建，确认 `event=building_patch` 回来。

第 3 步是本节的全部意义：证明自检确实挡住了错补丁，而不是「碰巧这次对上了」。
---

## 五、为什么百度 / 腾讯的免 root 包里也有 `libamapbldpatch.so`

`tools/build_variant.py` 的 `pack_native_libraries()` 是**三家共用**的一个函数：
它扫 `app/libs/<abi>/*.so` 全量打包，而不是按文件名挑。所以高德变体、百度变体、腾讯变体的
nested module apk 里都会出现这个 6 KB 的高德库。

这是**有意接受**的取舍，不是打包 bug：

- 收益：`required` 清单不必拆成 per-target，少一处分叉；将来再加 native 功能也不用到三个 target 里各改一遍
  （旧的「只按 `libmapclean_labels.so` 这个名字挑」写法，加第二个 native 功能时会**静默漏打**，
  表现是「功能装了但没生效」—— 这个项目已经吃过一次同样的亏）。
- 代价：6 KB 体积；百度/腾讯进程里没有任何代码会去 `System.load` 它，构造函数不会执行，等于一块冷数据。
- 唯一需要保证的是**它不能缺席**：`pack_native_libraries()` 对 `lib/arm64-v8a/libamapbldpatch.so`
  和 `lib/arm64-v8a/libmapclean_labels.so` 都做了硬断言，缺一个就 fail fast，
  不允许打出一个「没有 native 的 APK」。

如果你确实要彻底消除这 6 KB：把 required 清单改成按 target 取，并在 `pack_native_libraries()`
上加一个 `only` 参数。改之前请先把三家变体各构建一次对比 nested module 的条目集合 —— 别只改一半。
