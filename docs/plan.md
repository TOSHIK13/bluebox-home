# Blue Box Home — лёгкий лаунчер (M6-H1, M6-H2)

Основа — макет B (артефакт `9X9HE9BfZvxpyHRL2wXBJD`, v11). Отдельный APK `app.bluebox.home` (модуль `:app`), Views без Compose и DI: приставка Ugoos (Realtek RTD1325) упирается в отрисовку.

## Границы
- Только Home: часы, «Продолжить просмотр» (Watch Next), VPN-пилюля, Поиск, Настройки, док из 4 приложений + «Все приложения» (там же «Система»: Настройки, Wi-Fi, Bluetooth, VPN).
- Остальное остаётся системным: настройки (`Settings.ACTION_*`), заставка (Dream-сервис), роли. Питание и HDMI-вход в v1 нет.
- Логика — чистые классы `logic/` (`EntryRules`, `VpnRules`, `DockRules`) с unit-тестами; Android-часть — `data/` и два Activity.

## Куски
1. Модуль, логика, тесты → `verify.ps1 -Only ":app:testDebugUnitTest"`.
2. UI, Watch Next, VPN → сборка `:app:assembleDebug`.
3. Смоук на эмуляторе → `tv.ps1 scenario scripts/scenarios/m6-home-smoke.txt` (M6-H2).

## Данные Watch Next
Контракт — строки `TvContract.WatchNextPrograms` от Blue Box (`docs/contracts.md` в репозитории Blue Box). Полное описание полей и правил — `docs/watch-next-contract.md`.
- **Доступ.** Home запрашивает `READ_TV_LISTINGS` (`dangerous`, runtime-диалог, права лаунчера не нужны; проверено на реальном ТВ 2026-11-07, `docs/research/launcher-android-tv.md`). С ним TvProvider отдаёт строки `package_name = Home OR searchable = 1` (у строк по умолчанию 1), запрос только без `selection`: `WatchNextSource` так и делает. Без разрешения (`READ_EPG_DATA` выдать нельзя) видны лишь свои строки: на эмуляторе 2026-11-01 две копии Home видели каждая только свои.
- **Результат.** По скриншотам владельца (2026-11-07) Home выводит строки Blue Box на ТВ: заголовок, бейдж «С·Е»/«Фильм», полоса прогресса. Сами скриншоты в репозиторий не добавлены.
- **Картинки.** Blue Box пишет постер 2:3 в `poster_art_uri` и бэкдроп 16:9 в `thumbnail_uri` (Blue Box PR #160, 2026-11-07). Home берёт `thumbnail_uri`, при его отсутствии `poster_art_uri`.
- **Бейдж «НОВАЯ».** Нужен `WATCH_NEXT_TYPE_NEXT`/`NEW` при позиции 0. Blue Box пока всегда пишет `CONTINUE`, поэтому бейдж от Blue Box не появится (в debug-seed он есть).
- **Картинки по сети.** Home грузит их сам (`Posters`, без Coil); из РФ без VPN/DNS постеры `image.tmdb.org` могут не показаться.

## Не проверено на приставке
Станет ли Home «домом по умолчанию» на Google TV (выбор второго Home / отключение `launcherx`) — только владелец, на реальном ТВ.
