# Blue Box Home

Лёгкий лаунчер для Android TV (приставки с медленной отрисовкой): обычные Views, без Compose и сторонних библиотек.
Показывает «Продолжить просмотр» из Watch Next (`TvContract.WatchNextPrograms`), строки каналов, закреплённые приложения слева.
Отдельное приложение `app.bluebox.home`, связано с [Blue Box](https://github.com/TOSHIK13/BlueBox) только контрактом Watch Next.

## Команды (PowerShell, из корня)
| Что | Команда |
|---|---|
| Unit-тесты + debug-APK | `powershell -File scripts/verify.ps1` |
| Только тесты | `powershell -File scripts/verify.ps1 -Only ":app:testDebugUnitTest"` |
| Эмулятор/ТВ | `scripts/tv.ps1` (`install-home`, `start-home`, `home-grant`, `home-shot`, `scenario scripts/scenarios/m6-home-smoke.txt`) |

Подкоманды `build`, `install`, `reset-app` и сценарии приложения Blue Box унаследованы из его репозитория и для Home не годятся.

## Документы
- `docs/plan.md` — план и решения по лаунчеру.
- `docs/research/launcher-features.md` — что есть в лаунчерах Android TV.
- `docs/research/launcher-android-tv.md` — проверки на реальном ТВ, что можно читать у других приложений.
- `docs/design/` — макеты экрана.
