package io.nekohasekai.sfa.myvpn.importer
object ImportMessages {
    fun issue(code: String) = when(code) {
        "unsupported-protocol","unsupported-xray-protocol" -> "Протокол этого узла пока не поддерживается."
        "unsupported-transport","unsupported-tcp-header","unexpected-transport" -> "Способ подключения этого узла пока не поддерживается."
        "unsupported-vless-encryption","unsupported-security" -> "Этот вариант защиты соединения пока не поддерживается."
        "unknown-parameter","unknown-clash-parameter","duplicate-parameter" -> "В узле есть неизвестные или повторяющиеся параметры."
        "happ-encrypted" -> "Зашифрованная ссылка Happ требует отдельного декодера."
        "missing-reality-key","missing-obfs-password","missing-credentials","missing-bandwidth" -> "В узле не хватает обязательных настроек."
        "node-detour-requires-full-profile" -> "Этот узел зависит от другого узла. Импортируйте готовый профиль целиком."
        "unsupported-xray-profile-chain" -> "Профиль содержит цепочку или внутренние переходы Xray. Перенос отдельных её частей отключён."
        "unsupported-wireguard-option" -> "В профиле WireGuard/AmneziaWG есть неподдерживаемые параметры."
        "invalid-amnezia-option","invalid-wireguard-keepalive" -> "Параметры AmneziaWG или интервал keepalive имеют неверный формат."
        "unsupported-amnezia-container" -> "Протокол или связка внутри контейнера Amnezia пока не поддерживаются."
        "amnezia-client-config-missing" -> "В контейнере нет готовой клиентской конфигурации. Экспортируйте .conf, .ovpn или клиентский профиль Xray."
        "invalid-amnezia-link" -> "Контейнер Amnezia повреждён или имеет неподдерживаемый формат."
        "amnezia-admin-config" -> "Это данные для настройки сервера, а не готовое VPN-подключение."
        "tls-required" -> "Для этого протокола требуется защищённое соединение."
        "xhttp-vision-incompatible" -> "XHTTP несовместим с XTLS Vision. Проверьте параметры узла у провайдера."
        "unsupported-xhttp-option" -> "В узле XHTTP есть дополнительные параметры, которые пока не поддерживаются."
        "conflicting-xhttp-option","invalid-xhttp-option","invalid-xhttp-mode" -> "Параметры XHTTP противоречат друг другу или имеют неверный формат."
        else -> "Не удалось безопасно перенести настройки этого узла."
    }
}
