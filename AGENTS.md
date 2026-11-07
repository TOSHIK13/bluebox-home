# AGENTS.md — Blue Box Home

Отдельный репозиторий (вынесен из BlueBox 2026-11-07, история `app-home` сохранена). Язык документов и общения — русский, идентификаторы — английские.

- Модуль один: `:app` (applicationId `app.bluebox.home`), plain Views, без зависимостей кроме `kotlin-test` в тестах. Причины — `docs/plan.md`.
- Ворота: `powershell -File scripts/verify.ps1` (exit 0/1/2).
- Реальный ТВ (адрес в AGENTS.md репозитория BlueBox) без команды владельца в чате не трогать. Защита — `scripts/hooks/guard-real-tv.ps1` (подключить как PreToolUse-hook в `.claude/settings.json`). `adb` только с `-s emulator-NNNN`.
- Контракт данных — Watch Next от Blue Box (`WatchNextPrograms`: thumbnail 16:9, poster art 2:3, сезон/серия, позиция/длительность). Менять его — в репозитории Blue Box (`docs/contracts.md`).
- Ключи и токены — только `local.properties` или env, в репозиторий не класть.
