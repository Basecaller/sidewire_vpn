# Sidewire

VPN-клиент для Android на базе ядра **Xray**, с собственным интерфейсом.

[![Скачать APK](https://img.shields.io/badge/Скачать-APK-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Basecaller/sidewire_vpn/releases/latest/download/Sidewire.apk)

> 📥 **[Скачать последнюю версию APK](https://github.com/Basecaller/sidewire_vpn/releases/latest/download/Sidewire.apk)** (Android, arm64) · [Все релизы](https://github.com/Basecaller/sidewire_vpn/releases)

## Возможности

- Подключение по подписке (VLESS / VMess / Trojan / Shadowsocks / Hysteria2 / WireGuard и др.)
- Собственный интерфейс (Sidewire) поверх ядра Xray
- **Авто-подключение VPN** — при открытии выбранных приложений VPN включается сам, при выходе выключается
- **Авто-отключение VPN** — для другого списка приложений VPN, наоборот, выключается при открытии
- Тумблер «пауза» и удаление для каждого приложения
- Kill switch (системный Always-on VPN), шифрование DNS (DoH), блокировка рекламы через AdGuard DNS
- Скорость в уведомлении, выбор самого быстрого сервера по пингу

## Флейворы

- `playstore` — пакет `com.sidewire.app`
- `fdroid` — пакет `com.sidewire.app.fdroid`

## Происхождение и лицензия

Это **форк [v2rayNG](https://github.com/2dust/v2rayNG)** (© 2dust, лицензия **GPL-3.0**).
Проект распространяется на тех же условиях — **GNU GPL-3.0** (см. файл [LICENSE](LICENSE)).

Ядро **[Xray-core](https://github.com/XTLS/Xray-core)** используется под лицензией **MPL-2.0**.

### Внесённые изменения относительно оригинала v2rayNG

- Полностью новый интерфейс (Sidewire) на WebView вместо стандартного UI
- Функции авто-подключения / авто-отключения VPN по выбранным приложениям
- Ребрендинг: имя, иконки, значки уведомлений, строки
- Правки поведения уведомлений и ориентации, оптимизация производительности

В соответствии с GPL-3.0 исходный код открыт и доступен в этом репозитории.
