# MapClean (BMapClean x MapAdKiller) module build (offline toolchain, libxposed API 102)
#
# 自包含：源码/资源/元数据全部取自本脚本所在目录，产物落到本目录 dist\ 与仓库 output\。
# 临时构建目录必须 ASCII —— aapt2/d8 在非 ASCII 路径上会失败（见 MapAdKiller build.ps1 同款坑）。
#
# 用法： pwsh -File app\build.ps1
$ErrorActionPreference = "Stop"
$Sdk   = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$Bt    = "$Sdk\build-tools\35.0.0"
$Aj    = "$Sdk\platforms\android-34\android.jar"
$Jdk   = "D:\JDK"
$Javac = "$Jdk\bin\javac.exe"
$Keytool = "$Jdk\bin\keytool.exe"

$App = $PSScriptRoot
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
Copy-Item "$App\stub-src","$App\src","$App\res","$App\META-INF","$App\libs" -Destination $src -Recurse -Force
Copy-Item "$App\AndroidManifest.xml" $src
if (-not (Test-Path "$src\AndroidManifest.xml")) { throw "copy failed" }

$EAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"

Write-Host "[1/6] prepare separate test API stubs (never packaged)"
& $Javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $Aj -d "$src\build\stubs" (Get-ChildItem "$src\stub-src" -Recurse -Filter *.java | % FullName) 2>"$src\build\j1.err"
if ($LASTEXITCODE -ne 0) { Get-Content "$src\build\j1.err"; throw "stub compile failed" }

Write-Host "[2/6] compile module sources (+ libxposed service jar)"
& $Javac -encoding UTF-8 -nowarn --release 8 -classpath "$Aj;$src\libs\api-102.0.0.jar;$src\libs\service-classes.jar;$src\libs\interface-102.0.0.jar" -d "$src\build\classes" (Get-ChildItem "$src\src" -Recurse -Filter *.java | % FullName) 2>"$src\build\j2.err"
if ($LASTEXITCODE -ne 0) { Get-Content "$src\build\j2.err"; throw "module compile failed" }
$ErrorActionPreference = $EAP

Write-Host "[3/6] d8 -> classes.dex (module + libxposed service merged)"
& "$Jdk\bin\jar.exe" -cf "$src\build\classes.jar" -C "$src\build\classes" .
if ($LASTEXITCODE -ne 0) { throw "module classes jar failed" }
Copy-Item "$src\libs\service-classes.jar" "$src\build\service-classes.jar"
cmd /c "`"$Bt\d8.bat`" --min-api 26 --lib `"$Aj`" --lib `"$src\libs\api-102.0.0.jar`" --output `"$src\build\dex`" `"$src\build\classes.jar`" `"$src\build\service-classes.jar`" `"$src\libs\interface-102.0.0.jar`" > `"$src\build\d8.log`" 2>&1"
Get-Content "$src\build\d8.log" -ErrorAction SilentlyContinue | Select-Object -Last 15
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\dex\classes.dex")) { throw "d8 failed" }

Write-Host "[4/6] aapt2 compile+link, then add dex/META-INF"
& "$Bt\aapt2.exe" compile --dir "$src\res" -o "$src\build\res.zip" > "$src\build\aapt2c.log" 2>&1
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\res.zip")) { Get-Content "$src\build\aapt2c.log"; throw "aapt2 compile failed" }
& "$Bt\aapt2.exe" link -o "$src\build\out\module.apk" --manifest "$src\AndroidManifest.xml" -I $Aj --min-sdk-version 26 --target-sdk-version 34 "$src\build\res.zip" > "$src\build\aapt2l.log" 2>&1
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\out\module.apk")) { Get-Content "$src\build\aapt2l.log"; throw "aapt2 link failed" }
Copy-Item "$src\build\dex\classes.dex" "$src\build\out\classes.dex"
# Complete official service/interface 102 classes are merged in step 3.
# Framework API definitions are compile-only and never included in the APK.
Copy-Item "$src\META-INF" "$src\build\out\META-INF" -Recurse -Force
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

Write-Host "[5/6] zipalign + sign v1+v2+v3"
& "$Bt\zipalign.exe" -P 16 -f 4 "$src\build\out\module.apk" "$src\build\out\module-aligned.apk"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\out\module-aligned.apk")) { throw "16 KB zipalign failed" }
& "$Bt\zipalign.exe" -c -P 16 4 "$src\build\out\module-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "16 KB zipalign verification failed" }
$ks = "$App\debug.keystore"
if (-not (Test-Path $ks)) {
    cmd /c "`"$Keytool`" -genkeypair -keystore `"$ks`" -storepass android -keypass android -alias bmapclean -keyalg RSA -keysize 2048 -validity 10000 -dname `"CN=BMapClean, OU=ENI, O=ENI, L=NA, S=NA, C=CN`" > `"$src\build\keytool.log`" 2>&1"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $ks)) { Get-Content "$src\build\keytool.log"; throw "keytool failed" }
}
cmd /c "`"$Bt\apksigner.bat`" sign --ks `"$ks`" --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out `"$src\build\out\BMapClean.apk`" `"$src\build\out\module-aligned.apk`" > `"$src\build\sign.log`" 2>&1"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$src\build\out\BMapClean.apk")) { Get-Content "$src\build\sign.log"; throw "apksigner sign failed" }
cmd /c "`"$Bt\apksigner.bat`" verify --print-certs `"$src\build\out\BMapClean.apk`" >> `"$src\build\sign.log`" 2>&1"
if ($LASTEXITCODE -ne 0) { Get-Content "$src\build\sign.log"; throw "apksigner verification failed" }
Get-Content "$src\build\sign.log" -ErrorAction SilentlyContinue | Select-Object -First 10
& "$Bt\zipalign.exe" -c -P 16 4 "$src\build\out\BMapClean.apk"
if ($LASTEXITCODE -ne 0) { throw "signed APK 16 KB alignment verification failed" }

Write-Host "[6/6] dist"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
New-Item -ItemType Directory -Force -Path $Rel | Out-Null
Copy-Item "$src\build\out\BMapClean.apk" "$Out\BMapClean.apk" -Force
Copy-Item "$src\build\out\BMapClean.apk" "$Rel\MapClean-lsp-v2.1.2.apk" -Force
Get-Item "$Rel\MapClean-lsp-v2.1.2.apk" | Select-Object FullName,Length
Write-Host "BUILD OK src=$src"
