# Сторонний лаунчер (HOME) на Android TV / Google TV / Ugoos: требования и риски

Спайк 2026-11-07. Только веб; устройства и adb не трогались. Пометки: **[пров.]** — страницу/исходник открыл сам; **[вторично]** — со слов форума/блога/пересказа; **[не пров.]** — не нашёл подтверждения.
Объект: модуль `app-home` (Blue Box Home). Ориентир железа: Ugoos на Realtek RTD1325, Android TV 11–14.

## 1. Минимальный набор функций ТВ-лаунчера

**Обязательно**
- Intent-filter `MAIN` + `HOME` + `DEFAULT` (+ `LEANBACK_LAUNCHER`, чтобы лаунчер виден в списке приложений ТВ). FLauncher так и делает, плюс `LAUNCHER_APP` и `LAUNCHER`, `launchMode=singleTask`, `screenOrientation=landscape`, `touchscreen required=false`, `leanback required=true` [пров.: gitlab.com/flauncher/flauncher …/AndroidManifest.xml]. Документация: без `LEANBACK_LAUNCHER` приложение не видно на ТВ [пров.: https://developer.android.com/training/tv/start/start]; баннер 320x180 xhdpi с текстом [пров.: там же].
- Управление D-pad без касания: все элементы достижимы пультом и геймпадом [пров.: https://developer.android.com/training/tv/publishing/checklist]. Back на корне лаунчера не должен закрывать единственную HOME-активность (оставаться/пересоздаваться) — вывод из поведения HOME, [не пров.] отдельным источником.
- Список приложений: и `LEANBACK_LAUNCHER`, и (отдельной секцией/фолбэком) `LAUNCHER` для сайдлоада. Сторонние APK без LEANBACK не видны в штатном лаунчере; Sideload Launcher Chainfire показывает именно их [вторично: https://chainfire.eu/articles/889/Sideload_Launcher_for_Android_TV_released]. Запуск: `getLeanbackLaunchIntentForPackage`, при null — `getLaunchIntentForPackage` [вторично: community-код, на устройстве проверить].
- Доступ к системным настройкам с главного экрана (иначе при потере штатного лаунчера настроек нет, см. §2). FLauncher держит ярлык «Настройки Android» [пров.: README flauncher].
- Реакция на смену `isLowRamDevice()` и ограничение памяти (§5).
- Обработка `onNewIntent`/повторного Home (singleTask/singleInstance): вернуть на корень, сбросить фокус/оверлеи.

**Желательно**
- Ряд «Продолжить просмотр»/Play Next (ограничения в §4).
- Ярлыки источников (HDMI/Live TV) и системных вещей: Wi-Fi/Ethernet, VPN, часы, USB.
- Обои: у части ТВ нет `WallpaperManager`, FLauncher делает свой механизм [пров.: README flauncher].
- Скрытие/порядок приложений, показ сайдлоада, иконки по баннеру.
- Реакция на клавиши, которых пульт может слать вместо HOME: `KEYCODE_ALL_APPS` (на Xiaomi) [вторично: https://mustafa.net/2026/11/01/replace-google-tv-launcher-without-root/]; в `KeyEvent` есть `KEYCODE_SETTINGS`, `KEYCODE_TV_INPUT`, `KEYCODE_GUIDE`, `KEYCODE_TV`, `KEYCODE_ALL_APPS` (API 28) [пров.: https://developer.android.com/reference/android/view/KeyEvent]. `KEYCODE_ASSIST` "не доставляется приложениям" [пров.: там же] — Assistant-кнопку лаунчер не перехватит.

**Не нужно (для нашей цели)**
- Виджеты/уведомления-панель: на ТВ нет трея; foreground-сервисы допустимы только пока пользователь в приложении [пров.: https://developer.android.com/training/tv/playback/memory].
- Собственный голосовой поиск/Assistant: системный Assistant работает независимо; ради него библиотеки Assistant в лаунчере — лишний вес [пров.: memory-страница советует их заменять]. Нужна лишь кнопка «Поиск» в приложении.
- Мультипрофили/пользователи, CEC-логика: CEC обслуживает система (HDMI-CEC HAL), лаунчер не участвует [не пров.].
- Свой экран первого запуска (wizard) — им владеет Setup Wraith/Provision [см. §2].

## 2. Как смена лаунчера ломает приставку и как вернуть

Факты (все про обычный путь: «установить HOME-приложение, выбрать по умолчанию»):
- На Google TV/Chromecast HOME-категория стороннего лаунчера не даёт стать лаунчером «просто так»: кнопка Home остаётся за штатным [вторично: https://mustafa.net/2026/11/01/replace-google-tv-launcher-without-root/; SO «android tv leanback app as default launcher»]. Обход № 1: отключить штатный через adb. Для Chromecast с Google TV надо отключить **оба** `com.google.android.apps.tv.launcherx` и `com.google.android.tungsten.setupwraith`, иначе wraith вернёт штатный; после этого кнопка YouTube может перестать работать; тестировали только на Chromecast с Google TV [пров.: README flauncher, gitlab.com/flauncher/flauncher/-/raw/master/README.md]. Обход № 2: Button Mapper (безопаснее). Обход № 3: `cmd package set-home-activity --user 0 <pkg>/<Activity>` + проверка `resolve-activity`, плюс accessibility-сервис, ловящий HOME [вторично: mustafa.net].
- Если ни одно HOME-приложение не включено — вместо домашнего экрана «голый» экран; лечение: `adb shell pm enable com.google.android.tvlauncher` или переустановка лаунчера; `pm list packages -d` показывает отключённое [вторично: https://github.com/anand34577/lumen-tv-launcher/wiki/TV-Tools]. Re-enable требует рабочего adb — включать отладку и проверять `adb connect` ДО отключения штатного [вторично: там же].
- Поведение Home на Hisense (Android TV 10): после удаления штатного кнопка Home ничего не делала даже с accessibility-сервисом; автор откатил [вторично: mustafa.net]. Поведение зависит от прошивки.
- Роль HOME: `adb shell cmd role get-role-holders --user 0 android.app.role.HOME` — сохранить «до» [вторично: lumen wiki]. Возврат: `set-home-activity` на штатный компонент, `pm enable --user 0 <pkg>`, очистить список accessibility-сервисов, перезагрузка [вторично: mustafa.net, lumen wiki, Fully Kiosk FAQ через поиск].
- «Очистить данные» у домашнего приложения помогает при залипшем Home после обновления (рекомендация Sony) [вторично: https://www.sony.ca/en/electronics/support/televisions-projectors-lcd-tvs-android-/kd-43x85k/articles/00175354]. Safe mode на Android TV как способ вернуть штатный лаунчер — [не пров.].
- Несколько HOME-приложений: при Home ОС показывает выбор «Всегда/Один раз» (или молча берёт сохранённое). Сброс «по умолчанию» — Настройки → Приложения → (лаунчер) → «Очистить значения по умолчанию» [не пров. на ТВ]; на ряде ТВ пункт скрыт, помогает `set-home-activity`. Для отчётов «не тот лаунчер после Home» — смотреть `dumpsys package | grep HOME` [не пров.].
- Android 10+: старт activity из фона запрещён, **но лаунчер освобождён**: «запуск инициирован лаунчером» — исключение; плюс `SYSTEM_ALERT_WINDOW` [пров.: https://developer.android.com/guide/components/activities/background-starts]. Следствие: из фоновых сервисов/ресиверов лаунчера запускать UI нельзя, только из видимой HOME-активности или по PendingIntent системы.
- Android 12 TV: кастомные SplashScreen-анимации не поддерживаются [пров.: start-страница]. Системные `ro.`-лаунчеры (поля вендора) тут не проверены: для Ugoos/RTD1325 конкретики нет [не пров.].
- Risk-пакет: по умолчанию ставить как обычное приложение, не отключая штатный; предупреждать при `pm disable`; первым делом инструкция отката в README/справке приложения.

## 3. Манифест: что обязательно и типичные ошибки

- Активность HOME: `MAIN`+`HOME`+`DEFAULT`(+`LEANBACK_LAUNCHER`), `exported=true`, `launchMode=singleTask` (или `singleInstance`), `screenOrientation=landscape`, `configChanges` на ориентацию/раскладку/uiMode/density (как у FLauncher) [пров.: flauncher manifest].
- `uses-feature android.hardware.touchscreen required=false`; `android.software.leanback`: `required=true` ограничит установку только ТВ-устройствами (на Play) [пров.: start-страница]. У нас APK вне Play — `true` допустим, но на Ugoos с урезанными feature-флагами возможны ложные отказы установки [не пров.]. Баннер `android:banner` и иконка обязательны [пров.].
- Видимость пакетов (Android 11+): без `<queries>` список приложений будет обрезан; `<queries>` с intent `MAIN` + `LEANBACK_LAUNCHER`/`LAUNCHER` решает штатно; `QUERY_ALL_PACKAGES` — «рассматривается при публикации в Play» [пров.: https://developer.android.com/training/package-visibility]. FLauncher использует `<queries>` для `MAIN` и `GET_CONTENT` [пров.]. Вне Play `QUERY_ALL_PACKAGES` допустим, но `<queries>` предпочтительнее (меньше сюрпризов у Ugoos-прошивок) [наш вывод].
- `REQUEST_DELETE_PACKAGES` — если нужно удаление из лаунчера (FLauncher так делает) [пров.].
- `resumeWhilePausing`: не нашёл упоминаний в документации Android TV или в манифесте FLauncher [не пров.]; не включать без причины.
- Ошибки: нет `LEANBACK_LAUNCHER` (приложение не видно); лаунчер без `DEFAULT` (не выбирается как HOME); `exported` не задан при targetSdk 31+ (установка падает); два «главных» активити с `HOME` (дубли в выборе); тяжёлый `Application.onCreate` (медленный старт по Home) [последние две — наш вывод].

## 4. Watch Next / «Продолжить просмотр» у чужих приложений

Проверено по AOSP-исходнику `TvProvider.java` (скачан и декодирован, `main`, 2927 строк; строки 2025–2041):
- Читать чужие строки в `channels/programs/preview_program/watch_next_program` может только держатель `ACCESS_ALL_EPG_DATA` (`signature|privileged`, скрытый) [пров.: https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml; TvProvider.java].
- У остальных запрос ограничен `package_name = вызывающий`; **исключение:** держатель `READ_TV_LISTINGS` (`dangerous`) видит строки `package_name=? OR searchable=1`, при этом любой `selection` (WHERE) запрещён → `SecurityException` [пров.: TvProvider.java, 2028–2040]. Условие `searchable` в коде написано как `Channels.COLUMN_SEARCHABLE` и применено ко всем таблицам этого пути, включая watch_next [пров.]. По умолчанию `searchable=1` у строк (DEFAULT 1) [пров.].
- `READ_EPG_DATA` — `normal`, deprecated, «не проверяется» [пров.: manifest]. `WRITE_EPG_DATA` — `normal` — для записи своих данных [пров.].
- Официальная документация описывает только запись своих данных и «Play Next» как системный ряд; чтения чужого она не обещает [пров.: https://developer.android.com/training/tv/discovery/recommendations-channel]. Запрос с WHERE даёт `SecurityException` [пров.: там же].
- **Не проверено на устройстве:** получит ли обычный APK `READ_TV_LISTINGS` на Ugoos/Android TV (диалог/авто-выдача/отказ), и вернёт ли provider строки Watch Next чужих приложений через эту ветку; зависит от `searchable`, которое выставляет каждое приложение.
- Другие лаунчеры: Dispatch Launcher заявляет синхронизацию Play Next в свой ряд "Watch Next" (ограниченные метаданные, без логотипов, список приложений-источников настраивается) [вторично: https://dispatch.featurebase.app/changelog/v100-2024-08-15-android-tv-play-next-support-and]. Projectivy: ряд Play Next не подтверждён, у пользователей жалобы «Syncler не появляется» [вторично: r/Projectivy_Launcher]. FLauncher: README не упоминает Watch Next [пров.].
- Альтернативы: (а) читать только свои строки (`package_name=our`) — ряд «Продолжить» из нашего же трекинга/Room, это и так наш каталог; (б) попытка `READ_TV_LISTINGS` с запросом без WHERE и фильтрацией на клиенте, фиксировать результат на ТВ; (в) `MediaSessionManager.getActiveSessions` требует enabled notification listener [не пров.]; (г) deeplink-карточки «открыть приложение X».
- Правила для своих публикаций: Play Next — только то, чем пользователь уже занимался; не клипы [пров.: https://developer.android.com/training/tv/publishing/checklist]. При `ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED` удалить строку и не вставлять заново [пров.: recommendations-channel].
- Системный лаунчер Google TV читает Watch Next как привилегированный (held `ACCESS_ALL_EPG_DATA`) [вывод из manifest, не из документа о лаунчере].

## 5. Производительность на слабом железе

- Определять `ActivityManager.isLowRamDevice()`; ориентиры: 1 ГБ → процесс ≤ 280 МБ суммарно (anon ≤ 160, графика 30–40, файл 60–80) [пров.: https://developer.android.com/training/tv/playback/memory].
- Не грузить картинки выше разрешения UI (1080p→720p на 720p UI), hardware-bitmaps в Glide (`Downsampler.ALLOW_HARDWARE_CONFIG`), без placeholder-картинок (цвет), `forceHasOverlappingRendering=false`, не собирать композиции на устройстве [пров.: там же].
- Списки только через `RecyclerView`/Lazy-списки; не держать ссылки на bitmap между экранами; не вызывать `System.gc()` [пров.].
- Нет блокирующих сетевых вызовов на старте; показывать UI, потом грузить [пров.]. Фоновые обновления — WorkManager с `setRequiresDeviceIdle(true)`, ≤ 30 МБ на джоб; AlarmManager избегать [пров.].
- Подробности про Compose/blur: источник не нашёл прямого запрета; Leanback помечен deprecated, Compose for TV рекомендован [пров.: start-страница, memory-страница]. Blur/Render-эффекты на RTD1325 [не пров.] — тестировать и держать флаг отключения. Пулы потоков: специального числа в документации нет [не пров.]; разумно 2–3 потока на IO + один декодер картинок (наш вывод).
- Прогрев: baseline profile для холодного старта [пров.: memory-страница].

## 6. Чего может не хватать / что может сломаться (сжато)

1. Home-кнопка остаётся за штатным лаунчером (Google TV) или пульт шлёт иной код (`ALL_APPS`).
2. Пропажа настроек/источников при отключённом штатном лаунчере: нужен ярлык настроек и HDMI-входов (`TvInputManager`, passthrough-вход через `TvContract.buildChannelUriForPassthroughInput`) [пров.: https://developer.android.com/reference/android/media/tv/TvContract].
3. Нет Watch Next чужих приложений без `READ_TV_LISTINGS`/привилегий (§4).
4. Сайдлоад-приложения без `LEANBACK_LAUNCHER` не видны без LAUNCHER-фолбэка.
5. Видимость пакетов (`<queries>`) на Android 11+.
6. Обрыв HOME-активности и зацикливание: убедиться, что она не падает на старте (иначе ОС снова стартует её).
7. Зависимость от Ugoos-прошивки: у Ugoos свой uLauncher [вторично: https://ugoos.com/ulauncher-release-1-7-14-update]; про RTD1325 конкретики нет, RTD1325 нашёл только у Vontar R5 (Android TV 14) [вторично: https://androidpctv.com/?p=52589].
8. Память/графика на слабом SoC.

## Не проверено (передать в проверку на эмуляторе/устройстве владельцем)
- Выдача `READ_TV_LISTINGS` стороннему APK и содержимое Watch Next через неё.
- Поведение выбора HOME и «Очистить значения по умолчанию» на Ugoos.
- Safe mode на Android TV; `resumeWhilePausing`; список ключей `LEANBACK_SETTINGS` (в `Intent`-справке не нашёл).
- Влияние `ro.`-лаунчеров и полное отключение штатного на Android 12–14.

## Проверено на реальном ТВ (2026-11-07)
- READ_TV_LISTINGS: prot=dangerous (провайдер com.android.providers.tv) — обычное runtime-разрешение, диалог доступен и не-системному приложению; READ_EPG_DATA/WRITE_EPG_DATA выданы при установке. Права «главного лаунчера» для этого не нужны.
- Роль HOME держит com.google.android.apps.tv.launcherx. Смена — только владельцем (Выбор лаунчера / системные настройки); без смены Home получает уведомления (NotificationListener) и использование (usage access) как обычное приложение.
- Доступ к уведомлениям для Blue Box Home включён (экран «Доступ к уведомлениям»).
- Первый запуск: HomeActivity.askPermissions — по шагу за onResume: READ_TV_LISTINGS → usage access → notification access, каждый один раз (Prefs.asked*).
