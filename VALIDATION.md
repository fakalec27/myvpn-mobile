# Проверки публичного кандидата

## Публикация исходников · 14.10.2026

Исходный снимок `ac4f10ec2363c6af02155017e9e3069b80de63e1` опубликован в [myvpn-mobile](https://github.com/fakalec27/myvpn-mobile). Первый удалённый запуск проверок — [GitHub Actions](https://github.com/fakalec27/myvpn-mobile/actions/runs/38625997225). Его фактический результат доступен по ссылке; локальные результаты ниже относятся к предпубликационной приёмке и не выданы за результат этого запуска. Готовые APK и бинарный релиз не публиковались.

## Локальная приёмка до публикации

Дата: 14 октября 2026. Локальная копия, Windows, PowerShell 7. Ни телефоны, ни эмуляторы, ни реальные подписки, ни VPS не использовались.

Подтверждено:

- Source snapshot не менялся в окне экспорта: SHA до/после совпали для 3872 исходных файлов. Это не global freeze рабочей папки.
- Существующий mobile-core.patch совпал с diff authoritative core-build-transport15.
- ARM64 native core собран из включённых источников; AAR SHA совпал с соответствующим исходным AAR. ELF allocated-section hash совпал с contract APK70. Полный SHA libbox.so в APK не сравнивается с неочищенным AAR как равенство: AGP удаляет debug sections.
- `go test` с tags `with_awg,with_xhttp,with_utls,with_clash_api`: protocol/group, protocol/ssh, common/urltest, experimental/cachefile — PASS.

Android debug APK сборка — PASS, JVM tests — **182/182, 0 ошибок и пропусков**. После согласования ABI параметров повтор latest-code тоже прошёл. Первый запуск остановлен AGP из-за Unicode checkout path; после path-check override AIDL сообщил MalformedInputException. Проверка прошла через временное SUBST отображение только этой копии; оно снято. Эти два исходных лога не дают основания утверждать дефект NDK или бизнес-логики приложения.

Первый Android lint прогон дал 96 errors. Ошибки исправлены реальными переводами, observable resource/locale APIs, проверкой разрешения уведомлений, штатным proxy API и отказом от необязательной privileged integration. После изменений lint завершился **PASS, 0 errors**; предупреждения остаются и перечислены в [LINT_NOTES.md](LINT_NOTES.md). Проверка не отключена и baseline для сокрытия ошибок не создан.

Go vet выбранных четырёх пакетов — PASS; daemon package с native tags компилируется. Итоговый secret scan всей истории после включения nested источников: 75 matches — 63 полностью совпадают с pinned public upstream файлами, 2 являются синтетическими невалидными key markers в tests, 10 — известные upstream revision SHA в SPDX. Все совпадения классифицированы по точным Git blobs, необъяснённых нет. Raw gitleaks exit1 сохраняется: он не объявлен exit0. Сканирование всей рабочей директории дополнительно захватывает generated binaries/cache и lock errors; его нельзя выдавать за clean history scan.

Негативная ветка ordinary-client подтверждена compiled bytecode: getBinder() возвращает null и не вызывает hidden/system-service API; callers моделируют unavailable. Privileged provider disabled/nonexported; settings row disabled, прямые privilege routes показывают объяснение. Это offline proof границы, а не выполненная физическая проверка Android UI.

Физические проверки исходной версии не являются проверками публичного APK. В этой задаче runtime QA не проводилась. Android legacy flavor, store публикация, Linux/macOS build и x86_64 публичная сборка ещё не подтверждены. SPDX SBOM содержит source dependency graph с NOASSERTION для нерассмотренных лицензий; полный binary SBOM/лицензионный аудит требуется перед release. На этом этапе CI был подготовлен, GitHub CI ещё не запускался. Независимый clone-tree build подтверждён следующим абзацем.

На snapshot 7e216651 выполнен полный fresh-clone native/debug/JVM/lint прогон: 182 tests, 0 failures/errors/skips; lint 0 errors, 286 warnings, 6 hints. Native ARM AAR SHA повторно совпал. Затем добавлены полные pinned источники четырёх nested replacements backport module; основной core/client код не менялся. В новом самостоятельном клоне module graph основного ядра и backport module разрешается с -mod=readonly. Pins, вложенные replacements, лицензии и tracked manifest проверены отдельно; эта проверка не подменяется прежним native build proof.
