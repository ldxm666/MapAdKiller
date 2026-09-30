# MapClean (BMapClean x MapAdKiller) module build (offline toolchain, libxposed API 102)
#
# 自包含：源码/资源/元数据全部取自本脚本所在目录，产物落到本目录 dist\ 与仓库 output\。
# 临时构建目录必须 ASCII —— aapt2/d8 在非 ASCII 路径上会失败（见 MapAdKiller build.ps1 同款坑）。
#
# 用法： pwsh -File src\modules\com.baidu.BaiduMap\build.ps1
$ErrorActionPreference = "Stop"
$Sdk   = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$Bt    = "$Sdk\build-tools\35.0.0"
$Aj    = "$Sdk\platforms\android-34\android.jar"
$Jdk   = "D:\JDK"
$Javac = "$Jdk\bin\javac.exe"
$Keytool = "$Jdk\bin\keytool.exe"

$App = $PSScriptRoot
$Out = Join-Path $App "dist"
# 仓库 output\（本脚本位于 src\modules\<pkg>\ 下，故向上三级）
$Repo = Split-Path (Split-Path (Split-Path $App -Parent) -Parent) -Parent
$Rel  = Join-Path $Repo "output"

if (-not (Test-Path $Aj)) { throw "android.jar not found: $Aj" }

$stamp = Get-Date -Format "HHmmss"
$srcRoot = Join-Path $env:TEMP "bmapcleanbuild"
try { New-Item -ItemType Directory -Force -Path $srcRoot -ErrorAction Stop | Out-Null } catch { $srcRoot = "$env:TEMP\bmapcleanbuild"; New-Item -ItemType Directory -Force -Path $srcRoot | Out-Null }
$src = "$srcRoot\bc$stamp"
if (Test-Path $src) { Remove-Item $src -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$src\build\stubs","$src\build\classes","$src\build\dex","$src\build\out" | Out-Null
Copy-Item "$App\stub-src","$App\src","$App\res","$App\META-INF","$App\libs" -Destination $src -Recurse -Force
Copy-Item "$App\AndroidManifest.xml" $src
if (-not (Test-Path "$src\AndroidManifest.xml")) { throw "copy failed" }

$EAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"

Write-Host "[1/6] compile libxposed-api stubs (compile-only)"
& $Javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $Aj -d "$src\build\stubs" (Get-ChildItem "$src\stub-src" -Recurse -Filter *.java | % FullName) 2>"$src\build\j1.err"
if ($LASTEXITCODE -ne 0) { Get-Content "$src\build\j1.err"; throw "stub compile failed" }

Write-Host "[2/6] compile module sources (+ libxposed service jar)"
& $Javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $Aj -classpath "$src\build\stubs;$src\libs\service-classes.jar" -d "$src\build\classes" (Get-ChildItem "$src\src" -Recurse -Filter *.java | % FullName) 2>"$src\build\j2.err"
if ($LASTEXITCODE -ne 0) { Get-Content "$src\build\j2.err"; throw "module compile failed" }
$ErrorActionPreference = $EAP

Write-Host "[3/6] d8 -> classes.dex (module + libxposed service merged)"
& "$Jdk\bin\jar.exe" -cf "$src\build\classes.jar" -C "$src\build\classes" .
Copy-Item "$src\libs\service-classes.jar" "$src\build\service-classes.jar"
cmd /c "`"$Bt\d8.bat`" --min-api 26 --lib `"$Aj`" --output `"$src\build\dex`" `"$src\build\classes.jar`" `"$src\build\service-classes.jar`" > `"$src\build\d8.log`" 2>&1"
Get-Content "$src\build\d8.log" -ErrorAction SilentlyContinue | Select-Object -Last 15
if (-not (Test-Path "$src\build\dex\classes.dex")) { throw "d8 failed" }

Write-Host "[4/6] aapt2 compile+link, then add dex/META-INF"
& "$Bt\aapt2.exe" compile --dir "$src\res" -o "$src\build\res.zip" > "$src\build\aapt2c.log" 2>&1
if (-not (Test-Path "$src\build\res.zip")) { Get-Content "$src\build\aapt2c.log"; throw "aapt2 compile failed" }
& "$Bt\aapt2.exe" link -o "$src\build\out\module.apk" --manifest "$src\AndroidManifest.xml" -I $Aj --min-sdk-version 26 --target-sdk-version 34 "$src\build\res.zip" > "$src\build\aapt2l.log" 2>&1
if (-not (Test-Path "$src\build\out\module.apk")) { Get-Content "$src\build\aapt2l.log"; throw "aapt2 link failed" }
Copy-Item "$src\build\dex\classes.dex" "$src\build\out\classes.dex"
# ── classes2.dex：libxposed 服务端 AIDL 类 ──────────────────────────────────
# 模块**自身 App 进程**不会被 LSPosed 注入（框架只注入作用域内的目标 App），
# 所以 io.github.libxposed.service 的 AIDL 桩必须由 APK 自带，否则收到 binder 时：
#   NoClassDefFoundError: Failed resolution of:
#       Lio/github/libxposed/service/IXposedService$Stub;
# → onServiceBind 永不触发 → 设置页恒显「服务未连接」、开关写不进去（v0.1.1 真机实测）。
# libs\service-classes.jar 只有上层封装、缺 AIDL，故单独补一个 dex；来源见 libs\README-aidl.md。
$aidlDex = "$src\libs\libxposed-service-aidl.dex"
if (-not (Test-Path $aidlDex)) { throw "missing $aidlDex (libxposed service AIDL dex)" }
Copy-Item $aidlDex "$src\build\out\classes2.dex"
Copy-Item "$src\META-INF" "$src\build\out\META-INF" -Recurse -Force
Push-Location "$src\build\out"
$seven = @("D:\7-Zip\7z.exe","C:\Program Files\7-Zip\7z.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($seven) {
    & $seven a -tzip module.apk classes.dex classes2.dex META-INF | Select-Object -Last 3
} else {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open("$src\build\out\module.apk", "Update")
    [System.IO.Compression.ZipFileExtensions]::CreateFromFilePath($zip, "$src\build\out\classes.dex", "classes.dex") | Out-Null
    [System.IO.Compression.ZipFileExtensions]::CreateFromFilePath($zip, "$src\build\out\classes2.dex", "classes2.dex") | Out-Null
    Get-ChildItem "$src\build\out\META-INF" -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring("$src\build\out".Length+1).Replace("\","/")
        [System.IO.Compression.ZipFileExtensions]::CreateFromFilePath($zip, $_.FullName, $rel) | Out-Null
    }
    $zip.Dispose()
}
Pop-Location
if (-not (Test-Path "$src\build\out\module.apk")) { throw "zip add failed" }

Write-Host "[5/6] zipalign + sign v1+v2+v3"
& "$Bt\zipalign.exe" -f 4 "$src\build\out\module.apk" "$src\build\out\module-aligned.apk"
if (-not (Test-Path "$src\build\out\module-aligned.apk")) { Copy-Item "$src\build\out\module.apk" "$src\build\out\module-aligned.apk" }
$ks = "$App\debug.keystore"
if (-not (Test-Path $ks)) {
    cmd /c "`"$Keytool`" -genkeypair -keystore `"$ks`" -storepass android -keypass android -alias bmapclean -keyalg RSA -keysize 2048 -validity 10000 -dname `"CN=BMapClean, OU=ENI, O=ENI, L=NA, S=NA, C=CN`" > `"$src\build\keytool.log`" 2>&1"
    if (-not (Test-Path $ks)) { Get-Content "$src\build\keytool.log"; throw "keytool failed" }
}
cmd /c "`"$Bt\apksigner.bat`" sign --ks `"$ks`" --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out `"$src\build\out\BMapClean.apk`" `"$src\build\out\module-aligned.apk`" > `"$src\build\sign.log`" 2>&1"
cmd /c "`"$Bt\apksigner.bat`" verify --print-certs `"$src\build\out\BMapClean.apk`" >> `"$src\build\sign.log`" 2>&1"
Get-Content "$src\build\sign.log" -ErrorAction SilentlyContinue | Select-Object -First 10
if (-not (Test-Path "$src\build\out\BMapClean.apk")) { throw "apksigner failed" }

Write-Host "[6/6] dist"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
New-Item -ItemType Directory -Force -Path $Rel | Out-Null
Copy-Item "$src\build\out\BMapClean.apk" "$Out\BMapClean.apk" -Force
Copy-Item "$src\build\out\BMapClean.apk" "$Rel\MapClean-lsp-v2.0.2.apk" -Force
Get-Item "$Rel\MapClean-lsp-v2.0.2.apk" | Select-Object FullName,Length
Write-Host "BUILD OK src=$src"
