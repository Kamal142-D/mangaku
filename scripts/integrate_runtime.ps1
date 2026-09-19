param([Parameter(Mandatory)][string]$ProjectPath)
$ErrorActionPreference = 'Stop'
$classPaths = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
foreach($file in Get-ChildItem "$ProjectPath/smali" -Recurse -Filter *.smali) {
    $first = Get-Content -LiteralPath $file.FullName -TotalCount 1
    $descriptor = [regex]::Match($first, 'L[^;]+;').Value
    $classPaths[$descriptor] = $file.FullName
}
function EditClass([string]$descriptor, [scriptblock]$edit) {
    if(-not $classPaths.ContainsKey($descriptor)) { throw "Missing class $descriptor" }
    $path = $classPaths[$descriptor]
    $text = [IO.File]::ReadAllText($path).Replace("`r`n","`n")
    $updated = & $edit $text
    if($updated -ceq $text) { throw "No integration applied to $descriptor" }
    [IO.File]::WriteAllText($path,$updated)
}
function ReplaceOne([string]$text,[string]$old,[string]$replacement) {
    if(([regex]::Matches($text,[regex]::Escape($old))).Count -ne 1) { throw "Integration anchor missing or ambiguous: $old" }
    return $text.Replace($old,$replacement)
}
EditClass 'Lcom/mangaku/app/PalDexApplication;' { param($text)
    $anchor = 'invoke-super/range {p0 .. p0}, Landroid/app/Application;->onCreate()V'
    ReplaceOne $text $anchor "$anchor`n`n    invoke-static/range {p0 .. p0}, Lcom/mangaku/local/Bridge;->init(Landroid/app/Application;)V"
}
EditClass 'LX2/l;' { param($text)
    $text = [regex]::Replace($text,'(?ms)^\.method public static P\(Ljava/io/File;\)Ljava/lang/String;.*?^\.end method',@'
.method public static P(Ljava/io/File;)Ljava/lang/String;
    .locals 1
    invoke-static {p0}, Lcom/mangaku/local/Bridge;->read(Ljava/io/File;)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
'@)
    [regex]::Replace($text,'(?ms)^\.method public static R\(Ljava/io/File;Ljava/lang/String;\)V.*?^\.end method',@'
.method public static R(Ljava/io/File;Ljava/lang/String;)V
    .locals 0
    invoke-static {p0, p1}, Lcom/mangaku/local/Bridge;->write(Ljava/io/File;Ljava/lang/String;)V
    return-void
.end method
'@)
}
EditClass 'Lq2/B0;' { param($text)
    $text = ReplaceOne $text 'invoke-static {v0, v1}, LX2/l;->R(Ljava/io/File;Ljava/lang/String;)V' 'invoke-static {p0, v1}, Lcom/mangaku/local/Bridge;->save(Ljava/lang/Object;Ljava/lang/String;)V'
    $constructor = [regex]::Match($text,'(?ms)^\.method public constructor <init>.*?^\.end method').Value
    $text = ReplaceOne $text $constructor ($constructor.Replace('    return-void',"    invoke-static {p0}, Lcom/mangaku/local/Bridge;->register(Ljava/lang/Object;)V`n`n    return-void"))
    $text = [regex]::Replace($text,'(?ms)^\.method public static final e\(Lq2/B0;Lcom/mangaku/app/data/Manga;\)Ljava/lang/String;.*?^\.end method',@'
.method public static final e(Lq2/B0;Lcom/mangaku/app/data/Manga;)Ljava/lang/String;
    .locals 0
    iget-object p0, p1, Lcom/mangaku/app/data/Manga;->a:Ljava/lang/String;
    return-object p0
.end method
'@)
    $archive = [regex]::Match($text,'(?ms)^\.method public final G\(Ljava/io/File;Ljava/lang/String;\)V.*?^\.end method').Value
    $safe = [regex]::Replace($archive,'invoke-direct \{(\w+), (\w+), (\w+)\}, Ljava/io/File;-><init>\(Ljava/io/File;Ljava/lang/String;\)V', { param($m)
        "invoke-static {$($m.Groups[2].Value), $($m.Groups[3].Value)}, Lcom/mangaku/local/Bridge;->safeEntry(Ljava/io/File;Ljava/lang/String;)Ljava/io/File;`n`n    move-result-object $($m.Groups[1].Value)"
    })
    ReplaceOne $text $archive $safe
}
EditClass 'LH2/t;' { param($text)
    $text = ReplaceOne $text 'const-string p1, ""' 'const-string p1, "local-data"'
    $text = ReplaceOne $text '    if-nez p1, :cond_3' "    const/4 p1, 0x0`n`n    if-nez p1, :cond_3"
    $text = $text.Replace('const-string v1, "AniList"','const-string v1, "Data & sync"')
    $text.Replace('iget-object v2, p3, LB2/a;->H2:Ljava/lang/String;', 'const-string v2, "Local library, backups and optional AniList sync"')
}
EditClass 'LH2/r;' { param($text)
    [regex]::Replace($text,'(?s)(    :pswitch_0\n).*?(    sget-object v0, LM2/v;->a:LM2/v;)',"`$1    invoke-static {v0}, Lcom/mangaku/local/Bridge;->open(Landroid/content/Context;)V`n`n`$2")
}
EditClass 'Lq2/Q;' { param($text)
    [regex]::Replace($text,'(?ms)^\.method public final t\(Ljava/lang/Object;\)Ljava/lang/Object;.*?^\.end method',@'
.method public final t(Ljava/lang/Object;)Ljava/lang/Object;
    .locals 1
    iget-object v0, p0, Lq2/Q;->m:Lcom/mangaku/app/data/Manga;
    iget-object v0, v0, Lcom/mangaku/app/data/Manga;->a:Ljava/lang/String;
    invoke-static {v0}, Lcom/mangaku/local/Bridge;->deleteManga(Ljava/lang/String;)V
    sget-object v0, LM2/v;->a:LM2/v;
    return-object v0
.end method
'@)
}
EditClass 'Lq2/V0;' { param($text)
    $anchor = ".method public final t(Ljava/lang/Object;)Ljava/lang/Object;`n    .locals 21"
    ReplaceOne $text $anchor ($anchor + @'

    move-object/from16 v0, p0
    iget-object v0, v0, Lq2/V0;->m:Ljava/lang/String;
    invoke-static {v0}, Lcom/mangaku/local/SourceBridge;->chapters(Ljava/lang/String;)Ljava/util/List;
    move-result-object v0
    if-eqz v0, :builtin_chapters_fallback
    return-object v0
    :builtin_chapters_fallback
'@)
}
EditClass 'LC2/v1;' { param($text)
    # Add dialog: after its header divider, before weighted search results.
    # v0-v3 are dead here; v10 is the active Compose context.
    $anchor = "move-object/from16 v60, v41`n`n    move v6, v12`n`n    invoke-static/range {v0 .. v6}, LF/b0;->d(LT/o;FJLH/p;II)V"
    ReplaceOne $text $anchor ($anchor + @'

    invoke-static {}, Lcom/mangaku/local/SourceBridge;->label()Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    new-instance v2, Lcom/mangaku/local/OpenSourcesAction;
    invoke-direct {v2}, Lcom/mangaku/local/OpenSourcesAction;-><init>()V
    const/4 v3, 0x6
    invoke-static {v0, v1, v2, v10, v3}, LC2/v1;->w(Ljava/lang/String;ZLZ2/a;LH/p;I)V
'@)
}
$actions = Join-Path $ProjectPath 'smali/com/mangaku/local'
New-Item -ItemType Directory -Path $actions -Force | Out-Null
EditClass 'Lv2/s;' { param($text)
    $anchor = "iget-object v6, v11, Lcom/mangaku/app/data/Manga;->m:Ljava/lang/String;`n`n    invoke-static {v6}, Li3/o;->h0(Ljava/lang/CharSequence;)Z"
    $text = ReplaceOne $text $anchor (@'
move-object/from16 v6, v63
    invoke-static {v11, v6, v3, v10}, Lcom/mangaku/local/ProfileSourceButtons;->render(Lcom/mangaku/app/data/Manga;LH/a0;LB2/a;LH/p;)V
    
'@ + $anchor)
    # Both source actions are now always visible. Keep the existing Chapters
    # button when there is a source/cache, without a duplicate Add source row.
    $start = $text.IndexOf('    const v6, -0x1f893172')
    $end = $text.IndexOf('    :cond_44', $start)
    if($start -lt 0 -or $end -le $start) { throw 'Manga profile source button block missing' }
    $text.Remove($start,$end-$start).Insert($start,"    move-object/from16 v12, v88`n    goto :goto_1f`n`n")
}
# Reuse the existing compiled Compose Row setup, including its group lifecycle.
$sourceUi = [IO.File]::ReadAllText($classPaths['LC2/v1;']).Replace("`r`n","`n")
$rowMethod = [regex]::Match($sourceUi,'(?ms)^\.method public static final t\(LC2/s1;.*?^\.end method').Value
$rowStart = $rowMethod.IndexOf('    const/4 v1, 0x6')
$rowEnd = $rowMethod.IndexOf('    sget-object v3, LC2/s1;->h:LC2/s1;')
if($rowStart -lt 0 -or $rowEnd -le $rowStart) { throw 'Compose Row setup missing' }
$rowSetup = $rowMethod.Substring($rowStart,$rowEnd-$rowStart).Replace('p2','p3')
$profileButtons = @'
.class public final Lcom/mangaku/local/ProfileSourceButtons;
.super Ljava/lang/Object;
.implements LZ2/a;
.field private final id:Ljava/lang/String;
.method public constructor <init>(Ljava/lang/String;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lcom/mangaku/local/ProfileSourceButtons;->id:Ljava/lang/String;
    return-void
.end method
.method public final b()Ljava/lang/Object;
    .locals 1
    iget-object v0, p0, Lcom/mangaku/local/ProfileSourceButtons;->id:Ljava/lang/String;
    invoke-static {v0}, Lcom/mangaku/local/SourceBridge;->openFor(Ljava/lang/String;)V
    sget-object v0, LM2/v;->a:LM2/v;
    return-object v0
.end method
.method public static render(Lcom/mangaku/app/data/Manga;LH/a0;LB2/a;LH/p;)V
    .locals 9
    # ROW_SETUP
    invoke-static {}, LE1/v;->N()Ld0/d;
    move-result-object v0
    iget-object v1, p2, LB2/a;->c0:Ljava/lang/String;
    new-instance v2, LF2/Q;
    const/16 v5, 0x17
    invoke-direct {v2, p1, v5}, LF2/Q;-><init>(LH/a0;I)V
    invoke-static {}, Lr/U;->a()LT/o;
    move-result-object v3
    move-object v4, p3
    const/4 v5, 0x0
    invoke-static/range {v0 .. v5}, Lv/p;->d(Ld0/d;Ljava/lang/String;LZ2/a;LT/o;LH/p;I)V
    invoke-static {}, LR3/b;->I()Ld0/d;
    move-result-object v0
    invoke-static {}, Lcom/mangaku/local/SourceBridge;->pickLabel()Ljava/lang/String;
    move-result-object v1
    iget-object v5, p0, Lcom/mangaku/app/data/Manga;->a:Ljava/lang/String;
    new-instance v2, Lcom/mangaku/local/ProfileSourceButtons;
    invoke-direct {v2, v5}, Lcom/mangaku/local/ProfileSourceButtons;-><init>(Ljava/lang/String;)V
    invoke-static {}, Lr/U;->a()LT/o;
    move-result-object v3
    move-object v4, p3
    const/4 v5, 0x0
    invoke-static/range {v0 .. v5}, Lv/p;->d(Ld0/d;Ljava/lang/String;LZ2/a;LT/o;LH/p;I)V
    const/4 v4, 0x0
    const/4 v5, 0x1
    invoke-static {p3, v4, v5, v4, v4}, LA/k;->w(LH/p;ZZZZ)V
    return-void
    :cond_12
    invoke-static {}, LH/r;->I()V
    const/4 v0, 0x0
    throw v0
.end method
'@
[IO.File]::WriteAllText((Join-Path $actions 'ProfileSourceButtons.smali'),$profileButtons.Replace('    # ROW_SETUP',$rowSetup))
[IO.File]::WriteAllText((Join-Path $actions 'OpenSourcesAction.smali'),@'
.class public final Lcom/mangaku/local/OpenSourcesAction;
.super Ljava/lang/Object;
.implements LZ2/a;
.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method
.method public final b()Ljava/lang/Object;
    .locals 1
    invoke-static {}, Lcom/mangaku/local/SourceBridge;->open()V
    sget-object v0, LM2/v;->a:LM2/v;
    return-object v0
.end method
'@)
EditClass 'Lq2/W0;' { param($text)
    $anchor = ".method public final t(Ljava/lang/Object;)Ljava/lang/Object;`n    .locals 14"
    ReplaceOne $text $anchor ($anchor + @'

    iget-object v0, p0, Lq2/W0;->m:Lcom/mangaku/app/data/WebChapter;
    invoke-static {v0}, Lcom/mangaku/local/SourceBridge;->pages(Ljava/lang/Object;)Ljava/util/List;
    move-result-object v0
    if-eqz v0, :builtin_pages_fallback
    return-object v0
    :builtin_pages_fallback
'@)
}
EditClass 'Lq2/v;' { param($text)
    $text = ReplaceOne $text 'const-wide/16 v1, 0xa' 'const-wide/16 v1, 0xd'
    $text = ReplaceOne $text 'const-string v3, "1.5.4"' 'const-string v3, "1.7.1"'
    $messages = @(
        'Manga profiles now have Add source and Other sources side by side. Select a title from a built-in source to link it directly to the current manga while keeping your progress.',
        'زر إضافة المصدر كما هو، وبجانبه مصادر أخرى داخل صفحة المانجا. اختيار عمل من المصادر المدمجة يربطه بالمانجا الحالية مباشرة مع الاحتفاظ بتقدم القراءة.',
        'На странице манги доступны Add source и Other sources. Выбранное произведение связывается с текущей мангой с сохранением прогресса.'
    )
    $matches = [regex]::Matches($text,'const-string v0, "[^"\r\n]*"')
    if($matches.Count -lt 3) { throw 'Missing release note strings' }
    for($i=2; $i -ge 0; $i--) { $m = $matches[$i]; $text = $text.Remove($m.Index,$m.Length).Insert($m.Index,('const-string v0, "' + $messages[$i] + '"')) }
    $text
}
$manifest = Join-Path $ProjectPath 'AndroidManifest.xml'
$xml = [IO.File]::ReadAllText($manifest)
$xml = ReplaceOne $xml '</application>' @'
    <activity android:name="com.mangaku.local.DataActivity" android:exported="false" android:label="Data &amp; sync" />
    <activity android:name="com.mangaku.local.SourcesActivity" android:exported="false" android:label="Arabic sources" />
    <activity android:name="com.mangaku.local.SourceWebsiteActivity" android:exported="false" android:label="Source website" />
    <service android:name="com.mangaku.local.SyncJob" android:exported="false" android:permission="android.permission.BIND_JOB_SERVICE" />
</application>
'@
[IO.File]::WriteAllText($manifest,$xml)
$metadata = Join-Path $ProjectPath 'apktool.yml'
$text = [IO.File]::ReadAllText($metadata).Replace('versionCode: 10','versionCode: 13').Replace('versionName: 1.5.4','versionName: 1.7.1')
[IO.File]::WriteAllText($metadata,$text)
$assets = Join-Path $ProjectPath 'assets'
New-Item -ItemType Directory -Path $assets -Force | Out-Null
$root = Split-Path -Parent $PSScriptRoot
$sourceNotices = [IO.File]::ReadAllText("$root/vendor/keiyoushi/NOTICE") + "`n`n" + [IO.File]::ReadAllText("$root/vendor/keiyoushi/upstream/LICENSE") + "`n`njsoup 1.17.2`n" + [IO.File]::ReadAllText("$root/vendor/jsoup/LICENSE")
[IO.File]::WriteAllText("$assets/source-licenses.txt",$sourceNotices)
