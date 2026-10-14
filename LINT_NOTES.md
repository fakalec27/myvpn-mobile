# Оставшиеся предупреждения

Ошибки первого прогона устранены без baseline и выключения checks. Полный список предупреждений сохраняется в отчёте Android lint после сборки: `client-source/app/build/reports/lint-results-otherDebug.xml`.

Сначала требуют release review:

- PrivateApi/DiscouragedPrivateApi в унаследованных Root/Shizuku и Xposed классах. LSPosed/raw binder integration в этой редакции отключена целиком; для Root/Shizuku нужно отдельно подтвердить доступность каждой операции на поддерживаемых Android версиях. Не обещается одинаковое поведение на всех устройствах.
- HardwareIds: ANDROID_ID применяется только для явно заданных пользователем параметров провайдера, имеет локальный fallback и не является общим идентификатором всех пользователей. Этот идентификатор нельзя отправлять в отчёты; перед release необходима проверка privacy notice и экрана параметров подписки.
- BatteryLife: запрос исключения из оптимизации батареи запускается пользователем. Допустимость конкретного store distribution и требуемая декларация отдельно не проверены. Измерений расхода батареи нет.
- Диагностические ZIP могут содержать операционные данные; public edition не делает архив world-readable. Они не должны публиковаться как безопасные события myVPN.
- API compatibility, feature declarations и legacy flavor требуют отдельной проверки. Основная Android24+ ARM64 debug сборка не подтверждает Android21 legacy.

Остальная инвентаризация: новые версии Gradle dependencies, unused resources, существующие private resource references, рекомендуемые KTX API, typography/RTL/modifier conventions, obsolete SDK branches и Compose performance hints. Эти предупреждения сохраняются открыто; обновление зависимостей и массовые косметические изменения не выдаются за проверенные runtime улучшения.
