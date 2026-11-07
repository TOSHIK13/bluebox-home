# Контракт данных: как передавать «Продолжить просмотр» в Blue Box Home

Для разработчика (или агента) приложения, которое публикует строки Watch Next, чтобы они появились на главном экране Blue Box Home (`app.bluebox.home`). Описано то, что Home реально читает и как обрабатывает (код: `data/WatchNextSource.kt`, `logic/EntryRules.kt`, `logic/BackdropRules.kt`, `HomeActivity.kt`).

Продюсер сейчас один — Blue Box (`app.bluebox.tv`, `app-tv/.../watchnext`); контракт подходит и любому другому приложению. Менять контракт — согласованно в обоих репозиториях.

## 1. Канал передачи
Системный TvProvider, таблица Watch Next: `content://android.media.tv/watch_next_program` (`TvContract.WatchNextPrograms.CONTENT_URI`). Отдельного API между приложениями нет.

**Продюсер:**
- манифест: `<uses-permission android:name="com.android.providers.tv.permission.WRITE_EPG_DATA" />` (`normal`, выдаётся при установке);
- пишет строки только через `ContentResolver`/`androidx.tvprovider` в свои строки.

**Читатель (Home):**
- манифест: `READ_TV_LISTINGS` (`dangerous`) и `READ_EPG_DATA`. `READ_TV_LISTINGS` Home запрашивает диалогом при первом запуске; проверено на реальном ТВ (2026-11-07), права лаунчера не нужны;
- с `READ_TV_LISTINGS` TvProvider отдаёт строки `package_name = Home OR searchable = 1`; запрос без `selection` (любой WHERE → `SecurityException`). Home запрашивает именно так и фильтрует на клиенте;
- без разрешения Home видит только свои строки.

**Следствие для продюсера:** не ставить `searchable = 0` (по умолчанию 1), иначе строки для Home невидимы.

## 2. Поля строки

| Колонка `WatchNextPrograms` | Обязательность | Что кладём | Как использует Home |
|---|---|---|---|
| `COLUMN_TITLE` | **обязательно** | название сериала или фильма (не заголовок серии) | подпись над карточками и `contentDescription`; пустое → строка отбрасывается |
| `COLUMN_INTENT_URI` | **обязательно** | URI, который открывает нужное место в приложении-владельце, например `bluebox://play/tmdb_tv/1399?resume=1&s=3&e=9` | OK на карточке: `Intent.parseUri(uri, URI_INTENT_SCHEME)` + `FLAG_ACTIVITY_NEW_TASK`; пустое → строка отбрасывается; дубли по этому полю схлопываются |
| `COLUMN_TYPE` | рекомендуется | `TYPE_MOVIE` (0) или `TYPE_TV_EPISODE` (3) | `TYPE_MOVIE` без номера серии → бейдж «Фильм» |
| `COLUMN_SEASON_DISPLAY_NUMBER` | для серии | **строка**: `"2"`, допустимо `"2A"`; для фильма не заполнять | бейдж «С2 · Е6»; `""` и `"0"` считаются пустыми |
| `COLUMN_EPISODE_DISPLAY_NUMBER` | для серии | строка: `"6"` | бейдж; без него бейдж «С·Е» не рисуется (только «Е6», если нет сезона) |
| `COLUMN_THUMBNAIL_URI` | **рекомендуется** | кадр 16:9 (backdrop), `COLUMN_THUMBNAIL_ASPECT_RATIO = ASPECT_RATIO_16_9` | **первым** берётся для карточки и фона экрана при фокусе |
| `COLUMN_POSTER_ART_URI` | рекомендуется | постер 2:3 (`COLUMN_POSTER_ART_ASPECT_RATIO = ASPECT_RATIO_2_3`) | запасной вариант, если нет thumbnail; показывается целиком по центру, не обрезается |
| `COLUMN_LAST_PLAYBACK_POSITION_MILLIS` | рекомендуется | миллисекунды, ≥ 0 | полоса прогресса, «осталось N мин» |
| `COLUMN_DURATION_MILLIS` | рекомендуется | миллисекунды, > 0 | то же; при `0` прогресс не считается |
| `COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS` | **рекомендуется** | время последнего просмотра, мс UTC | порядок (новые первыми); `0` — без ограничения по возрасту |
| `COLUMN_WATCH_NEXT_TYPE` | рекомендуется | `CONTINUE`(0) — начатое; `NEXT`(1) / `NEW`(2) — следующая или новая серия, не начатая | `NEXT`/`NEW` при позиции `0` → бейдж «НОВАЯ» |
| `COLUMN_SHORT_DESCRIPTION` | не обязательно | краткое описание | читается, пока не отображается |
| `COLUMN_PACKAGE_NAME` | выставляет система | — | диагностика (лог) |

Прочие колонки Home не читает.

## 3. Какие строки Home покажет
Применяется по порядку (`EntryRules.select`):
1. есть непустые `intent_uri` и `title`;
2. `last_engagement` не старше **180 дней** (или равен 0);
3. прогресс **меньше 97%** (`position * 100 / duration`; зависшие строки на 99% скрываются; без длительности прогресс 0);
4. сортировка по `last_engagement` по убыванию;
5. дубли по `intent_uri` убираются;
6. не более **8** строк.

Если ничего не подошло, Home показывает карточку «открыть Blue Box».

**Бейджи:**
- «НОВАЯ»: `watch_next_type` равен 1 или 2 **и** позиция ≤ 0;
- «С2 · Е6» / «Е6»: есть номер серии;
- «Фильм»: номера серии нет, а `type = TYPE_MOVIE`;
- полоса прогресса внизу — при позиции > 0.

## 4. Картинки
- Home грузит картинку сам (без библиотек): `http`/`https` с таймаутом 5 с на соединение и 8 с на чтение, либо `content://`.
- Карточка 168×94 dp (16:9). Широкий кадр заполняет карточку (обрезаются края); портретный постер показывается целиком.
- Фон экрана: `thumbnail`, иначе `poster_art`; для URL TMDB вида `/t/p/<размер>/` размер заменяется на `w780`.
- Картинки должны открываться с ТВ без авторизации. Из РФ `image.tmdb.org` может требовать VPN/DNS — это проблема сети ТВ, а не контракта.

## 5. Как записать (пример без зависимостей)
```kotlin
val v = ContentValues().apply {
    put(WatchNextPrograms.COLUMN_TYPE, WatchNextPrograms.TYPE_TV_EPISODE)
    put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
    put(WatchNextPrograms.COLUMN_TITLE, "Игра престолов")
    put(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, "3")
    put(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, "9")
    put(WatchNextPrograms.COLUMN_THUMBNAIL_URI, "https://image.tmdb.org/t/p/w780/backdrop.jpg")
    put(WatchNextPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO, TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
    put(WatchNextPrograms.COLUMN_POSTER_ART_URI, "https://image.tmdb.org/t/p/w500/poster.jpg")
    put(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
    put(WatchNextPrograms.COLUMN_DURATION_MILLIS, 3_000_000)
    put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 900_000)
    put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis())
    put(WatchNextPrograms.COLUMN_INTENT_URI, "bluebox://play/tmdb_tv/1399?resume=1&s=3&e=9")
}
contentResolver.insert(WatchNextPrograms.CONTENT_URI, v)
```
(`WatchNextPrograms` — `android.media.tv.TvContract.WatchNextPrograms`.) Через `androidx.tvprovider` `WatchNextProgram.Builder` — те же колонки; проверьте, что номера сезона и серии попали именно в `*_DISPLAY_NUMBER`.

**Правила сопровождения строк:**
- одна строка на произведение (для сериала — на текущую серию), ключ — `internal_provider_id`;
- обновлять позицию при сохранении прогресса (шаг порядка 5 с), `last_engagement` — при каждом просмотре; порядок задаёт именно оно;
- удалять строку, когда просмотрено до конца или произведение убрано;
- не более 50 строк на приложение (лимит системы); Home берёт 8.

## 6. Как проверить
- Эмулятор, свои строки: `debug`-сборка Home умеет сеять данные (`SeedReceiver`): `adb -s emulator-NNNN shell am broadcast -a app.bluebox.home.SEED -n app.bluebox.home.debug/app.bluebox.home.SeedReceiver`; сценарий `scripts/scenarios/m6-home-smoke.txt`.
- Чужие строки из `shell` не прочитать: `shell` не держит `ACCESS_ALL_EPG_DATA`, `content query` даёт «No result found». Проверять надо логом самого Home (тег `BBHome`): `row pkg=… title=… poster=… thumb=…` по каждой строке и `watch-next rows=N packages=[…] err=…` в конце.
- Что увидеть на экране: заголовок, широкая картинка, нужный бейдж, полоса прогресса, OK открывает `intent_uri`.

## 7. Чего контракт не даёт
- Нет отдельных рядов каналов от продюсера: читается только Watch Next.
- Нет обратной связи Home → продюсер.
- Строки приложений с `searchable = 0` и строки без `READ_TV_LISTINGS` у Home не видны.
- Системный Google TV Home читает Watch Next по-своему (привилегированно); этот документ — только про Blue Box Home.
