# Проверки публичного кандидата

Дата: 14 октября 2026. Локальная копия, Windows, PowerShell 7. Ни телефоны, ни эмуляторы, ни реальные подписки, ни VPS не использовались.

Подтверждено:

- Source snapshot не менялся в окне экспорта: SHA до/после совпали для 3872 исходных файлов. Это не global freeze рабочей папки.
- Существующий mobile-core.patch совпал с diff authoritative core-build-transport15.
- ARM64 native core собран из включённых источников; AAR SHA совпал с соответствующим исходным AAR. ELF allocated-section hash совпал с contract APK70. Полный SHA libbox.so в APK не сравнивается с неочищенным AAR как равенство: AGP удаляет debug sections.
- `go test` с tags `with_awg,with_xhttp,with_utls,with_clash_api`: protocol/group, protocol/ssh, common/urltest, experimental/cachefile — PASS.

Android debug APK сборка — PASS, JVM tests — **182/182, 0 ошибок и пропусков**. После согласования ABI параметров повтор latest-code тоже прошёл. Первый запуск остановлен AGP из-за Unicode checkout path; после path-check override AIDL сообщил MalformedInputException. Проверка прошла через временное SUBST отображение только этой копии; оно снято. Эти два исходных лога не дают основания утверждать дефект NDK или бизнес-логики приложения.

Дополнительная Android lint проверка — **FAIL: 96 errors, 286 warnings, 6 hints**. Ошибки: MissingTranslation52, LocalContextGetResourceValueCall38, MissingPermission3, SoonBlockedPrivateApi2, NonObservableLocale1. В путях собственного слоя myvpn error locations нет; для полной готовности исходного клиента к выпуску findings всё равно необходимо разобрать. Проверка не отключена и baseline для сокрытия ошибок не создан.

Go vet выбранных четырёх пакетов — PASS; daemon package с native tags компилируется. Source secret scan дал 53 matches: 51 полностью совпадает с pinned public upstream файлами, 2 — синтетические невалидные key markers в tests. Необъяснённых совпадений нет. Сканирование всей рабочей директории дополнительно захватывает generated binaries/cache и lock errors; его нельзя выдавать за clean history scan.

Физические проверки исходной версии не являются проверками публичного APK. В этой задаче runtime QA не проводилась. Android legacy flavor, store публикация, Linux/macOS build и x86_64 публичная сборка ещё не подтверждены. SPDX SBOM содержит source dependency graph с NOASSERTION для нерассмотренных лицензий; полный binary SBOM/лицензионный аудит требуется перед release. CI подготовлен, GitHub CI ещё не запускался. Независимый clone-tree build проверяется отдельно.
