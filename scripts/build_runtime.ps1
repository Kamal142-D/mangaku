param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$BuildTools = "$env:LOCALAPPDATA/Android/Sdk/build-tools/34.0.0",
    [string]$JavaHome = "$env:ProgramFiles/Android/Android Studio/jbr",
    [switch]$Tests
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = Split-Path -Parent (Split-Path -Parent $BuildTools)
$android = Join-Path $sdk 'platforms/android-34/android.jar'
$java = Join-Path $JavaHome 'bin/java.exe'
if(-not (Test-Path -LiteralPath $java)) { throw 'A full JDK is required. Pass -JavaHome with a JDK 17 or 21 installation.' }
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function CachedJar($group, $artifact, $version) {
    $jars = @(Get-ChildItem -LiteralPath "$cache/$group/$artifact/$version" -Recurse -Filter "$artifact-$version.jar")
    if ($jars.Count -ne 1) { throw "Required cached dependency: $group/$artifact/$version" }
    return $jars[0].FullName
}
$stdlib = CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '1.9.24'
$jsoup = CachedJar 'org.jsoup' 'jsoup' '1.17.2'
$compiler = @(
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '1.9.24'), $stdlib,
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '1.9.24'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '1.9.24'),
    (CachedJar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    (CachedJar 'org.jetbrains' 'annotations' '13.0')
)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$jar = Join-Path $OutputDirectory 'runtime.jar'
$sources = @(Get-ChildItem "$root/runtime/src" -Recurse -Filter *.kt | ForEach-Object FullName)
if($Tests) { $sources += @(Get-ChildItem "$root/runtime/tests" -Recurse -Filter *.kt | ForEach-Object FullName) }
& $java -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -no-jdk -jvm-target 1.8 -classpath "$android;$stdlib;$jsoup" -d $jar @sources
if($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed.' }
& $java -cp "$BuildTools/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib $android --output $OutputDirectory $jar $stdlib $jsoup
if($LASTEXITCODE -ne 0) { throw 'Runtime DEX compilation failed.' }
$hashes = @($compiler + @($jsoup, $android, "$BuildTools/lib/d8.jar") | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 | Select-Object Path,Hash })
$hashes | ConvertTo-Json | Set-Content "$OutputDirectory/toolchain.json" -Encoding utf8
