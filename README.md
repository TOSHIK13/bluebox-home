# Blue Box Home

Лёгкий лаунчер для Android TV (приставки с медленной отрисовкой): обычные Views, без Compose и сторонних библиотек.
Показывает «Продолжить просмотр» из Watch Next (`TvContract.WatchNextPrograms`), строки каналов, закреплённые приложения слева.
Отдельное приложение `app.bluebox.home`, связано с приложением Blue Box только контрактом Watch Next.

## Команды (PowerShell, из корня)
| Что | Команда |
|---|---|
| Unit-тесты + debug-APK | `powershell -File scripts/verify.ps1` |
| Только тесты | `powershell -File scripts/verify.ps1 -Only ":app:testDebugUnitTest"` |
| Эмулятор/ТВ | `scripts/tv.ps1` (`install-home`, `start-home`, `home-grant`, `home-shot`, `scenario scripts/scenarios/m6-home-smoke.txt`) |

Подкоманды `build`, `install`, `reset-app` и сценарии приложения Blue Box унаследованы из его репозитория и для Home не годятся.

## Окружение (`scripts/env.ps1`)
Тулчейн лежит вне репозитория; пути задаются переменными. Личные значения (пути, `BLUEBOX_TV` — host:port реального ТВ) можно держать в `%USERPROFILE%\.bluebox-home.ps1`: `env.ps1` подключает его первым, а хук защиты ТВ берёт оттуда адрес.
| Переменная | Что | По умолчанию |
|---|---|---|
| `BLUEBOX_TOOLS` | каталог с `jdk-home.txt`, `android-sdk`, `android-user-home` | `<repo>\.tools` |
| `BLUEBOX_GRADLE_HOME` | записываемый Gradle-home | `%USERPROFILE%\.bluebox-gradle` |
| `BLUEBOX_GRADLE_RO_CACHE` | кэш зависимостей только для чтения (необязателен) | не задан |

Нет JDK/SDK — `verify.ps1` выходит с кодом 2 «ПРОБЛЕМА ОКРУЖЕНИЯ». В worktree `local.properties` копируется из основного чекаута.

## Защита реального ТВ
`scripts/hooks/guard-real-tv.ps1` подключён как PreToolUse-hook в `.claude/settings.json`: `adb` только с `-s emulator-NNNN`; на ТВ разрешены лишь `install-home`, `start-home`, `stop-home`, `home-tour` через `tv.ps1 -Device tv -AllowRealTv -Variant beta` по команде владельца.

## Документы
- `docs/plan.md` — план и решения по лаунчеру.
- `docs/research/launcher-features.md` — что есть в лаунчерах Android TV.
- `docs/research/launcher-android-tv.md` — проверки на реальном ТВ, что можно читать у других приложений.
- `docs/design/` — макеты экрана.

## Лицензия
MIT, см. LICENSE.
