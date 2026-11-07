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

## Риск данных
`TvProvider` отдаёт стороннему приложению только его собственные строки Watch Next (pitfalls.md). Обычный Home без привилегий не увидит строки SmartTube и Blue Box. **Подтверждено на эмуляторе 2026-11-01:** две копии Home (`.debug` и `.debug.two`), вторая записала 4 строки — первая видит только свои (`packages=[app.bluebox.home.debug]`), `READ_EPG_DATA` выдать нельзя (`pm grant` → «not a changeable permission type»). Значит чужие строки (SmartTube) Home без привилегий не получит; свои строки и строки Blue Box — только через его собственный источник. Запасные пути: экспортируемый `ContentProvider` в Blue Box; privileged-установка на приставку; чтение через `adb shell pm grant`.

## Не проверено на приставке
Станет ли Home «домом по умолчанию» на Google TV (выбор второго Home / отключение `launcherx`) — только владелец, на реальном ТВ.
