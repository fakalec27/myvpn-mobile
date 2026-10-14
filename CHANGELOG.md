# Изменения

## Публичный кандидат на базе myVPN70/mobile15

- Включены исходники клиента, pinned native core, локальные compatibility dependencies, patch generators и native tests.
- Отдельный бренд, свои правила импорта/маршрутизации, интерфейс и постоянная диагностика службы.
- Отключён частный OTA; сборка debug не требует release keystore, рабочих профилей или подписки.
- Добавлены provenance, license notices и инструкции локальной проверки.
- Исправлены ошибки Android lint: observable Compose resources, locale updates, реальные переводы, permissions и штатный proxy API. Необязательная LSPosed интеграция отключена в публичной редакции.
- Диагностические ZIP используют owner-only permissions.

Релиз ещё не опубликован. Итерации исходной разработки не представлены как публичные releases.
