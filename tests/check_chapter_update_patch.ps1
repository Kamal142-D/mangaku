param([string]$ProjectPath = "$PSScriptRoot/../recovery/work/apktool-project")

$ErrorActionPreference = 'Stop'
$classes = @{}
Get-ChildItem -Path "$ProjectPath/smali/q2*" -Directory | Get-ChildItem -Filter '*.smali' -File | ForEach-Object {
    $descriptor = Get-Content -LiteralPath $_.FullName -TotalCount 1
    if ($descriptor -ceq '.class public final Lq2/j0;') { $classes.updater = $_.FullName }
    if ($descriptor -ceq '.class public final Lq2/D;') { $classes.search = $_.FullName }
    if ($descriptor -ceq '.class public abstract Lq2/v;') { $classes.changelog = $_.FullName }
}
if ($classes.Count -ne 3) { throw 'Missing patch target classes.' }
$smali = Get-Content -LiteralPath $classes.updater -Raw

if ($smali -match 'Lcom/mangaku/app/data/Manga;->e:Lq2/J0;') {
    throw 'Chapter updater still filters by the original manga source type.'
}

if ($smali -notmatch 'Lcom/mangaku/app/data/Manga;->m:Ljava/lang/String;') {
    throw 'Chapter updater no longer checks for a source URL.'
}

'Chapter update source-link regression check: PASS'

$search = Get-Content -LiteralPath $classes.search -Raw
if ($search -notmatch '(?s):cond_9\s+check-cast v2, Ljava/util/List;\s+(?:#[^\r\n]*\s+)*goto :goto_c') {
    throw 'Empty filtered search can still reach the unfiltered retry.'
}
'IMHentai filtered-empty regression check: PASS'

$metadata = Get-Content -LiteralPath "$ProjectPath/apktool.yml" -Raw
$changelog = Get-Content -LiteralPath $classes.changelog -Raw
if ($metadata -notmatch 'versionCode: 10\b' -or $metadata -notmatch 'versionName: 1\.5\.4\b' -or
    $changelog -notmatch 'const-string v3, "1\.5\.4"' -or $changelog -notmatch 'const-wide/16 v1, 0xa\b') {
    throw 'Expected version 1.5.4 (10) in APK metadata and changelog.'
}
'Release version regression check: PASS'
