param(
    [string]$BuildTools = "$env:LOCALAPPDATA/Android/Sdk/build-tools/34.0.0",
    [string]$JavaHome = "$env:ProgramFiles/Android/Android Studio/jbr"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $root 'tools/jadx-1.5.6/jre/bin/java.exe'
$apktool = Join-Path $root 'tools/apktool_3.0.3.jar'
$original = Join-Path $root 'builds/original/mangaku-v1.5.3.apk'
$patch = Join-Path $root 'patches/1.5.4.patch'
$expectedHashes = @{
    $original = 'AE4EF83FA163D0EFC7899B6340B3050123BCA51948F7C7A2F85217E4515947BC'
    $apktool = 'DBF930B076C6B9BE08D57C449CACEFC3BDD6B71EBD59B3066FC0E1F5B14F9423'
}
foreach ($path in $expectedHashes.Keys) {
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expectedHashes[$path]) {
        throw "Unexpected input checksum: $path"
    }
}
Get-Command git -ErrorAction Stop | Out-Null
foreach ($path in @($java, $patch, "$BuildTools/zipalign.exe", "$BuildTools/aapt.exe", "$BuildTools/lib/apksigner.jar",
    "$root/signing/mangaku-release.jks", "$root/signing/key.properties")) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file missing: $path" }
}

# A new directory per run preserves previous builds and the recovered working tree.
$run = Join-Path $root ('builds/rebuild/' + [Guid]::NewGuid().ToString('N'))
$project = Join-Path $run 'project'
$targets = Join-Path $run 'patch-targets'
New-Item -ItemType Directory -Path $targets -Force | Out-Null
& $java -jar $apktool d $original -o $project -p "$run/framework" -j 1
if ($LASTEXITCODE -ne 0) { throw 'APK decode failed.' }

# Apktool may assign different filenames to case-colliding classes on Windows.
# Match the DEX class descriptor, then apply ordinary patches to stable filenames.
$classes = @{
    '.class public final Lq2/D;' = 'imhentai.smali'
    '.class public final Lq2/j0;' = 'chapter-updates.smali'
    '.class public abstract Lq2/v;' = 'changelog.smali'
}
$destinations = @{ 'apktool.yml' = "$project/apktool.yml" }
Get-ChildItem -Path "$project/smali/q2*" -Directory | Get-ChildItem -Filter '*.smali' -File | ForEach-Object {
    $firstLine = Get-Content -LiteralPath $_.FullName -TotalCount 1
    foreach ($descriptor in $classes.Keys) {
        if ($firstLine -ceq $descriptor) {
            $name = $classes[$descriptor]
            if ($destinations.ContainsKey($name)) { throw "Duplicate class: $descriptor" }
            $destinations[$name] = $_.FullName
        }
    }
}
if ($destinations.Count -ne 4) { throw 'Could not locate all patch target classes.' }
foreach ($name in $destinations.Keys) {
    [IO.File]::WriteAllText("$targets/$name", [IO.File]::ReadAllText($destinations[$name]).Replace("`r`n", "`n"))
}
$relativeTargets = 'builds/rebuild/' + (Split-Path $run -Leaf) + '/patch-targets'
& git -C $root -c core.autocrlf=false apply --check "--directory=$relativeTargets" $patch
if ($LASTEXITCODE -ne 0) { throw 'Patch does not match the original APK.' }
& git -C $root -c core.autocrlf=false apply "--directory=$relativeTargets" $patch
if ($LASTEXITCODE -ne 0) { throw 'Patch application failed.' }
foreach ($name in $destinations.Keys) {
    Copy-Item -LiteralPath "$targets/$name" -Destination $destinations[$name]
}
& "$root/tests/check_chapter_update_patch.ps1" -ProjectPath $project

& "$PSScriptRoot/build_runtime.ps1" -OutputDirectory "$run/runtime" -BuildTools $BuildTools -JavaHome $JavaHome
& "$PSScriptRoot/integrate_runtime.ps1" -ProjectPath $project

$unsigned = Join-Path $run 'unsigned.apk'
$signed = Join-Path $run 'app-release.apk'
& $java -jar $apktool b $project -o $unsigned -p "$run/framework" -j 1
if ($LASTEXITCODE -ne 0) { throw 'APK build failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,"$run/runtime/classes.dex",'classes2.dex') | Out-Null }
finally { $archive.Dispose() }
& "$PSScriptRoot/sign_release.ps1" -InputApk $unsigned -OutputApk $signed -BuildTools $BuildTools

$badging = & "$BuildTools/aapt.exe" dump badging $signed
if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -notmatch "package: name='com\.mangaku\.app' versionCode='13' versionName='1\.7\.1'") {
    throw 'Built APK package/version verification failed.'
}

[ordered]@{
    versionName = '1.7.1'
    versionCode = 13
    sourceCommit = (Get-Content "$root/vendor/keiyoushi/UPSTREAM_COMMIT").Trim()
    sourceCatalogSha256 = (Get-FileHash "$root/vendor/keiyoushi/inventory.json").Hash
    builtAtUtc = [DateTime]::UtcNow.ToString('o')
    originalSha256 = $expectedHashes[$original]
    apktoolSha256 = $expectedHashes[$apktool]
    patchSha256 = (Get-FileHash -LiteralPath $patch -Algorithm SHA256).Hash
    runtimeDexSha256 = (Get-FileHash -LiteralPath "$run/runtime/classes.dex" -Algorithm SHA256).Hash
    runtimeInputs = @(Get-ChildItem "$root/runtime/src" -Recurse -Filter *.kt | ForEach-Object { [ordered]@{ path = $_.FullName.Substring($root.Length+1); sha256 = (Get-FileHash $_.FullName).Hash } })
    integrationSha256 = (Get-FileHash -LiteralPath "$PSScriptRoot/integrate_runtime.ps1").Hash
    kotlinJava = (& "$JavaHome/bin/java.exe" -version 2>&1 | Out-String).Trim()
    apkSha256 = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash
    java = (& $java -version 2>&1 | Out-String).Trim()
    buildTools = $BuildTools
    apk = $signed
} | ConvertTo-Json | Set-Content -LiteralPath "$run/build-receipt.json" -Encoding utf8
Write-Output "Verified APK: $signed"
Write-Output "Build receipt: $run/build-receipt.json"
