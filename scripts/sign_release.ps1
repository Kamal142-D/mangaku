param(
    [string]$InputApk = "builds/intermediates/mangaku-v1.5.4-unsigned.apk",
    [string]$OutputApk = "builds/release/app-release.apk",
    [string]$BuildTools = "$env:LOCALAPPDATA/Android/Sdk/build-tools/34.0.0"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$inputPath = if ([IO.Path]::IsPathRooted($InputApk)) { $InputApk } else { Join-Path $root $InputApk }
$outputPath = if ([IO.Path]::IsPathRooted($OutputApk)) { $OutputApk } else { Join-Path $root $OutputApk }
$signingDir = Join-Path $root "signing"
$keystore = Join-Path $signingDir "mangaku-release.jks"
$properties = Join-Path $signingDir "key.properties"
$java = Join-Path $root "tools/jadx-1.5.6/jre/bin/java.exe"
$keytool = Join-Path $root "tools/jadx-1.5.6/jre/bin/keytool.exe"
$zipalign = Join-Path $BuildTools "zipalign.exe"
$apksigner = Join-Path $BuildTools "lib/apksigner.jar"

if (-not (Test-Path -LiteralPath $inputPath)) {
    throw "Input APK not found: $inputPath"
}

New-Item -ItemType Directory -Force -Path $signingDir, (Split-Path -Parent $outputPath) | Out-Null

if (-not (Test-Path -LiteralPath $keystore)) {
    if (Test-Path -LiteralPath $properties) {
        throw "Signing properties exist without their keystore. Restore the matching keystore before continuing."
    }

    $random = [byte[]]::new(36)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($random) } finally { $generator.Dispose() }
    $password = [Convert]::ToBase64String($random).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    [IO.File]::WriteAllLines($properties, @(
        "storePassword=$password",
        "keyPassword=$password",
        "keyAlias=mangaku",
        "storeFile=mangaku-release.jks"
    ))

    & $keytool -genkeypair -noprompt -keystore $keystore -alias mangaku -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Mangaku, O=Mangaku, C=US" -storepass $password -keypass $password
    if ($LASTEXITCODE -ne 0) { throw "Could not create the release keystore." }
}

if (-not (Test-Path -LiteralPath $properties)) {
    throw "Signing properties not found: $properties"
}

$values = @{}
foreach ($line in [IO.File]::ReadAllLines($properties)) {
    if ($line -and -not $line.StartsWith("#")) {
        $parts = $line.Split("=", 2)
        if ($parts.Count -eq 2) { $values[$parts[0].Trim()] = $parts[1].Trim() }
    }
}

foreach ($required in "storePassword", "keyPassword", "keyAlias") {
    if (-not $values[$required]) { throw "Missing $required in $properties" }
}

& $zipalign -f -p 4 $inputPath $outputPath
if ($LASTEXITCODE -ne 0) { throw "zipalign failed." }

$env:MANGAKU_STORE_PASSWORD = $values.storePassword
$env:MANGAKU_KEY_PASSWORD = $values.keyPassword
try {
    & $java -jar $apksigner sign --ks $keystore --ks-key-alias $values.keyAlias --ks-pass env:MANGAKU_STORE_PASSWORD --key-pass env:MANGAKU_KEY_PASSWORD --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false $outputPath
    if ($LASTEXITCODE -ne 0) { throw "APK signing failed." }
}
finally {
    Remove-Item Env:MANGAKU_STORE_PASSWORD, Env:MANGAKU_KEY_PASSWORD -ErrorAction SilentlyContinue
}

& $java -jar $apksigner verify --verbose --print-certs $outputPath
if ($LASTEXITCODE -ne 0) { throw "Signed APK verification failed." }

& $zipalign -c -p 4 $outputPath
if ($LASTEXITCODE -ne 0) { throw "Signed APK alignment verification failed." }

Get-FileHash -Algorithm SHA256 $outputPath
