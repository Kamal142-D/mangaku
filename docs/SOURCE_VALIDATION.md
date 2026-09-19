# Source validation — latest complete run

All 55 sources were attempted on 2026-09-18. The latest complete run passed 20 and failed 35. A pass covers a sampled catalog, nonempty search, chapter list, page parsing and decoding one image. It does not certify every title or the full reader/download UI for that source.

Evidence: `builds/runtime-tests/7c375ef54248472e9a6ed664794c0a6e/source-live-results.json` and `source-live-log.txt`.

The earlier complete run passed 19; a focused rerun passed EShadow, MangaTales and RocksManga. Arabhentai and Azora passed earlier but failed the latest complete run. Results reflect site availability and the sampled chapter at the time of testing.

| Source ID | Latest result | Last stage | Evidence / error |
| --- | --- | --- | --- |
| anyonemanga | failed | browse | المصدر يحتاج تحقق أو تسجيل دخول، أو طلبات أقل (HTTP 403). افتح الموقع ثم أعد المحاولة. |
| arabhentai | failed | image | Too many redirects |
| arabmanhwa | failed | browse | الموقع يحتاج تحقق أو تسجيل دخول. افتح الموقع ثم أعد المحاولة. |
| arabshentai | failed | browse | Unable to resolve host "arabshentai.com": No address associated with hostname |
| arabtoons | failed | browse | Read timed out |
| arbxcomix | failed | browse | Read timed out |
| areamanga | failed | browse | المصدر يحتاج تحقق أو تسجيل دخول، أو طلبات أقل (HTTP 403). افتح الموقع ثم أعد المحاولة. |
| ariatoon | passed | image | Titles 20; search 2; chapters 2; pages 5; image 1080x1150 |
| azora | failed | details | المصدر يحتاج تحقق أو تسجيل دخول، أو طلبات أقل (HTTP 503). افتح الموقع ثم أعد المحاولة. |
| comicverse | passed | image | Titles 20; search 1; chapters 1; pages 29; image 1040x1600 |
| despairmanga | failed | browse | Unable to resolve host "despair-manga.net": No address associated with hostname |
| detectiveconanar | failed | browse | المصدر يحتاج تحقق أو تسجيل دخول، أو طلبات أقل (HTTP 403). افتح الموقع ثم أعد المحاولة. |
| dilar | failed | pages | Unsupported encryption protocol version: 12 |
| duskoryvile | failed | browse | الموقع يحتاج تحقق أو تسجيل دخول. افتح الموقع ثم أعد المحاولة. |
| empirewebtoon | failed | browse | Read timed out |
| eshadow | passed | image | Titles 7; search 1; chapters 4; pages 19; image 1124x1600 |
| goonscans | failed | browse | Read timed out |
| hentailek | passed | image | Titles 24; search 1; chapters 101; pages 75; image 720x1476 |
| hentaiman | failed | browse | Unable to resolve host "hentaiman.net": No address associated with hostname |
| hentaislayer | failed | browse | Unable to resolve host "hentaislayer.net": No address associated with hostname |
| hijala | passed | image | Titles 5; search 3; chapters 629; pages 33; image 800x8955 |
| hizomanga | failed | details | الموقع يحتاج تحقق أو تسجيل دخول. افتح الموقع ثم أعد المحاولة. |
| kawiimanga | passed | image | Titles 20; search 1; chapters 28; pages 45; image 800x420 |
| lavascans | passed | image | Titles 32; search 1; chapters 359; pages 12; image 800x10429 |
| lonertranslations | passed | image | Titles 3; search 1; chapters 98; pages 69; image 1125x1600 |
| manga3asq | failed | browse | الموقع يحتاج تحقق أو تسجيل دخول. افتح الموقع ثم أعد المحاولة. |
| mangaailand | passed | image | Titles 8; search 1; chapters 158; pages 18; image 896x1280 |
| mangacloud | passed | image | Titles 20; search 1; chapters 822; pages 18; image 2816x1536 |
| mangadar | failed | details | No chapters in five sampled titles |
| mangahub | failed | image | Read timed out |
| mangalek | failed | search | الموقع يحتاج تحقق أو تسجيل دخول. افتح الموقع ثم أعد المحاولة. |
| mangalink | failed | browse | Empty catalog |
| mangalionz | failed | details | الموقع يحتاج تحقق أو تسجيل دخول. افتح الموقع ثم أعد المحاولة. |
| mangaspark | failed | search | الموقع يحتاج تحقق أو تسجيل دخول. افتح الموقع ثم أعد المحاولة. |
| mangastarz | failed | details | الموقع يحتاج تحقق أو تسجيل دخول. افتح الموقع ثم أعد المحاولة. |
| mangaswat | passed | image | Titles 20; search 1; chapters 8; pages 8; image 1200x718 |
| mangatales | passed | image | Titles 41; search 1; chapters 1; pages 10; image 1653x2339 |
| mangatek | passed | image | Titles 24; search 1; chapters 6; pages 15; image 2816x1536 |
| mangatime | passed | image | Titles 24; search 5; chapters 368; pages 18; image 1200x1722 |
| mangatuk | failed | browse | Empty catalog |
| manhatic | failed | browse | Read timed out |
| manhatok | passed | image | Titles 11; search 1; chapters 7; pages 17; image 1338x1920 |
| murim | failed | browse | Value <!DOCTYPE of type java.lang.String cannot be converted to JSONObject |
| neverscans | failed | browse | Empty catalog |
| oduto | passed | image | Titles 1; search 1; chapters 24; pages 48; image 5092x3054 |
| onma | failed | browse | المصدر رد بخطأ HTTP 404 |
| orcamanga | failed | pages | لم يعثر المصدر على صفحات. افتح الموقع للتحقق من الفصل أو تسجيل الدخول. |
| paradisebl | failed | browse | Unable to resolve host "paradise-bl.com": No address associated with hostname |
| rocksmanga | passed | image | Titles 12; search 12; chapters 28; pages 34; image 1200x1500 |
| stellarsaber | failed | browse | المصدر يحتاج تحقق أو تسجيل دخول، أو طلبات أقل (HTTP 403). افتح الموقع ثم أعد المحاولة. |
| teamx | passed | image | Titles 10; search 1; chapters 15; pages 30; image 720x2810 |
| xsanomanga | passed | image | Titles 8; search 1; chapters 217; pages 20; image 1600x1159 |
| yokai | passed | image | Titles 3; search 3; chapters 172; pages 17; image 1096x1600 |
| yonabar | failed | browse | Unable to resolve host "yonaber.com": No address associated with hostname |
| yurimoonsub | failed | browse | Read timed out |
