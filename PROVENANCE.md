# Происхождение исходников

Этот снимок основан на myVPN70 и ядре `1.14.3-myvpn-mobile15`. Версия 70 — номер итерации клиента, а не число опубликованных релизов. Публичная редакция меняет update/signing defaults и нейтрализует примеры; её debug APK отличается от частного APK70.

| Компонент | Upstream | Pinned revision | Лицензия |
|---|---|---|---|
| Android-клиент | SagerNet/sing-box-for-android | ae93152f21457723af77d144f28c1f7be7362aec | GPL-3.0-or-later + сохранённое условие брендинга |
| Ядро v1.14.3 | SagerNet/sing-box | 7054cac5124465ffdb145870d7c01885566711b1 | GPL-3.0-or-later |
| Источник XHTTP/VLESS/AWG backports | leadaxe/sing-box-lx | 411cc672e7700c2d6b697f937f5dad9f01b29be8 | GPL-3.0-or-later |
| uTLS compatibility fork | Leadaxe/utls-lx (module github.com/metacubex/utls) | ad69b191e2f83367ec0ac5f3a1a61310732c43b9 | лицензии Go/uTLS в module LICENSE |
| AWG device dependency | fork github.com/sagernet/wireguard-go | 8a53421edf7edd2f60dbeab3bd6e49256eab4d04 | сохранённая module LICENSE (MIT) |

Исходные copyright headers и лицензии сохраняются. Pavel Muravev отвечает за продуктовые решения форка, слой импорта/маршрутизации/диагностики, UI, интеграцию и проверку изменений. Android VPN service, значительная часть клиента, протоколы и native runtime происходят из upstream. Использование агента для реализации не меняет атрибуцию upstream.

Native transport15 дерево включено целиком в необходимой для сборки части; это не только два diff-файла базового core-source. Все local Go replacements находятся рядом в `compat`. Patch generators предназначены для повторения интеграции в scratch layout с pinned upstream Git checkouts; их revision assertions намеренно не ослаблены. Для обычной сборки клона их запускать не требуется. После replay необходимо сохранить безопасные публичные UpdateTrust/UpdateRepository и Gradle signing defaults текущего снимка.

ARM64 native библиотека, повторно собранная из этого снимка, совпала с исходной по allocated ELF sections (`3d0f6b5b72489b706dc030e1d57c4b954c5145108e558b85675d0a321e691f54`) и AAR SHA-256 (`4550a149f3fbfe6b30023c6456829eb23a0db0fd4ae9981cb389faea32987406`). Проверка учитывает удаление debug sections AGP. Этот факт относится к native библиотеке, не доказывает совпадение APK после публичных правок и не заменяет runtime QA.
