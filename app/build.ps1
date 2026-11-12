# MapClean (BMapClean x MapAdKiller) module build (offline toolchain, libxposed API 102)
#
# 自包含：源码/资源/元数据全部取自本脚本所在目录，产物落到本目录 dist\ 与仓库 output\。
# 临时构建目录必须 ASCII —— aapt2/d8 在非 ASCII 路径上会失败（见 MapAdKiller build.ps1 同款坑）。
#
# 用法： pwsh -File app\build.ps1
param([string]$SrcOverride = "")
$ErrorActionPreference = "Stop"
$Sdk   = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$Bt    = "$Sdk\build-tools\35.0.0"
$Aj    = "$Sdk\platforms\android-34\android.jar"
$Jdk   = "D:\JDK"
$Javac = "$Jdk\bin\javac.exe"
$Keytool = "$Jdk\bin\keytool.exe"

# ── Kotlin 工具链 ────────────────────────────────────────────────────────────
# 编译器来自 Gradle 8.9 发行版自带的 kotlin-compiler-embeddable（版本 1.9.23）。
# 本机没有独立 kotlinc，也没有 gradle 命令 —— 直接用 java -cp 调 K2JVMCompiler。
# 这套 jar 缺任何一个都会 NoClassDefFoundError（trove4j 尤其容易漏）。
$KotlinLib = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\lib"
$KotlinJars = @(
    "kotlin-compiler-embeddable-1.9.23.jar",
    "kotlin-stdlib-1.9.23.jar",
    "kotlin-reflect-1.9.23.jar",
    "kotlin-script-runtime-1.9.23.jar",
    "trove4j-1.0.20200330.jar",
    "annotations-24.0.1.jar",
    "kotlin-daemon-embeddable-1.9.23.jar"
) | ForEach-Object { Join-Path $KotlinLib $_ }
$KotlinCp = $KotlinJars -join ";"
# 注意：$KotlinStdlib 依赖 $App，而 $App = $PSScriptRoot 在下面才赋值 ——
# 定义放这儿会拼出 "\libs\..." 这种相对空路径，症状是 kotlinc 报
# "cannot access built-in declaration 'kotlin.String'"（stdlib 不在 classpath 上）。
# 所以它在 $App 之后定义。
$Java = "$Jdk\bin\java.exe"

$App = $PSScriptRoot
$KotlinStdlib = Join-Path $App "libs\kotlin-stdlib-1.9.23.jar"
$Out = Join-Path $App "dist"
# 仓库 output\（本脚本位于 app\，故向上一级）
$Repo = Split-Path $App -Parent
$Rel  = Join-Path $Repo "output"

if (-not (Test-Path $Aj)) { throw "android.jar not found: $Aj" }

$stamp = Get-Date -Format "HHmmss"
$srcRoot = Join-Path $env:TEMP "bmapcleanbuild"
try { New-Item -ItemType Directory -Force -Path $srcRoot -ErrorAction Stop | Out-Null } catch { $srcRoot = "$env:TEMP\bmapcleanbuild"; New-Item -ItemType Directory -Force -Path $srcRoot | Out-Null }
$src = Join-Path $srcRoot ("bc" + $stamp + "-" + [guid]::NewGuid().ToString("N").Substring(0,8))
New-Item -ItemType Directory -Force -Path "$src\build\stubs","$src\build\classes","$src\build\dex","$src\build\out" | Out-Null
$srcTree = if ($SrcOverride -and (Test-Path $SrcOverride)) { $SrcOverride } else { "$App\src" }
if (-not (Test-Path "$srcTree\io")) { throw "source tree not found: $srcTree" }
# 注意：源目录名必须落到 $src\src（脚本后续按 $src\src 取源码），
# 所以不能用 -Destination $src 直接拷目录（那会变成 $src\module-src）。
New-Item -ItemType Directory -Force -Path "$src\src" | Out-Null
Copy-Item "$srcTree\*" "$src\src" -Recurse -Force
Copy-Item "$App\stub-src","$App\res","$App\META-INF","$App\libs" -Destination $src -Recurse -Force
Copy-Item "$App\AndroidManifest.xml" $src
if (-not (Test-Path "$src\AndroidManifest.xml")) { throw "copy failed" }

# 产物名按 Version.NAME 动态生成 —— 这里曾经硬编码 v2.1.6，改版本号时漏改就会
# 把新版本号写进内容、却仍然输出旧文件名的 APK（发布时极易传错包）。
# 版本号来源可能是 Version.java 也可能是 Version.kt（转译后）—— 两处都试。
$verFile = @("$src\src\io\github\ldxm666\mapclean\Version.kt",
             "$src\src\io\github\ldxm666\mapclean\Version.java") |
        Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $verFile) { throw "Version.java / Version.kt not found" }
$verText = [System.IO.File]::ReadAllText($verFile)
if ($verText -notmatch 'NAME\s*(?::\s*String\s*)?=\s*"([^"]+)"') { throw "cannot parse Version.NAME from $verFile" }
$VerName = $Matches[1]
if ($VerName -notmatch '^\d+(\.\d+)+([.-][A-Za-z0-9]+)*$') { throw "unexpected Version.NAME: $VerName" }
$LspName = "MapClean-lsp-v$VerName.apk"
Write-Host "module version = $VerName  ->  output $LspName"

$EAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"

Write-Host "[1/6] prepare separate test API stubs (never packaged)"
& $Javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $Aj -d "$src\build\stubs" (Get-ChildItem "$src\stub-src" -Recurse -Filter *.java | % FullName) 2>"$src\build\j1.err"
if ($LASTEXITCODE -ne 0) { Get-Content "$src\build\j1.err"; throw "stub compile failed" }

# ── Kotlin 先编译 ───────────────────────────────────────────────────────────
# 混编顺序必须是 kotlinc → javac：
#   kotlinc 能读 .java 做符号解析（但不产出它们的 class），所以它先跑，
#   编译结果里就同时包含 kt 和「被 javac 编译前的 java 类型信息」；
#   然后 javac 以 kotlin 输出为 classpath 编译剩下的 .java，Kotlin 类可见。
#   反过来做则 Kotlin 看不到 java 源码里定义的类型。
Write-Host "[2/7] compile Kotlin sources"
$ktFiles  = @(Get-ChildItem "$src\src" -Recurse -Filter *.kt | % FullName)
$javaFiles= @(Get-ChildItem "$src\src" -Recurse -Filter *.java | % FullName)
if ($ktFiles.Count -gt 0) {
    # kotlinc 的 user classpath 里必须带 stdlib，否则 `listOf` 之类全部解析失败
    $ktCp = "$Aj;$src\libs\api-102.0.0.jar;$src\libs\service-classes.jar;$src\libs\interface-102.0.0.jar;$KotlinStdlib"
    & $Java -cp $KotlinCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -no-stdlib -no-reflect -nowarn -jvm-target 1.8 `
        -classpath $ktCp -d "$src\build\classes" @ktFiles @javaFiles 2>"$src\build\k1.err"
    if ($LASTEXITCODE -ne 0) { Get-Content "$src\build\k1.err" -ErrorAction SilentlyContinue; throw "kotlin compile failed" }
    Write-Host "      kotlin: $($ktFiles.Count) files"
} else {
    Write-Host "      kotlin: none (pure java build)"
}

Write-Host "[3/7] compile Java sources (+ libxposed service jar)"
if ($javaFiles.Count -gt 0) {
    & $Javac -encoding UTF-8 -nowarn --release 8 -classpath "$Aj;$src\libs\api-102.0.0.jar;$src\libs\service-classes.jar;$src\libs\interface-102.0.0.jar;$src\build\classes;$KotlinStdlib" -d "$src\build\classes" @javaFiles 2>"$src\build\j2.err"
    if ($LASTEXITCODE -ne 0) { Get-Content "$src\build\j2.err"; throw "module compile failed" }
    Write-Host "      java: $($javaFiles.Count) files"
}
$ErrorActionPreference = $EAP

Write-Host "[4/7] d8 -> classes.dex (module + libxposed service merged)"
& "$Jdk\bin\jar.exe" -cf "$src\build\classes.jar" -C "$src\build\classes" .
if ($LASTEXITCODE -ne 0) { throw "module classes jar failed" }
Copy-Item "$src\libs\service-classes.jar" "$src\build\service-classes.jar"
# kotlin-stdlib 必须进 d8：Kotlin 代码在运行期要它的实现（Intrinsics、CollectionsKt 等）。
# interface-102.0.0.jar 只做 --lib（编译用），不进 dex。
# interface-102.0.0.jar 必须作为 **d8 输入**（不是 --lib）一起合进 dex。
# 它里面有 IXposedService 的 AIDL 生成类：$Stub / $Stub$Proxy / $Default。
# 我们的代码只静态引用 IXposedService.Stub.asInterface()，$Stub$Proxy 与 $Default
# 没有任何字节码直接引用它们 —— 只要这个 jar 不在输入里，d8 就不会把它们打进 dex，
# 运行时 XposedServiceHelper.onBinderReceived 一调 asInterface 就
# NoClassDefFoundError: IXposedService$Stub，表现是框架状态永远"连接中"、
# 所有开关都读不到远程配置。重构构建脚本时漏掉这一项，就是这么坏的。
$d8Inputs = @(
    "$src\build\classes.jar",
    "$src\build\service-classes.jar",
    "$src\libs\interface-102.0.0.jar"
)
if (Test-Path $KotlinStdlib) { $d8Inputs += $KotlinStdlib }
$d8Args = ($d8Inputs | ForEach-Object { "`"$_`"" }) -join " "
cmd /c "`"$Bt\d8.bat`" --min-api 26 --lib `"$Aj`" --lib `"$src\libs\api-102.0.0.jar`" --output `"$src\build\dex`" $d8Args > `"$src\build\d8.log`" 2>&1"
Get-Content "$src\build\d8.log" -ErrorAction SilentlyContinue | Select-Object -Last 15
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\dex\classes.dex")) { throw "d8 failed" }

# 百度 / 腾讯必须仍被转发到。
#
# 一个 APK 只能声明一个 Xposed 入口（META-INF/xposed/java_init.list），百度与腾讯那两半
# 只能靠共享源码里的显式转发到达。曾经为了让"只并入高德"的免 root 精简构建能编译，
# 有人把这两个转发删掉了 —— 精简构建是过了，但完整模块的百度从此完全没反应
# （日志里连 BMapClean 都不出现）。精简构建的正确做法是生成 no-op 桩类
# （tools/build_variant.py 的 STUBS），不是把调用从共享源码里拿掉。
#
# 这里直接在 DEX 里查类型描述符，编译期就把它钉死。
$dexText = [System.Text.Encoding]::GetEncoding(28591).GetString(
    [System.IO.File]::ReadAllBytes("$src\build\dex\classes.dex"))
foreach ($symbol in @("Lio/github/ldxm666/bmapclean/MainHook;",
                      "Lio/github/ldxm666/bmapclean/App;",
                      "Lio/github/ldxm666/tmapclean/MainHook;",
                      "Lio/github/ldxm666/tmapclean/App;",
                      "Lio/github/ldxm666/mapadkiller/TmapHooks;")) {
    if (-not $dexText.Contains($symbol)) {
        throw "dispatcher regression: $symbol is not referenced any more - the corresponding map app would silently stop working"
    }
}

Write-Host "[5/7] aapt2 compile+link, then add dex/META-INF"
& "$Bt\aapt2.exe" compile --dir "$src\res" -o "$src\build\res.zip" > "$src\build\aapt2c.log" 2>&1
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\res.zip")) { Get-Content "$src\build\aapt2c.log"; throw "aapt2 compile failed" }
& "$Bt\aapt2.exe" link -o "$src\build\out\module.apk" --manifest "$src\AndroidManifest.xml" -I $Aj --min-sdk-version 26 --target-sdk-version 34 "$src\build\res.zip" > "$src\build\aapt2l.log" 2>&1
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\out\module.apk")) { Get-Content "$src\build\aapt2l.log"; throw "aapt2 link failed" }
Copy-Item "$src\build\dex\classes.dex" "$src\build\out\classes.dex"
# Complete official service/interface 102 classes are merged in step 3.
# Framework API definitions are compile-only and never included in the APK.
Copy-Item "$src\META-INF" "$src\build\out\META-INF" -Recurse -Force
    # module.prop 的 version / versionCode 必须跟 Version.java 同步。
    # 这里曾经是手写的：2.2.5 / 2.2.6 两个包里的 module.prop 一直停在旧值，
    # 结果 LSPosed 模块仓库页面显示的版本号长期落后（模块能装、版本号不对）。
    # 现在每次构建都从 Version.java 重写，单一口径。
    if ($verText -notmatch 'CODE\s*=\s*(\d+)') { throw "cannot parse Version.CODE from Version.java" }
    $VerCode = $Matches[1]
    $propPath = "$src\build\out\META-INF\xposed\module.prop"
    if (-not (Test-Path $propPath)) { throw "module.prop missing at $propPath" }
    $prop = [System.IO.File]::ReadAllLines($propPath)
    for ($i = 0; $i -lt $prop.Count; $i++) {
        if ($prop[$i] -match '^version=') { $prop[$i] = "version=$VerName" }
        elseif ($prop[$i] -match '^versionCode=') { $prop[$i] = "versionCode=$VerCode" }
    }
    [System.IO.File]::WriteAllLines($propPath, $prop, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "module.prop -> version=$VerName versionCode=$VerCode"
New-Item -ItemType Directory -Force -Path "$src\build\out\lib" | Out-Null
Get-ChildItem "$src\libs" -Directory | Where-Object { $_.Name -in @('arm64-v8a','armeabi-v7a','x86','x86_64') } | ForEach-Object {
    Copy-Item $_.FullName "$src\build\out\lib" -Recurse -Force
}
Push-Location "$src\build\out"
$seven = @("D:\7-Zip\7z.exe","C:\Program Files\7-Zip\7z.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($seven) {
    & $seven a -tzip module.apk classes.dex META-INF | Select-Object -Last 3
    if ($LASTEXITCODE -ne 0) { throw "dex/metadata ZIP packaging failed" }
    & $seven a -tzip -mx=0 module.apk lib | Select-Object -Last 3
    if ($LASTEXITCODE -ne 0) { throw "native ZIP packaging failed" }
} else {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open("$src\build\out\module.apk", "Update")
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$src\build\out\classes.dex", "classes.dex") | Out-Null
    Get-ChildItem "$src\build\out\META-INF" -Recurse -File | ForEach-Object {
        $entryName = $_.FullName.Substring("$src\build\out".Length+1).Replace("\","/")
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entryName) | Out-Null
    }
    $zip.Dispose()
    # Windows PowerShell/.NET Framework writes NoCompression entries as Deflate
    # method 8. Vector requires native libraries to use real ZIP STORED method 0.
    # The already required JDK jar tool's "0" option writes that exact format.
    & "$Jdk\bin\jar.exe" uf0 "$src\build\out\module.apk" lib
    if ($LASTEXITCODE -ne 0) { throw "native ZIP STORED packaging failed" }
}
Pop-Location
if (-not (Test-Path "$src\build\out\module.apk")) { throw "zip add failed" }

Write-Host "[6/7] zipalign + sign v1+v2+v3"
& "$Bt\zipalign.exe" -P 16 -f 4 "$src\build\out\module.apk" "$src\build\out\module-aligned.apk"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\out\module-aligned.apk")) { throw "16 KB zipalign failed" }
& "$Bt\zipalign.exe" -c -P 16 4 "$src\build\out\module-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "16 KB zipalign verification failed" }
# ── 签名密钥：绝不自动生成 ─────────────────────────────────────────────────
# 曾经这里是「没有就 keytool -genkeypair 生成一个」，结果 2.2.2 那次构建时
# app\debug.keystore 丢失 ⇒ 悄悄换了密钥 ⇒ 老用户（≤2.2.1，证书 cbecdaeb…）
# 无法覆盖升级（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。现在缺密钥/密钥不符都直接失败。
$ks = "$App\debug.keystore"
$ksExpected = "cbecdaeb31836c4ab2ffe9271b47148810d1335f3a70782dc9d2d29d986554ed"
if (-not (Test-Path $ks)) {
    throw ("签名密钥库缺失: $ks`n不要新建！请恢复固定密钥（证书 SHA-256 = $ksExpected），" +
           "否则老用户无法覆盖升级。历史副本示例: work\MapAdKiller-2.1.6\debug.keystore")
}
$ksInfo = & $Keytool -list -v -keystore $ks -storepass android 2>&1 | Out-String
$ksMatch = [regex]::Match($ksInfo, 'SHA256:\s*([0-9A-Fa-f:]{95})')
if ($ksMatch.Success) {
    $ksHex = ($ksMatch.Groups[1].Value -replace ':', '').ToLower()
    if ($ksHex -ne $ksExpected) {
        throw ("签名密钥库证书不匹配！`n  实际: $ksHex`n  期望: $ksExpected`n" +
               "用错密钥会让老用户无法覆盖升级，构建已中止。")
    }
}
cmd /c "`"$Bt\apksigner.bat`" sign --ks `"$ks`" --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out `"$src\build\out\BMapClean.apk`" `"$src\build\out\module-aligned.apk`" > `"$src\build\sign.log`" 2>&1"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\out\BMapClean.apk")) { Get-Content "$src\build\sign.log"; throw "apksigner sign failed" }
cmd /c "`"$Bt\apksigner.bat`" verify --print-certs `"$src\build\out\BMapClean.apk`" >> `"$src\build\sign.log`" 2>&1"
if ($LASTEXITCODE -ne 0) { Get-Content "$src\build\sign.log"; throw "apksigner verification failed" }
Get-Content "$src\build\sign.log" -ErrorAction SilentlyContinue | Select-Object -First 10
& "$Bt\zipalign.exe" -c -P 16 4 "$src\build\out\BMapClean.apk"
if ($LASTEXITCODE -ne 0) { throw "signed APK 16 KB alignment verification failed" }

Write-Host "[7/7] dist"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
New-Item -ItemType Directory -Force -Path $Rel | Out-Null
Copy-Item "$src\build\out\BMapClean.apk" "$Out\BMapClean.apk" -Force
Copy-Item "$src\build\out\BMapClean.apk" "$Rel\$LspName" -Force
Get-Item "$Rel\$LspName" | Select-Object FullName,Length
Write-Host "BUILD OK src=$src"
