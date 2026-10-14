# Уведомления зависимостей

Клиент и ядро включают работу SagerNet и других авторов. Их notices сохранены в `client-source/LICENSE`, `core-build-transport15/LICENSE` и copyright headers. Полный GPL находится в `LICENSES/GPL-3.0.txt`.

- `compat/sing-box-lx/LICENSE`: GPL-3.0-or-later; backports не заявлены как оригинальные протоколы автора MyVPN.
- `compat/utls/LICENSE`: лицензия Go; дополнительные авторы/лицензии сохраняются в исходниках uTLS.
- `compat/wireguard-go-awg/LICENSE`: лицензия этой зависимости; она не заменяет GPL клиента и ядра.
- `core-build-transport15/common/windivert/assets/LICENSE.txt`: лицензия WinDivert, вместе с Windows embed assets.
- `client-source/third_party/libxposed-api/LICENSE`: сохранённая лицензия libxposed API.
- `compat/sing-box-lx/submodules/{wireguard-go,sing-tun,gvisor,utls}/LICENSE`: точные pinned sources всех nested local replacements; gvisor сохраняет Apache-2.0, остальные компоненты сохраняют свои module licenses.
- Gradle wrapper и библиотеки, скачиваемые Gradle/Go, сохраняют собственные условия. Инвентаризация версий находится в DEPENDENCIES.json; для release SBOM дополнительно нужен список реально разрешённых Android artifacts и их license notices.

Встроенные публичные CA/test certificates и upstream cryptographic test fixtures не являются операционными ключами приложения. Ключи подписи и учётные данные пользователей в снимок не входят.
