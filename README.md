# Sidewire

VPN-клиент для Android на базе ядра **Xray**, форк [v2rayNG](https://github.com/2dust/v2rayNG) с собственным интерфейсом.

## Возможности

- Подключение по подписке (VLESS / VMess / Trojan / Shadowsocks / Hysteria2 / WireGuard и др.)
- Собственный интерфейс (Sidewire) поверх ядра Xray
- **Авто-подключение VPN** — при открытии выбранных приложений VPN включается сам, при выходе выключается
- **Авто-отключение VPN** — для другого списка приложений VPN, наоборот, выключается при открытии
- Тумблер «пауза» и удаление для каждого приложения
- Kill switch (системный Always-on VPN), шифрование DNS (DoH), блокировка рекламы через AdGuard DNS
- Скорость в уведомлении, выбор самого быстрого сервера по пингу

## Сборка

Требуется Android SDK (compileSdk 37, NDK) и JDK 17.

1. Создайте `local.properties` в корне проекта:
   ```properties
   sdk.dir=C:/Path/To/Android/Sdk
   ```
2. Соберите APK:
   ```bash
   ./gradlew :app:assemblePlaystoreDebug -PABI_FILTERS=arm64-v8a
   ```
   Готовый APK: `app/build/outputs/apk/playstore/debug/`.

## Флейворы

- `playstore` — пакет `com.sidewire.app`
- `fdroid` — пакет `com.sidewire.app.fdroid`

## Лицензия

Наследует лицензию исходного проекта v2rayNG (GPL-3.0). См. файл лицензии.
