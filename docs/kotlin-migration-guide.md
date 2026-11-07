# Java → Kotlin 转译规范

本文件是转译的**唯一依据**。所有批次必须遵守，否则混编会失败。

---

## 一、编译器与约束

| 项 | 值 |
|---|---|
| Kotlin | **1.9.23**（`kotlin-compiler-embeddable-1.9.23.jar`） |
| 编译器位置 | `C:\Users\Administrator\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\lib\` |
| `-jvm-target` | **1.8** |
| `-no-stdlib -no-reflect` | **必须加**（stdlib 手动进 classpath） |
| minSdk / dex 版本 | 26 / `--min-api 26` |
| AndroidX | **禁止**。只有 `android.jar` + `api-102.0.0.jar` + `service-classes.jar` |
| Compose | **禁止**。本地无 Compose 制品，且模块不用 gradle |

编译器 classpath 需要这 7 个 jar（缺一个就 `NoClassDefFoundError`）：

```
kotlin-compiler-embeddable-1.9.23.jar
kotlin-stdlib-1.9.23.jar
kotlin-reflect-1.9.23.jar
kotlin-script-runtime-1.9.23.jar
trove4j-1.0.20200330.jar
annotations-24.0.1.jar
kotlin-daemon-embeddable-1.9.23.jar
```

---

## 二、不可改动的契约

这些是运行时按**字符串或反射**查找的，改一个就直接失效：

| 契约 | 值 | 谁在用 |
|---|---|---|
| Xposed 入口类 | `io.github.ldxm666.mapclean.MainHook` | `META-INF/xposed/java_init.list` |
| 入口方法 | `MainHook` 必须有可被反射调用的 `init` | libxposed API 102 |
| 包名 | 四个包名整体不动 | manifest / scope / 各 hook 里的 `Class.forName` |
| 类名 | **全部不动** | 同上，且 `AndroidManifest.xml` 里写死了 Activity 全名 |
| 原生库名 | `libmapclean_labels.so` | `native_init.list` + `System.loadLibrary` |
| `Version.NAME / LABEL / CODE` | 字段名不动 | `build.ps1` 用正则从源码里抠版本号 |

> **`build.ps1` 会读 `Version.java` 的正则**：`NAME\s*=\s*"([^"]+)"`。
> 转成 Kotlin 后如果写成 `const val NAME = "2.3.2"`，正则仍然能匹配；
> 但如果加了 `@JvmField` 之外的修饰或换了引号风格就会失配。
> 转译 `Version.kt` 后必须改 `build.ps1` 的这条正则指向 `.kt`。

---

## 三、语法映射

### 静态成员

Java 的 `static` 没有直接对应。按**被谁调用**选择：

```java
// Java
static final int S4 = 16;
static int dp(Context c, float v) { ... }
static final String TAG = "x";
```

```kotlin
// Kotlin —— 被 Kotlin 和 Java 双方调用时
companion object {
    @JvmField val S4 = 16          // Java 侧: Cls.S4
    @JvmStatic fun dp(c: Context, v: Float): Int = ...   // Java 侧: Cls.dp(...)
    const val TAG = "x"            // 编译期常量，必须 const val 才能被注解引用
}
```

规则：
- `static final` 基本类型 / String → `@JvmField val` 或 `const val`（后者仅限字面量）
- `static` 方法 → `@JvmStatic fun`
- 纯 Kotlin 内部使用（无 Java 调用方）→ 普通 `companion object` 成员即可，不加注解
- **`object` 单例**不要滥用：会改变类加载时机

### 常量与注解

注解参数、`switch` 标签、`java_init.list` 里用到的必须是 **`const val`**，不能是 `@JvmField val`。

### `final` 类

Java 的 `final class X` → Kotlin `class X`（Kotlin 类默认 final）。

### 包级私有

Java 的包私有（无修饰符）→ Kotlin **默认就是 `public`**，要写成 `internal` 才等价。
本项目多数类是包私有工具类，转成 `internal class` 更贴近原意，但如果**被其它包引用**就必须保持 `public`（Kotlin 的 `internal` 是模块级）。

> 实际做法：**统一用 `public`（即不写修饰符）**。本项目四个包互相引用，`internal` 在单模块下虽然可用，但和 Java 的语义不同，不值得冒险。

### 字段默认值

```java
private int count;          // Java 默认 0
private boolean flag;       // false
private String name;        // null
```

```kotlin
private var count = 0
private var flag = false
private var name: String? = null
```

**注意**：Java 里 `boolean` 默认 `false`，Kotlin 必须有显式初值。漏写会编译错，这反而是好事。

### 可空性

这是转译里**最容易引入 bug** 的地方。原则：

- Java 里实际可能为 null 的 → `Type?`
- 明确不会 null 的 → `Type`（非空）
- 拿不准 → **先写 `Type?`**，让编译器逼你处理；写成非空而实际为 null，会在运行时 `NullPointerException`

典型坑：
```java
// Java —— 调用方传 null 是合法的
void log(String tag, String msg) { if (tag == null) tag = "?"; ... }
```
```kotlin
// 必须写 String?
fun log(tag: String?, msg: String?) { val t = tag ?: "?" }
```

### 数组

```java
int[] a = {1,2,3};            →  val a = intArrayOf(1,2,3)
String[] s = new String[n];   →  val s = arrayOfNulls<String>(n)
Object[][] o = new Object[n][]; → val o = arrayOfNulls<Array<Any?>>(n)
```

`Object[][]` 这种在 Kotlin 里很难看。如果代码里大量用它（如 `PanelTypes.Group` 的 `sections`），
可以考虑改成 `List<Pair<String, Array<Item>>>`，但**这是接口变更**，会影响调用方——
本批次内自洽即可，跨批次要提前说。

### 匿名内部类 → 对象表达式 / lambda

```java
// Java
new XposedInterface.Hooker() {
    @Override public Object hook(...) { ... }
}
```
```kotlin
// Kotlin —— 接口是 SAM 时可以直接 lambda
XposedInterface.Hooker { chain -> ... }

// 有多个方法或需要保存 this 时用对象表达式
object : XposedInterface.Hooker {
    override fun hook(...): Any? { ... }
}
```

**注意**：Java 8 的 lambda 在 d8 下会 desugar。Kotlin 的 lambda 编译成 `Function` 对象或 `invokedynamic`，
两者在 min-api 26 上都没问题，但**能用对象表达式就用对象表达式**，避免 d8 的 lambda 合成差异。

### 三元运算符

```java
int x = a > b ? a : b;   →   val x = if (a > b) a else b
```

### `switch`

```java
switch (k) { case 1: ...; break; default: ... }
```
```kotlin
when (k) { 1 -> ...; else -> ... }
```

**注意**：Java 的 `switch` 支持穿透（fall-through），`when` 不支持。有穿透的地方要显式合并分支。

### 循环

```java
for (int i = 0; i < n; i++)     →  for (i in 0 until n)
for (X x : list)                →  for (x in list)
while (...)                     →  while (...)
```

`for (i in 0 until n)` 是前闭后开，和 Java 一致。**不要**写成 `0..n`（那是闭区间，会多一次）。

### 异常

```java
try { ... } catch (Throwable t) { /* 忽略 */ }
```
```kotlin
try { ... } catch (t: Throwable) { /* 忽略 */ }
```

Java 的受检异常在 Kotlin 里没有约束，**不要**为此加 `throws`。

### 反射

```java
Class.forName(name)              →  Class.forName(name)
obj.getClass().getDeclaredField() →  obj.javaClass.getDeclaredField()
```

Kotlin 的 `::class.java` 和 `javaClass` 等价于 Java 的 `getClass()`。
**注意**：Kotlin 类的字段如果写成 `val`，反射看到的字段名和 Java 一致，但
**`@JvmField` 缺失时字段是 private + getter**，`getDeclaredField("name")` 会 `NoSuchFieldException`。

> 本项目大量使用反射读别人的字段，但**自己类的字段**被反射读的情况要逐个确认。

---

## 四、必须保持的行为细节

转译**不是重写**。以下任一改变都是 bug：

1. **求值顺序**：Java 从左到右求值，Kotlin 也是，但 `?:`、`?.` 的短路要确认
2. **整数除法**：`a / b` 在两边都是整除（`Int / Int`）
3. **整数溢出**：Kotlin 和 Java 一样静默溢出，`Int.MAX_VALUE + 1` 两边都是负数
4. **字符串拼接**：Java 的 `"" + null` 得到 `"null"`；Kotlin 的 `"$x"` 在 x 为 null 时也是 `"null"`
5. **`equals` / `==`**：Java 的 `==` 对引用比较，Kotlin 的 `==` 调 `equals`！
   - 需要引用比较时必须用 `===`
   - 这是**最高频的转译 bug**
6. **自动装箱**：`Integer` 的 `==` 在 -128..127 之外是引用比较，两边一样，但 Kotlin 默认用 `==` 调 equals，行为**不同**
7. **`static` 初始化顺序**：Java 的静态块按声明顺序执行，Kotlin 的 `companion object` 在类加载时初始化，顺序要按原样排
8. **`synchronized`** → `synchronized(this) { }` 或 `@Synchronized`
9. **`volatile`** → `@Volatile`
10. **`final` 局部变量被匿名类捕获** → Kotlin 不需要 `final`，但捕获的是**快照**这一点要一致

---

## 五、清单：每条都要过

转译每个文件后，逐条核对：

- [ ] 类名、包名、方法签名完全一致
- [ ] 所有 `static` 成员有正确的 `@JvmStatic` / `@JvmField` / `const val`
- [ ] 所有可能为 null 的引用写成 `Type?`，且调用点有非空处理
- [ ] 引用比较用了 `===`，值比较用了 `==`
- [ ] `for (i in 0 until n)` 而不是 `0..n`
- [ ] 数组用 `intArrayOf` / `arrayOfNulls`，不是 `Array<Int>`
- [ ] `switch` 的穿透已显式合并
- [ ] 反射读自己类的字段时，字段有 `@JvmField`
- [ ] 没有引入 AndroidX / Compose / Java 8+ 新 API（`minSdk 26` 已够用，但别用 `java.time` 以外的较新类）
- [ ] 文件是 **UTF-8 无 BOM、LF 换行**
- [ ] 没有删掉任何原有注释（注释里记录了大量真机验证结论和踩坑原因，是资产）

---

## 六、顺手要抓的 bug 类型

转译时逐文件审查，发现即记下、能修就修：

| 类型 | 表现 |
|---|---|
| **数组越界** | `new Row[n]` 后按下标写 m 个元素，m > n（腾讯 `tools[1]` 就是这么坏的） |
| **键名不一致** | 写入用的键和读取用的键拼法不同（`K_TOOL_PREFIX + "更多工具"` vs `tool_id_86`） |
| **同一键多处 UI** | 同一 Config 键在两个界面各有一份控件，互相不回读 |
| **null 未判** | Java 里靠隐式容忍，转 Kotlin 后炸 |
| **默认值不一致** | 同一键在两个界面显示不同默认值 |
| **死代码** | 有定义零调用（`buildOtherPage()`） |
| **`==` 陷阱** | 转 Kotlin 时把引用比较误改成值比较 |
| **资源泄漏** | 注册了 listener / hook 没有反注册 |

---

## 七、产出要求

每批转译完成后：

1. 对应 `.java` 文件**删除**，新增 `.kt` 文件（同名，仅扩展名变化）
2. 目录结构保持 `app/src/io/github/ldxm666/<pkg>/`
3. 不修改任何 `res/`、`META-INF/`、`libs/`
4. 报告：文件数、行数、发现的 bug 清单（文件:行 + 现象 + 是否已修）

---

## 八、转译后必须同步改动的构建工具

`javac` 单遍编译的时代，很多工具脚本里都写死了「源码一定是 .java」。
转成 Kotlin 之后这些假设全部失效，而且**失败方式很隐蔽**：不是报错，而是
悄悄产出一个少了类的包。

### 8.1 `app/build.ps1`

| 改动 | 原因 |
|---|---|
| 新增 `[2/7] compile Kotlin sources`（kotlinc 先跑） | kotlinc 能读 `.java` 做符号解析但不产出，所以它先跑；javac 再以 kotlin 输出为 classpath 编译剩余 java。反过来做 Kotlin 看不到 java 里的类型 |
| 版本号正则兼容 `Version.kt` | 原来硬读 `Version.java` |
| d8 输入加 `kotlin-stdlib` | 运行期要 `Intrinsics` / `CollectionsKt` |
| **d8 输入必须保留 `interface-102.0.0.jar`** | 见下 |

**最容易踩的一条**：`interface-102.0.0.jar` 必须作为 **d8 输入**，不能只当 `--lib`。

它里面有 `IXposedService` 的 AIDL 生成类（`$Stub` / `$Stub$Proxy` / `$Default`）。
我们的代码只静态引用 `IXposedService.Stub.asInterface()`，`$Stub$Proxy` 与 `$Default`
**没有任何字节码直接引用它们** —— jar 一旦只当 `--lib`，d8 就不会把这两个类打进 dex。
运行时 `XposedServiceHelper.onBinderReceived` 一调 `asInterface` 就：

```
java.lang.NoClassDefFoundError: Failed resolution of:
    Lio/github/libxposed/service/IXposedService$Stub;
```

表现是**框架状态永远「连接中」、作用域读数「未知」、所有开关只显示默认值**。
看起来像网络问题或框架没启用，实际是打包少了一个类。

> 教训：`javap` 核对「方法签名一致」不够，还要核对**哪些类进了 dex**。
> 验证方法：`unzip -p app.apk classes.dex | strings | grep 'IXposedService\$Stub\$Proxy'`

### 8.2 `tools/build_variant.py`（内置版构建）

四处硬依赖 `.java` 的地方，不改就连编译都进不去：

1. **版本号**：`build_variant.py` 原来硬读 `Version.java` → 改为优先 `Version.kt`、回落 `.java`。
2. **编译阶段**：`build_slim_module` 是 javac 单遍。树里已无 `.java`（除生成的桩），
   必然 `程序包 FloatingPanel 不存在`。补 kotlinc→javac 混编，并把 `kotlin-stdlib`
   加进 d8 输入。配套：
   - 所有 `.java`-only 的 glob / 裁剪名单 / `keepFiles` 匹配改成按 **stem + 扩展名**
   - 写桩之前**删掉同名 `.kt` 孪生**，否则同一个 FQN 声明两次
3. **白名单反读**：`check_host_whitelist` 只扫外层类。Kotlin 把 `@JvmStatic` 编译成
   静态桥方法，真正的 const-string 在 `$Companion` 里 → 误报「名单里一个字符串都没有」。
   改为同时扫外层与 `$Companion`，仍然 fail-closed。
4. **桩调用 internal 成员**：`App.listenEmbedded()` 声明为 `@JvmStatic internal fun`，
   Kotlin 把 internal 成员的 JVM 名改写成 `listenEmbedded$main`。
   桩里必须直接写 `listenEmbedded$main()`。
   **故意不用反射** —— 名字再变是编译期失败，而不是悄悄变成空调用。
   （这条是免 root 面板「只读 · 服务未连接」的老病根。）

### 8.3 一条推论

只要项目里同时存在 Java 与 Kotlin，**任何「按扩展名收集源文件」的脚本都要复查**。
本项目里这样做的有：`build.ps1`、`build_variant.py`、`build_source_zip.py`、
`module_prune.py`。Kotlin 转译那一轮只改了第一个，其余三个是后来逐个踩出来的。
