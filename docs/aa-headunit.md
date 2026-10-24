# Android Auto: сторона головного устройства (HU) для carlife-aa

Составлено по открытым исходникам (headunit-revived, aasdk), документации Android for Cars и наблюдениям на реальном телефоне. Первая часть (разделы 1-9) написана до реализации, поэтому многие утверждения помечены **[Д]**; разделы 10-11 описывают то, что сделано в `android/`.

Обозначения. Пометка **[П]** = подтверждено (прочитано в коде/доке, дан путь:строка или URL). **[Д]** = догадка/вывод, в коде или доке прямо не сказано.

Сокращения путей:
- `HUR` = [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived), `app/src/main/java/com/andrerinas/openheadunit`
- `HUR-PROTO` = там же, `app/src/main/proto`
- `AASDK` = [opencardev/aasdk](https://github.com/opencardev/aasdk) (продолжение [f1xpl/aasdk](https://github.com/f1xpl/aasdk))

Важная оговорка про версии. Headunit-revived (README, `HUR/connection/self/SelfLauncherManager.kt:122`) делит мир на AA < 17.4 и AA >= 17.4. Приложение проверено на AA 17.x, то есть на ветке "от 17.4"; ветка "до 17.4" в коде headunit-revived называется LEGACY и идёт через интент в Gearhead, а не через сервер 5277. Но "Start head unit server" в AA существует и работает независимо от версии (DHU-доки, см. п.1.3), поэтому для нашего случая основной путь 5277. **[Д]** (что на старых версиях, например 15.2, сервер 5277 есть и ведёт себя так же: прямого подтверждения нет, подтверждено только для "16.4+" и "17.xx", см. п.9).

---

## 1. Как headunit-revived делает self mode

### 1.1 Две ветки

Выбор ветки: `HUR/connection/self/SelfLauncherManager.kt:122-137`. По версии AA (`getPackageInfo("com.google.android.projection.gearhead").versionName`, разбор major.minor, `SelfLauncherManager.kt:58-71`) **[П]**:

| AA | Ветка | Кто кого коннектит |
|---|---|---|
| >= 17.4 | `SelfLauncherV17_4`: HU-приложение само открывает TCP на `127.0.0.1:5277` (`HUR/connection/self/launchers/SelfLauncherV17_4.kt:21`) | HU = TCP-клиент, AA "head unit server" = TCP-сервер **[П]** |
| < 17.4  | `SelfLauncherLegacy` + 2 fallback: приложение поднимает TCP-сервер на порту 5288 и шлёт AA интент, чтобы Gearhead сам подключился к 127.0.0.1:5288 (`SelfLauncherManager.kt:129-137`) | HU = TCP-сервер, AA = клиент **[П]** |

Подтверждение, что 5277 доступен и на старых версиях (в Android 10 + AA "17.xx" в обсуждении, где авторы советуют именно сервер как обход блокировки интента в AA 16.4+): https://github.com/andreknieriem/open-headunit/discussions/533 **[П]** (там же: "Both methods were confirmed working").

### 1.2 Ветка 5277 (рекомендуется для carlife-aa)

- Пользователь вручную: AA -> Настройки -> 10 тапов по "Версия" -> меню "три точки" -> "Start head unit server". Появляется постоянное уведомление (foreground service AA). Источники: README headunit-revived (раздел "3. Headunit Server"), wiki https://github.com/andreknieriem/open-headunit/wiki/Troubleshooting, DHU-доки https://developer.android.com/training/cars/testing/dhu **[П]**.
- Порт по умолчанию 5277, подтверждено DHU-докой (`adb forward tcp:5277 tcp:5277`) и кодом `HUR/automation/AutomationCommandPolicy.kt:53-54` (`HEADUNIT_SERVER_PORT = 5277`) **[П]**.
- Никакого интента/компонента Gearhead для запуска проекции не требуется: приложение делает `Socket.connect(127.0.0.1:5277, timeout 5000)` (`HUR/connection/projection/SocketProjectionConnection.kt:174-177`), loopback не привязывается к сети (`LoopbackBindPolicy.kt:19-33`, `SocketProjectionConnection.kt:75`) **[П]**. Нужен только `INTERNET` (`AndroidManifest.xml:66`).
- Сервер AA не говорит на AAP сам: "это прокси, не AAP-сервер: принимает сокет, передаёт его своему car service по binder и ждёт без таймаута. Accept-цикл последовательный: пока одно соединение висит, все следующие стоят в backlog, не обслуживаются и не закрываются" (`HUR/connection/UnresponsivePeerPolicy.kt:6-11`) **[П]**. Следствия: (а) одно соединение на жизнь сервера, нельзя "пробовать и закрывать"; (б) если прошлая сессия оборвалась без чистого закрытия, сервер глохнет (`HUR/connection/TeardownGuard.kt:6`, `HUR/connection/wifi/LinkLossTeardownPolicy.kt:20`) и лечится только Stop/Start сервера руками, а иногда Force stop AA (`HUR/aap/AapTransport.kt:1041-1050`) **[П]**.
- Сервер привязан к loopback (README: "or on the same device (Self-Mode)"); также достижим по Wi-Fi IP телефона для внешних ГУ (README, wiki). **[П]**
- Запустить сервер программно из стороннего приложения headunit-revived не умеет: на ветке 5277 приложение просто показывает тост "запустите Headunit Server в Developer Settings" и открывает `com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity` (`SelfLauncherV17_4.kt:17-25`, `SelfLauncherManager.kt:73-94`) **[П]**. Автозапуск через службу спецвозможностей в нашем приложении был и убран (см. п.10), сервер включает пользователь.

### 1.3 Ветка LEGACY (AA < 17.4, нами не используется)

Шаги (`HUR/connection/self/launchers/SelfLauncherLegacy.kt:52-75`) **[П]**:
1. Поднять `WirelessServer`: `ServerSocket` на `0.0.0.0:5288`, reuseAddress, 3 попытки bind по 700 мс (`HUR/connection/wifi/server/WirelessServer.kt:100-114`). NSD-регистрация `_aawireless._tcp` в self mode не нужна (`SelfLauncherLegacy.kt:52-63`).
2. Дождаться активной сети (до 1 с) (`SelfLauncherLegacy.kt:60-64`). На ГУ без сети создаётся фиктивный VPN-интерфейс (`HUR/../github/.../DummyVpnService.kt:12-47`: адрес 10.0.0.2/24, маршрут 0.0.0.0/0), чтобы `ConnectivityManager.activeNetwork != null` **[П]**. На обычном телефоне с Wi-Fi/мобильной сетью это, скорее всего, не нужно **[Д]**.
3. Запустить активность Gearhead явным интентом (`SelfLauncherLegacy.kt:29-43`):
   - компонент: пакет `com.google.android.projection.gearhead`, класс `com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity`, флаг `FLAG_ACTIVITY_NEW_TASK`;
   - extras: `PARAM_HOST_ADDRESS="127.0.0.1"` (String), `PARAM_SERVICE_PORT=5288` (int), `PARAM_SERVICE_WIFI_NETWORK` = `android.net.Network` (активная сеть, либо фейк из `Parcel` с netId 0, `SelfLauncherServices.kt:19-32`), `wifi_info` = рефлективный `WifiInfo` с SSID `"Headunit-Fake-Wifi"` (`SelfLauncherServices.kt:35-48`).
4. Gearhead сам коннектится к 127.0.0.1:5288; `WirelessServer` отдаёт принятый сокет в `CommManager.connect(socket)` (`WirelessServer.kt:129-146`). Измерено: Gearhead тратит 15-20 с (на UNISOC), дедлайн приложения 30 с (`SelfLaunchTimeoutPolicy.kt:34`) **[П]**.
5. Fallback 1 (только API >= 30): broadcast в `com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver`, action `...wirelessstartup.START`, extras `ip_address`, `projection_port=5288`, `PARAM_SERVICE_WIFI_NETWORK`, `wifi_info` (`SelfLauncherBroadcast.kt:24-31`). Fallback 2: broadcast в `com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver`, action `com.google.android.projection.gearhead.START_WIRELESS_PROJECTION`, extra `DEVICE_ADDRESS`=MAC (`SelfLauncherBTDiscovery.kt:29-37`) **[П]**.
6. Нужно, чтобы AA был системным/обновлённым системным приложением (`FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP`), иначе приложение ругается (`HUR/connection/self/SelfLaunchResolveHelper.kt:49-57, 83-105`), см. также https://github.com/andreknieriem/open-headunit/issues/994 (CN HyperOS, AA не системный) **[П]**. На Android 10 и ниже запуск блокирован в AA >= 16.4 (README, раздел Known Issues).
7. Предварительно проверяется разрешение: `com.google.android.projection.gearhead.companion.RequestManifestPermissionsActivity` (`HUR/connection/self/AAPermissionTrampolineActivity.kt:48-52`) **[П]**.

README прямо говорит: "AA 17.4+ ломает почти все сторонние триггеры, включая Self-Mode" (README headunit-revived, блок NOTE) **[П]**. Вопрос "ломается ли LEGACY на 15.2/Android 16" отдельно: **[Д]**: на Android 16 фоновый запуск чужой активности (BAL) режется жёстче; у headunit-revived для этого висит `SYSTEM_ALERT_WINDOW` (`AndroidManifest.xml:63`) и приложение стартует из foreground service, но на Android 16 это в источниках не проверено.

### 1.4 Нужен ли developer mode и "сервер ГУ"?

- Для ветки 5277: да, обязательно (ручное включение, п.1.2) **[П]**.
- Для LEGACY: нет, developer mode не нужен (интент в `WirelessStartupActivity`) **[П]**, но ломается на >= 17.4 и на Android <= 10 при AA >= 16.4 **[П]**.

### 1.5 Ограничения Android 14+/16 (на стороне нашего приложения)

- Foreground service: headunit-revived держит `AapService` с `foregroundServiceType="connectedDevice|mediaPlayback|microphone"` (`AndroidManifest.xml:251-255`), в runtime заявляет `CONNECTED_DEVICE | MEDIA_PLAYBACK` (`HUR/app/ForegroundServiceTypePolicy.kt:28-33`), микрофон добавляется только когда реально открывается захват: с Android 14 mic это while-in-use тип, из фона старт отклоняется даже при выданном RECORD_AUDIO (`ForegroundServiceTypePolicy.kt:9-19`) **[П]**.
- Для `connectedDevice` нужно хотя бы одно из: `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`, `TRANSMIT_IR`, runtime `BLUETOOTH_*`/`UWB_RANGING`, либо `UsbManager.requestPermission()`; плюс `FOREGROUND_SERVICE` и `FOREGROUND_SERVICE_CONNECTED_DEVICE` (https://developer.android.com/develop/background-work/services/fgs/service-types) **[П]**. Для нас подходит `CHANGE_NETWORK_STATE` (normal-permission). Для `mediaPlayback` аналогично `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. С Android 15 нельзя запускать mediaPlayback/dataSync FGS из `BOOT_COMPLETED` (там же) **[П]**.
- Mic-FGS и `RECORD_AUDIO`: только если мы шлём аудио с микрофона ГУ в AA (см. п.6) **[Д]**: для первой версии "видео + тач" не нужно.
- Local Network Permission (`ACCESS_LOCAL_NETWORK`): обязательна для приложений с targetSdk 37 (Android 17) для доступа к LAN (https://developer.android.com/privacy-and-security/local-network-permission) **[П]**; что loopback 127.0.0.1 исключён, прямо не нашла **[Д]** (вероятно, нет проблем; для Android 16 при targetSdk < 37 enforcement нет).
- Разрешения, которые headunit-revived заявляет в манифесте и которые к нам относятся: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `RECORD_AUDIO`/`FOREGROUND_SERVICE_MICROPHONE` (если мик) (`AndroidManifest.xml:55-88`). Package visibility: `<queries><package android:name="com.google.android.projection.gearhead"/>` (`AndroidManifest.xml:14`) нужна для `getPackageInfo` по Gearhead (определение версии AA) **[П]**.

---

## 2. Транспорт и кадрирование AAP

### 2.1 Кадр (frame)

Исходящий и входящий формат на TCP-сокете **[П]**:

```
byte 0     channel id
byte 1     flags (битовое поле)
bytes 2-3  длина тела кадра, big-endian uint16 (без заголовка)
[bytes 4-7 только у FIRST-фрагмента НЕ-bulk сообщения: общий размер сообщения, big-endian uint32]
тело       payload (после рукопожатия: один TLS-record)
```

Источники: `HUR/aap/AapMessage.kt:106` (HEADER_SIZE=4), `HUR/aap/AapMessageIncoming.kt:11-31` (decode: chan=buf[0], flags=buf[1], enc_len = BE16 buf[2..3]), `HUR/aap/AapReadSingleMessage.kt:63-83` (для `flags==0x09` дочитывает 4 байта total), `AASDK/src/Messenger/FrameSize.cpp:35-46,73-75` (SHORT = 2 байта, EXTENDED = 6 байт: 2 frame + 4 total).

Флаги (`AASDK/include/aasdk/Messenger/FrameType.hpp`, `EncryptionType.hpp`, `MessageType.hpp`; `HUR/aap/AapMessageFraming.kt:47-67`):

| бит | значение |
|---|---|
| 0x01 | FIRST (кадр открывает сообщение) |
| 0x02 | LAST (кадр завершает сообщение) |
| 0x04 | CONTROL (тип сообщения "общий/control" на не-control канале) |
| 0x08 | ENCRYPTED (тело в TLS) |

Типичные значения: `0x03` = plain bulk (версия, SSL-хендшейк, auth); `0x0b` = encrypted bulk (обычное сообщение); `0x0f` = encrypted + control-flag (control-типы 1..26 на не-control каналах, `AapMessage.kt:112-118`); входящие фрагменты: `0x09` first, `0x08` middle, `0x0a` last (шифрованные, `AapMessageFraming.kt:9-14`) **[П]**.

Исходящие сообщения HU в наших сценариях всегда маленькие, фрагментировать не нужно **[Д]** (headunit-revived этого не делает: `AapTransport.kt:669-689` шифрует и шлёт одним кадром). Входящие видео фрагментируются по 16 КБ (`AapMessageFraming.kt:33-35`) **[П]**.

Поле "длина" у шифрованного кадра = длина TLS-record (после wrap). Для plain-кадров = `размер payload + 2` (тип), см. `Messages.createRawMessage` (`HUR/aap/protocol/messages/Messages.kt:20-32`: `[chan][flags][len=size+2 BE][type BE16][data]`) **[П]**.

Внутри тела (после расшифровки): `[type BE16][protobuf]`; для media DATA: `[type=0][timestamp u64 BE µs][данные]` (`AudioMediaPayload.kt:5-28`, видео: `VideoFragmentAssembler.kt:221-225`: offset 10 для DATA с timestamp, offset 2 для CODEC_CONFIG) **[П]**.

Сборка: один blocking read на сообщение для сокетов (`AapRead.kt:190-213`), потому что фреймы на TCP не склеиваются иначе, чем по длине; чтение `readFully` по длине (`AapReadSingleMessage.kt:99`) **[П]**.

### 2.2 Версия протокола

HU первым шлёт Version Request (`Messages.kt:8-9,18`): `00 03 00 06 00 01 00 01 00 02` (chan 0, flags 0x03, len 6, type 0x0001, major=1, minor=2) **[П]**. Подряд до 3 попыток, ответ ждёт 2 с на попытку, общий бюджет 10 с (`AapTransport.kt:958-1037`, `HANDSHAKE_TIMEOUT_MS=10_000` :1209) **[П]**.

Ответ телефона: `chan 0, flags 0x03, type 0x0002, major(2) minor(2) status(2)` + опционально `VersionResponseOptions` (ConnectionConfiguration с ping timeout и т.п.) (`HUR/aap/AapVersionNegotiation.kt:74-127`; `HUR-PROTO/control.proto:291-302`) **[П]**. Телефон выбирает минор сам; сообщения "1.6+" нельзя слать, если выбрано меньше (`AapVersionNegotiation.kt:87-88`) **[П]**. Какую именно версию выбирает AA: **[Д]** (в логах headunit-revived типично "1.7"; в коде не зафиксировано).

Если на 5277 не пришло ни байта за всё время, значит сервер "завис" на прошлой сессии (`AapTransport.kt:1039-1051`, `HandshakeFailure.PEER_SILENT`) **[П]**.

### 2.3 TLS поверх кадров

- HU = TLS-клиент (`sslEngine.useClientMode = true`, `HUR/aap/AapSslContext.kt:157-158`) через `SSLEngine` (Conscrypt если доступен, иначе системный JSSE; `createSslContext`, `AapSslContext.kt:393-413`), `createSSLEngine("android-auto", 5277)` **[П]**. Проверка сертификата телефона отключена (`NoCheckTrustManager`, `HUR/ssl/NoCheckTrustManager.kt:7-17`; `AapSslContext.kt:410`) **[П]**.
- Handshake: цикл по `getHandshakeStatus()`. NEED_WRAP: `wrap(emptyArray())` -> отправить как plain-кадр `[chan 0][flags 0x03][len][type 0x0003 ENCAPSULATED_SSL][TLS bytes]` (`AapSslContext.kt:76-83`, `createRawMessage(0, 3, 3, …)`). NEED_UNWRAP: читать plain-кадр (header 6 байт, потом payload; payloadLength = len-2), байты положить в `unwrap` (`AapSslContext.kt:123-151`). Одно сообщение AAP может содержать неполную запись, тогда копится до достаточного размера (`BUFFER_UNDERFLOW`, :62-68). Лимит хендшейка 15 с (`SSL_HANDSHAKE_TIMEOUT_MS`, :391) **[П]**.
- После хендшейка: `ssl.postHandshakeReset()` и HU шлёт plain-кадр **Auth Complete**: `00 03 00 04 00 04 08 00` = chan 0, flags 0x03, len 4, type 0x0004, protobuf `AuthResponse{status=0 (OK)}` (`Messages.kt:11-13`, `AapTransport.kt:1062-1069`) **[П]**.
- После этого все кадры идут с `ENCRYPTED(0x08)`: тело = результат `sslEngine.wrap` (один record на сообщение): `encrypt` в `AapSslContext.kt:349-369`, сборка заголовка в `AapTransport.kt:669-689` (`data[0]`, `data[1]` из исходного, длина = размер шифротекста). Приём: `sslEngine.unwrap` на каждый кадр (`AapSslContext.kt:271-347`) **[П]**. Сессия TLS переиспользуется между подключениями (JSSE session cache, `AapSslContext.kt:411-412`) **[П]**.

### 2.4 Сертификат/ключ HU

HU предъявляет клиентский сертификат: публичный сертификат "Google Automotive Link" (JVC Kenwood), отдельный от ключа, лежащий в открытом виде в репозиториях:
- headunit-revived: `app/src/main/res/raw/cert` (PEM, subject: O="Google Automotive Link", срок 2014-07-08 .. 2044-07-07) и `.../raw/privkey` (PKCS#8 RSA 2048). Загрузка: `HUR/ssl/SingleKeyKeyManager.kt:85-103` (`R.raw.cert`, `R.raw.privkey`; RSA `PKCS8EncodedKeySpec`), `KeyManager` для клиентского алиаса: `chooseClientAlias`/`chooseEngineClientAlias` (:69-76) **[П]**.
- aasdk (opencardev/aasdk): `src/Messenger/Cryptor.cpp:253-299` (встроенные PEM, "RSA PRIVATE KEY") и `cert/headunit.crt`, `cert/headunit.key`. Это другой сертификат (JVC Kenwood, serial 0x1b) vs headunit-revived (serial 0x7653); оба принимаются AA. README `cert/README.md` в aasdk: "publicly available certificate used in Android Auto head units" **[П]**.
- Можно взять любой из двух; брать из `res/raw` headunit-revived, потому что он уже проверен на Android-клиенте (JSSE/Conscrypt) **[Д]**.
- Лицензия: headunit-revived AGPL; копирование кода в проект тянет AGPL. Сертификат и ключ как данные: публичные. Решение по лицензии за вами.

---

## 3. Service Discovery

После Auth Complete телефон шлёт `ServiceDiscoveryRequest` (control, type 0x0005; поля `phone_name`=4, `phone_brand`=5), HU отвечает `ServiceDiscoveryResponse` (type 0x0006) (`HUR/aap/AapControl.kt:340-346`, `HUR-PROTO/control.proto:230-258`) **[П]**.

Идентификаторы каналов выбирает сам HU в `Service.id` (aasdk даже называет их "dynamic", `AASDK/include/aasdk/Messenger/ChannelId.hpp:5-10`); конкретная нумерация headunit-revived (`HUR/aap/protocol/Channel.kt:5-18`) работает против AA **[П]**: CONTROL=0, SENSOR=1, VIDEO=2, INPUT=3, AUDIO_SPEECH(AU1)=4, AUDIO_SYSTEM(AU2)=5, AUDIO_MEDIA(AUD)=6, MIC=7, BLUETOOTH=8, MEDIA_PLAYBACK=9, NAVIGATION=10 (нумерация aasdk другая, это не стандарт) **[П]**.

### 3.1 Корневые поля (значения headunit-revived по умолчанию)

(`HUR/aap/protocol/messages/ServiceDiscoveryResponse.kt:299-337`; дефолты `HUR/utils/Settings.kt:921-960`) **[П]**

| поле | значение |
|---|---|
| make / model | `"Google"` / `"Desktop Head Unit"` |
| year | `"2025"` |
| vehicle_id | `"headlessunit-001"` (с суффиксом `-moto` для мотоцикла, `HUR/aap/VehicleIdentityPolicy.kt:16-20`) |
| driver_position | `DRIVER_POSITION_LEFT` (0) |
| head_unit_make / model | `"Google"` / `"Desktop Head Unit"` |
| head_unit_software_build / version | `"1"` / `"0.1.0"` |
| can_play_native_media_during_vr | `false` |
| hide_projected_clock | false; session_configuration (бит 0x01 часы, 0x02 сигнал, 0x04 батарея) |
| display_name | `"Open Headunit"` |
| headunit_info (поле 17) | дублирует make/model/year/id/hu_make/hu_model/sw + `vehicle_type` (`ServiceDiscoveryResponse.kt:314-328`) |
| connection_configuration | опционально: ping timeout 15000 мс, socket read timeout 15000 мс, буферы 65536 (`HUR/aap/ConnectionConfigPolicy.kt:14-40`) |

Важно (**[П]**, `ServiceDiscoveryResponse.kt:212-223`, `VehicleTypePolicy.kt:1-34`): если HU не объявляет микрофон-сервис (`ID_MIC`), телефон обрывает setup с "No audio/mic", **кроме** случая, когда HU объявляет себя мотоциклом (`vehicle_type = 3`, `vehicle_id` + `-moto`). Для мотоцикла телефон берёт собственный микрофон. Для carlife-aa на первой итерации: либо объявить `MOTORCYCLE`, либо объявить mic-сервис (16 кГц/16 бит/моно) и отдавать реальный звук с микрофона ГУ.

### 3.2 Сервисы (минимальный рабочий набор)

Подтверждено кодом headunit-revived (`ServiceDiscoveryResponse.kt`) **[П]**:

1. SENSOR (id 1), `sensor_source_service`: `DRIVING_STATUS` (13) обязателен; `NIGHT` (10) (headunit-revived объявляет всегда, :44); `LOCATION` (1) только при включённом GPS (:39). `makeSensorType` :457.
2. VIDEO (id 2), `media_sink_service` (:52-137):
   - `available_type = MEDIA_CODEC_VIDEO_H264_BP` (3), `audio_type = NONE (0)`, `available_while_in_call = true`;
   - `video_configs[0]`: `codec_resolution = _1280x720 (2)`, `frame_rate = _60 (1)` или `_30 (2)`, `density` (DPI), `margin_width`, `margin_height`, `pixel_aspect_ratio_e4` (10000 = квадратные пиксели), `video_codec_type = H264_BP (3)`.
   - Разрешения (`control.proto:82-93`): 1=800x480, 2=1280x720, 3=1920x1080, 4=2560x1440, 5=3840x2160, 6=720x1280, 7=1080x1920, 8=1440x2560, 9=2160x3840. Кадр = **1280x720** для "720p". DHU-док подтверждает 480p/720p/1080p как поддерживаемые (https://developer.android.com/training/cars/testing/dhu) **[П]**.
   - margins: на размер экрана ГУ не влияют, если соотношение сторон кадра совпадает с экраном. Нулевые margin = AA рисует во весь кадр. В headunit-revived вычисляются в `HUR/utils/HeadUnitScreenConfig.kt:620-631` и `ProjectionGeometryPolicy.kt:49-57` (`margin = max(0, (adjusted_video - panel)/scale)`; в режиме PAR margin=0) **[П]**. Для 16:9 ГУ (или кадра 1280x720 без letterbox) используйте margin=0/0, density 160..240 **[Д]** (значение density, при котором интерфейс выглядит нормально, подбирать на месте; headunit-revived берёт реальный `densityDpi` экрана, `HeadUnitScreenConfig.kt:644-650`; README: "Google Maps portrait: DPI < 200").
3. INPUT (id 3), `input_source_service` (:141-161): `touchscreen = TouchConfig{width=1280, height=720}` (= размер кадра, не экрана), `keycodes_supported` (список из `HUR/input/KeyCode`), `touchpad` только если нужен rotary. Размер touch = negotiated width/height кадра (`ServiceDiscoveryResponse.kt:144-147`) **[П]**.
4. AUDIO (`media_sink_service`, `available_type = MEDIA_CODEC_AUDIO_PCM (1)`, `audio_type`, `audio_configs`) (:163-211; конфиги `HUR/aap/protocol/AudioConfigs.kt:49-72`) **[П]**:

   | канал | id | `audio_type` | формат |
   |---|---|---|---|
   | SYSTEM (системные звуки) | 5 | `SYSTEM (2)` | 16000 Гц, 16 бит, 1 канал |
   | SPEECH (guidance/ассистент) | 4 | `SPEECH (1)` | 16000 Гц, 16 бит, 1 канал |
   | MEDIA | 6 | `MEDIA (3)` | 48000 Гц, 16 бит, 2 канала |

   - Канал SYSTEM объявлен всегда ("keep connection alive", `ServiceDiscoveryResponse.kt:164`); конфиг 16 kHz mono "required": иначе у телефона пустой endpoint-лист и "No audio/mic" (`AudioConfigs.kt:64-66`) **[П]**.
   - **Нюанс self mode:** при loopback-сессии (`commManager.isLoopbackSession`) headunit-revived **не объявляет** SPEECH и MEDIA, только SYSTEM (`ServiceDiscoveryResponse.kt:179-211`, `AudioSinkAnnouncementPolicy.kt:90-91`), причина в репо не записана ("Why Self Mode drops them at all is not recorded ... and is not settled") **[П]**; в обсуждении #533 пользователь подтверждает: звук идёт с самого телефона при выключенном audio sink. **Для carlife-aa это критично**: звук AA, видимо, придётся забирать иначе или проверять, будет ли AA вообще слать media/speech на loopback при объявленных сервисах. **[Д]**
   - Валидатор телефона принимает аудио-конфиги только из набора {16000, 48000} Гц, 16 бит, 1 или 2 канала (комментарий `ServiceDiscoveryResponse.kt:218-222`) **[П]**.
5. MIC (id 7), `media_source_service{type=PCM, audio_config{16000,16,1}}` (:224-236) только если HU реально записывает; иначе мотоцикл (см. выше) **[П]**.
6. Bluetooth (id 8): объявляется только при заданном MAC (`ServiceDiscoveryResponse.kt:246-260`); без него AA оставляет звонки на телефоне. Для минимума **не нужен** **[П]** (комментарий :261-267).
7. MEDIA_PLAYBACK (id 9) и NAVIGATION (id 10) (`navigation_status_service{min_interval 1000, ImageCodesOnly}`) headunit-revived объявляет всегда (:270-284). Для минимальной сессии "видео+тач" **[Д]**: не обязательны; не проверено, что AA стартует без них.
8. Что отсутствует у headunit-revived и не нужно: radio, phone_status, vendor_extension, generic_notification, wifi_projection.

---

## 4. Видео

Поток (всё через канал VIDEO=2) **[П]**:

1. Phone -> HU: `ChannelOpenRequest` (control type 0x0007, `priority`, `service_id`) на нужный канал. HU отвечает `ChannelOpenResponse{status=STATUS_SUCCESS}` (type 0x0008) на **тот же** канал; на сенсорном канале дополнительно сразу шлёт `DrivingStatus UNRESTRICTED` (`HUR/aap/AapControl.kt:477-502`) **[П]**.
2. Phone -> HU: `MediaSetupRequest` (type 0x8000, поле `type`=3=H264) (`media.proto` `MediaSetupRequest`, `AapControl.kt:39-41`). HU отвечает `Config{status=STATUS_READY(2), max_unacked, configuration_indices=[0]}` type 0x8003 (`AapControl.kt:103-116`) **[П]**. `max_unacked` у headunit-revived для видео: 12 по Wi-Fi/сокету, 16 по USB (6/8 для софт-HEVC) (`AapControl.kt:136-156`), для аудио 30 (сокет) **[П]**.
3. HU -> Phone: `VideoFocusNotification{mode=VIDEO_FOCUS_PROJECTED(1), unsolicited}` type 0x8008 (`HUR/aap/protocol/messages/VideoFocusEvent.kt:10-20`). headunit-revived шлёт его, когда готов Surface/активность (`gainVideoFocus` после Config: `AapControl.kt:118-120`; `AapProjectionActivity.kt:175,289,345,437,2043-2046`), `unsolicited=true` при повторных/старте, `false` при ответе на запрос **[П]**. Без этого сообщения AA, судя по логике, видео не стартует **[Д]** (прямой тест не делался).
4. Phone -> HU: `Start{session_id, configuration_index=0}` (type 0x8001) (`AapControl.kt:94-101`, `media.proto Start`). HU запоминает `session_id` на канал (`AapTransport.setSessionId` :1161) и использует его во всех Ack **[П]**.
5. Phone -> HU: `CODEC_CONFIG` (type 0x0001): `[type 2B][SPS/PPS Annex-B]`, затем `DATA` (type 0x0000): `[type 2B][timestamp u64 BE µs][Annex-B access unit]`. Старт-коды `00 00 01`/`00 00 00 01` (`HUR/aap/AapVideo.kt:219-232`). Смещение полезной нагрузки 10 (DATA с timestamp) или 2 (CODEC_CONFIG/без timestamp) (`HUR/decoder/video/VideoFragmentAssembler.kt:221-225`). Это уже готовый Annex-B, его можно подавать в MediaCodec/в CarLife-энкодер-канал без конвертации **[П]** (если CarLife-сторона ждёт Annex-B с SPS/PPS в IDR; иначе добавить SPS/PPS перед ключевыми кадрами **[Д]**).
6. Фрагментация: DATA > ~16 КБ приходит как FIRST(0x09, +4 байта total size) + MIDDLE(0x08)* + LAST(0x0a); сборка по `flags` (`AapVideo.kt:282-358`, `VideoFragmentAssembler.kt:210-219`: FLAG_SINGLE=11, FIRST=9, MIDDLE=8, LAST=10). Максимум сборочного буфера в headunit-revived: 131080*64 байт (`AapVideo.kt:20`) **[П]**.
7. Ack: на каждое принятое видео-сообщение (DATA и CODEC_CONFIG; по реализации ack уходит по сообщениям, у которых `type` в начале = 0 или 1, то есть фактически по первому фрагменту или целому сообщению, `AapTransport.kt:798-852`) HU шлёт `Media.Ack{session_id, ack=1}` type 0x8004 на канал VIDEO (`HUR/aap/protocol/messages/MediaAck.kt:7-18`, `AapTransport.kt:1157-1159`). Ack уходит **после** обработки (чтобы окно управляло скоростью), а не при получении (`AapTransport.kt:770-795` комментарий) **[П]**. Если не слать ack, окно `max_unacked` закроется и картинка встанет **[П]**.
8. Keyframe по запросу: специального сообщения "request IDR" в коде нет; headunit-revived переотправляет `VideoFocusNotification` (focus cycle: сначала NATIVE gain=false, затем PROJECTED) (`AapTransport.kt:379-441`, `AapVideo.kt:97-109`) **[П]**. Для нас: если CarLife-декодер потерял кадр, делать то же самое **[Д]** (поведение AA 15.2 не проверено).
9. Phone -> HU `VideoFocusRequestNotification{mode, reason}` (type 0x8007): `VIDEO_FOCUS_NATIVE` значит "пользователь нажал Exit/Home в AA", нужно показать нативный UI (`AapControl.kt:48-73`) **[П]**. Ответ на нативный запрос обязателен для корректного выхода: при `NATIVE` HU должен вернуть нативный экран/закрыть сессию **[Д]**.
10. `Stop` (0x8002) на видео-канале: фон/переход; headunit-revived игнорирует его при собственном focus-cycle (`AapControl.kt:158-178`) **[П]**.

---

## 5. Тач

Канал INPUT (id 3) **[П]**:

1. После ChannelOpen телефон шлёт `KeyBindingRequest` (type 0x8002, список `keycodes`), HU отвечает `BindingResponse{status=STATUS_SUCCESS}` type 0x8003 (`AapControl.kt:216-237`, `input.proto`) **[П]**. Без этого ввод не заработает **[Д]**.
2. События: type `0x8001` (`Input.MsgType.EVENT`), proto `InputReport` (`HUR-PROTO/input.proto:28-78`):
   - `timestamp` (uint64, **наносекунды**): headunit-revived берёт `SystemClock.elapsedRealtime()` (мс) * 1_000_000 (`HUR/aap/protocol/messages/TouchEvent.kt:48`);
   - `touch_event` (поле 3) = `TouchEvent{ repeated Pointer pointer_data (1), uint32 action_index (2), PointerAction action (3) }`;
   - `Pointer{ x=1, y=2, pointer_id=3 }` (uint32);
   - `PointerAction`: DOWN=0, UP=1, MOVE=2, CANCEL=3, OUTSIDE=4, POINTER_DOWN=5, POINTER_UP=6 (`input.proto:30-38`). Сопоставление с Android: `MotionEvent.ACTION_*` напрямую (`TouchEvent.kt:19-30`).
3. Координаты: пиксели **кадра**, объявленного в TouchConfig (то есть 1280x720 для 720p), от 0 до width/height включительно; при `FILL` и нулевых margin: `x = rawX / surfaceW * videoW`, `y = rawY / surfaceH * videoH`, clamp `[0, videoW]`, `[0, videoH]` (`HUR/input/TouchCoordinateMapper.kt:24-72`; вызов `AapProjectionActivity.kt:2141-2199`). В режиме margin вычитается `margin` из ширины (uiW = videoW - marginW) **[П]**.
4. Мультитач: в одном сообщении все активные указатели; `action_index` = индекс указателя, вызвавшего POINTER_DOWN/UP (`AapProjectionActivity.kt:2199`) **[П]**. `pointer_id` берётся из `MotionEvent.getPointerId`.
5. Для CarLife: у CarLife свой тач-протокол (в [CarProjection](https://github.com/aa112901/CarProjection) и [apollo-DuerOS](https://github.com/milesking/apollo-DuerOS)); задача шлюза: брать координаты в системе экрана ГУ, нормировать на 1280x720 и слать DOWN/MOVE/UP в той же последовательности. Один палец уходит с pointer id=0; второй палец CarLife (`action>>8`, pointerx/pointery, см. `carlife-protocol.md` §6.1.1) мост переводит в POINTER_DOWN/POINTER_UP со всеми активными указателями, id оставшегося пальца не меняется (`AaBridge.onCarlifeTouch`) **[Д]**.

---

## 6. Аудио

(`HUR/aap/AapAudio.kt`, `HUR/aap/AudioMediaPayload.kt`, `AapControl.kt`) **[П]**

- Каждый объявленный аудио-сервис (каналы 4, 5, 6) проходит ту же цепочку, что видео: ChannelOpen -> `MediaSetupRequest(type=PCM/AAC)` -> HU `Config{READY, max_unacked(30 по сокету), [0]}` -> телефон `Start{session_id, config_index}` -> поток `DATA` (`[type 0][ts u64 µs][PCM]`) + `Ack` на каждое `DATA` (на `CODEC_CONFIG` ack **не** шлётся, только для AAC; `AudioMediaPayload.kt:14-27`).
- Ack: `Media.Ack{session_id (из Start этого канала), ack=1}` на тот канал (`AapTransport.kt:1157-1159`; вызов для аудио `AapMessageHandlerType.kt:67-78`).
- Формат PCM: см. п.3.2: система/речь 16 кГц/16/mono, медиа 48 кГц/16/stereo (little-endian signed, как отдаёт AA: `AudioTrack` ENCODING_PCM_16BIT в `AudioTrackWrapper`; **[Д]** про порядок байт, но в коде декодер PCM без пересборки).
- Объявлять AAC необязательно (`Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC`, на 2.4 ГГц headunit-revived переключается, `NarrowBandProfilePolicy`); для loopback PCM.
- Audio focus: телефон шлёт `AudioFocusRequestNotification{request: GAIN=1, GAIN_TRANSIENT=2, GAIN_TRANSIENT_MAY_DUCK=3, RELEASE=4}` (control type 0x0012), HU отвечает сразу `AudioFocusNotification{focus_state}` (type 0x0013): GAIN->`STATE_GAIN(1)`, GAIN_TRANSIENT->`STATE_GAIN_TRANSIENT(2)`, MAY_DUCK->`STATE_GAIN_TRANSIENT_GUIDANCE_ONLY(7)`, RELEASE->`STATE_LOSS(3)` ("always-grant") (`AapControl.kt:395-436, 450-455`). После `MediaSetupRequest` на аудио-канале HU сам шлёт `AudioFocusNotification{STATE_GAIN, unsolicited=true}` (`AapControl.kt:123-131`) **[П]**.
- Навигационный фокус: `NavFocusRequestNotification` -> ответ `NavFocusNotification{NAV_FOCUS_PROJECTED (2)}` (`AapControl.kt:358-370`) **[П]**.
- Микрофон (если объявлен): телефон шлёт `MicrophoneRequest{open, max_unacked}` (0x8005) на канал MIC; HU отвечает `MicrophoneResponse{status, session_id}` (0x8006) и шлёт `DATA` с PCM 16 кГц/16/mono, телефон шлёт `Ack` (`AapControl.kt:188-206`, `MicrophoneResponse.kt`) **[П]**.
- Для минимума "видео+тач": допустимо объявить только SYSTEM-sink (как делает headunit-revived в loopback), принять его Config/Start и просто сливать/отбрасывать данные, **не забывая ack на каждое DATA**, иначе у телефона закроется окно **[Д]**.

---

## 7. Ping, keepalive, shutdown

**[П]**
- Пинг инициирует **телефон**: control type 0x000b `PingRequest{timestamp, bug_report?}`, примерно раз в секунду на всю жизнь сессии (`AapTransport.kt:214-216` комментарий). HU отвечает `PingResponse{timestamp}` (type 0x000c) на тот же канал (`AapControl.kt:348-356`: `timestamp = System.nanoTime()`) **[П]**.
- HU свой Ping не шлёт (в коде нет) **[П]**. Таймауты: телефон может прислать желаемый ping timeout в `VersionResponseOptions` (`AapTransport.kt:1016-1020`); HU может предложить свои в `ServiceDiscoveryResponse.connection_configuration` (15 с по умолчанию в headunit-revived) **[П]**.
- TCP: `keepAlive=true`, `tcpNoDelay=true`, soTimeout чтения 15 с на ожидание следующего заголовка; при таймауте сессия считается потерянной (`AapReadSingleMessage.kt:28-30,41-44`; `SocketProjectionConnection.kt:273-293`) **[П]**. Это значит, что ping телефона обязательно должен приходить чаще 15 с (он приходит ~1 с).
- Shutdown HU -> телефон: `ByeByeRequest{reason=USER_SELECTION(1)}` (control 0x000f), пауза 150 мс, закрыть сокет (`AapTransport.kt:698-709`) **[П]**. Закрывать сокет обязательно: иначе сервер 5277 остаётся "занятым" (`TeardownGuard.kt:6`) **[П]**.
- Shutdown телефон -> HU: `ByeByeRequest` (reason) от телефона: HU отвечает `ByeByeResponse` (0x0010), ждёт 500 мс и закрывает (`AapControl.kt:372-382`) **[П]**. Если пришёл `ByeByeResponse` (HU послал первым), сессия заканчивается (`AapControl.kt:318-320`).
- "Magic garbage": 4+ байта 0xFF вместо заголовка = чистый выход от Wireless Helper (специфика headunit-revived, нам не нужно) (`AapReadSingleMessage.kt:56-61`).
- Ошибки кадрирования: control type 0xFFFF `FRAMING_ERROR`, `0x00FF UNEXPECTED_MESSAGE` (`control.proto:37-38`) **[П]**.

---

## 8. Минимальная последовательность для "видео 720p + тач"

Каждая стрелка = один AAP-кадр. "pl" = plain (flags 0x03), "enc" = flags 0x0b (0x0f для control-типов на не-control канале). Номера каналов: headunit-revived.

Предусловия: на телефоне включён developer mode и "Start head unit server" (AA 17.x). Приложение с foreground service и разрешениями из п.1.5.

0. TCP connect `127.0.0.1:5277` (timeout 5 с). Не открывать тестовое соединение и не закрывать его без хендшейка.
1. HU -> AA (pl, ch 0): `VERSION_REQUEST` (1): `00 03 00 06 00 01 00 01 00 02`.
2. AA -> HU (pl, ch 0): `VERSION_RESPONSE` (2): major, minor, status (+options). Проверить `status == 0`.
3. TLS-хендшейк: HU `wrap()` -> `ENCAPSULATED_SSL` (3) (pl), AA -> `ENCAPSULATED_SSL`, и так до `FINISHED` (клиентский сертификат из п.2.4, доверие любому).
4. HU -> AA (pl, ch 0): `AUTH_COMPLETE` (4): `00 03 00 04 00 04 08 00`.
5. AA -> HU (enc, ch 0): `SERVICE_DISCOVERY_REQUEST` (5).
6. HU -> AA (enc, ch 0): `SERVICE_DISCOVERY_RESPONSE` (6) с сервисами: SENSOR(1: DRIVING_STATUS, NIGHT), VIDEO(2: H264_BP, 1280x720, fps 30 или 60, margins 0, density N), INPUT(3: touchscreen 1280x720, keycodes), AUDIO SYSTEM(5, PCM 16k/16/1), (+ при желании SPEECH(4)/MEDIA(6)); vehicle_type MOTORCYCLE либо MIC(7).
7. Для каждого сервиса AA шлёт `CHANNEL_OPEN_REQUEST` (7) на соответствующий канал; HU отвечает `CHANNEL_OPEN_RESPONSE` (8, STATUS_SUCCESS). На канале 1 сразу после ответа HU шлёт `SENSOR_EVENT` (0x8003) с `DrivingStatus=UNRESTRICTED(0)`.
8. AA -> HU, ch 1: `SENSOR_START_REQUEST` (0x8001, тип сенсора) на каждый объявленный сенсор; HU: `SENSOR_START_RESPONSE` (0x8002, STATUS_SUCCESS); для NIGHT дополнительно `SENSOR_EVENT{night=false}`.
9. AA -> HU, ch 3: `KEY_BINDING_REQUEST` (0x8002); HU: `BINDING_RESPONSE` (0x8003, STATUS_SUCCESS).
10. AA -> HU, ch 2: `MEDIA_SETUP` (0x8000, type=H264). HU -> AA: `MEDIA_CONFIG` (0x8003: READY, max_unacked=12, indices=[0]).
11. HU -> AA, ch 2: `VIDEO_FOCUS_NOTIFICATION` (0x8008: PROJECTED, unsolicited=true).
12. AA -> HU, ch 2: `MEDIA_START` (0x8001: session_id, config_index 0). Запомнить session_id.
13. AA -> HU, ch 2: `CODEC_CONFIG` (SPS/PPS) и поток `DATA` (Annex-B) с фрагментацией; HU собирает по flags 0x0b / 0x09,0x08*,0x0a. После обработки каждого сообщения HU шлёт `MEDIA_ACK` (0x8004: session_id, ack=1) на ch 2.
14. Аудио (если объявлено): те же шаги 10 и 12 на каналах 4/5/6, + `AUDIO_FOCUS_NOTIFICATION` (STATE_GAIN, unsolicited) после Setup; Ack на каждый DATA.
15. AA -> HU, ch 0: `PING_REQUEST` (~1/с) -> HU `PING_RESPONSE`. `NAV_FOCUS_REQUEST` -> `NAV_FOCUS_NOTIFICATION(PROJECTED)`. `AUDIO_FOCUS_REQUEST` -> `AUDIO_FOCUS_NOTIFICATION`. `VOICE_SESSION_NOTIFICATION` можно игнорировать.
16. Тач: HU -> AA, ch 3: `INPUT_REPORT` (0x8001) с `TouchEvent` (DOWN/MOVE/UP, координаты в пикселях 1280x720).
17. Выход: HU -> AA `BYEBYE_REQUEST` (15), 150 мс, закрыть сокет. AA -> HU `BYEBYE_REQUEST` -> ответить `BYEBYE_RESPONSE` и закрыть.

Что **не** подтверждено как обязательное (проверить опытом): MEDIA_PLAYBACK(9) и NAVIGATION(10) сервисы; NIGHT-сенсор; наличие SPEECH/MEDIA аудио в loopback **[Д]**.

---

## 9. Риски, версии, альтернативы

### 9.1 Статус self mode / сервера ГУ в 2025-2026

- README headunit-revived: на AA >= 17.4 ломаются почти все сторонние триггеры (Wireless Helper, интентный self mode); "Headunit Server" называется "единственным оставшимся решением для Self-Mode" (README headunit-revived, NOTE; wiki https://github.com/andreknieriem/open-headunit/wiki/Wireless) **[П]**.
- Для AA 16.4+ на Android <= 10 запуск интентом отключён Google; обход: Headunit Server на 127.0.0.1, подтверждено рабочим "on Android 10 with Android Auto 17.xx" (https://github.com/andreknieriem/open-headunit/discussions/533) **[П]**.
- Открытые/недавние жалобы (сентябрь 2026): #1022 "Self mode not responding on latest android auto" (требование "открыть и закрыть headunit server в dev settings", повторяется; закрыт без разбора) https://github.com/andreknieriem/open-headunit/issues/1022; #977 "refuses to work in Self mode" (доходит до Welcome-экрана и выбрасывает назад; закрыт "Done/Testing") https://github.com/andreknieriem/open-headunit/issues/977; #698 "App is not working after android auto 17.4 release" (wontfix, head unit server тоже не помог) https://github.com/andreknieriem/open-headunit/issues/698; #994 AA не системное приложение на CN HyperOS https://github.com/andreknieriem/open-headunit/issues/994; #929 AA считает HU активным медиа-приложением в self mode https://github.com/andreknieriem/open-headunit/issues/929 **[П]** (пересказ страниц, детали комментариев через WebFetch неполные).
- Прямых отчётов именно про Android 16 + сервер 5277 в найденном нет **[П]** (отсутствие данных, не подтверждение работоспособности). Наше приложение использует ветку 5277 и проверено на AA 17.x.
- Известная хрупкость сервера: принимает ровно одно соединение, зависает при невежливо оборванном (`UnresponsivePeerPolicy.kt`, `TeardownGuard.kt`); восстановление: Stop/Start сервера, при необходимости Force stop AA и перезагрузка телефона (`AapTransport.kt:1043-1050`) **[П]**. Это нужно встроить в UX (подсказка пользователю, backoff по 10/60/300 с).
- Запуск сервера только руками (см. 1.2) **[П]**; автоматизация (ADB wireless debugging, accessibility) не исследована **[Д]**.
- Google может в любой момент закрыть сервер/сменить кадрирование: подпись "snapshot_version" в `VersionRequestOptions` (`control.proto:291-294`) и `FramingError` намекают на эволюцию протокола; нашему HU стоит игнорировать неизвестные поля и сообщения **[Д]**.
- Видео в self mode: AA на том же телефоне будет рендерить кадр 720p параллельно основному UI телефона; нагрузка на SoC (энкодер AA + наш декодер/ретранслятор CarLife) **[Д]**: критична на слабых телефонах; keyframe-цикл `VideoFocus` может давать мерцание.
- Звук: в loopback headunit-revived не объявляет MEDIA/SPEECH (причина не установлена), в #533 звук играет с телефона при выключенном sink **[П]**. Значит, для вывода на ГУ в CarLife потребуется эксперимент: объявить media-sink и проверить, пошёл ли поток **[Д]**; альтернатива: захватывать системный звук телефона отдельно (`MediaProjection`/`AudioPlaybackCapture`) **[Д]**.

### 9.2 Чего нет в коде или не подтверждено

- Поведение AA < 16.4 как "server": нет данных; подтверждены лишь 16.4/17.x. **[Д]**
- Нужность sensors NIGHT / MPB / NAV для старта: не проверялось. **[Д]**
- Точное требование к timestamp-полю input: используется наносекундный (мс*1e6), без проверки AA. **[П]** для формулы, **[Д]** для критичности.
- Лицензия: headunit-revived AGPL (код копировать нельзя без лицензирования проекта AGPL), aasdk GPLv3 (проверить `LICENSE` в репозитории).
- Фактическая версия протокола, выбранная AA (1.6/1.7?): в документе не зафиксирована. **[Д]**

### 9.3 Альтернативы

1. **DHU-стиль без Android-HU**: AA на телефоне -> adb forward 5277 -> ПК (DHU) (https://developer.android.com/training/cars/testing/dhu). К нашему делу не относится (нужен телефон, работающий и как HU, и как проекция в ГУ) **[П]**.
2. **Wireless AA (Wi-Fi Direct/hotspot, Native mode)**: HU-приложение выступает как настоящий wireless-HU (BT-handshake + Wi-Fi) (`HUR/connection/wifi/modes/nativeaa/`, README "2. Native Mode"). Для одного телефона не подходит (телефон не может быть HU сам для себя по BT/Wi-Fi) **[Д]**.
3. **Wireless Helper + интент (LEGACY)**: работает на AA <= 17.3 (README), но требует AA как системное приложение и режется BAL на Android 16 **[П]** (README), **[Д]** (Android 16).
4. **MediaProjection-зеркало экрана телефона** вместо AA-протокола: полностью обходит AA, но это не Android Auto UI **[Д]**.
5. **USB-режим AOA** (ПК/второй телефон как HU): не подходит для схемы "один телефон" **[Д]**.

---

## Приложение: быстрые ссылки на файлы

- Сеть/рукопожатие: `HUR/aap/AapTransport.kt:933-1084` (handshake), `HUR/aap/AapSslContext.kt:24-173`, `HUR/aap/protocol/messages/Messages.kt`.
- Приём: `HUR/aap/AapReadSingleMessage.kt`, `HUR/aap/AapMessageIncoming.kt`, `HUR/aap/AapMessageHandlerType.kt`.
- Control/Media/Input/Sensor обработчики: `HUR/aap/AapControl.kt`.
- Service discovery: `HUR/aap/protocol/messages/ServiceDiscoveryResponse.kt`, `HUR/aap/protocol/AudioConfigs.kt`.
- Видео: `HUR/aap/AapVideo.kt`, `HUR/decoder/video/VideoFragmentAssembler.kt`, `HUR/aap/protocol/messages/VideoFocusEvent.kt`, `MediaAck.kt`.
- Тач: `HUR/aap/protocol/messages/TouchEvent.kt`, `HUR/input/TouchCoordinateMapper.kt`, `HUR/aap/AapProjectionActivity.kt:2141-2199`.
- Proto-схемы (подходят для генерации): `HUR-PROTO/{control,media,input,sensors,common}.proto`; альтернативная раскладка в `AASDK/protobuf/aap_protobuf/`.
- Self mode: `HUR/connection/self/*`, `HUR/connection/wifi/server/WirelessServer.kt`, `HUR/connection/UnresponsivePeerPolicy.kt`.
- Сертификат: headunit-revived `app/src/main/res/raw/{cert,privkey}`, aasdk `cert/headunit.{crt,key}`.

---

## 10. Автозапуск сервера ГУ (2026-10-07, убран 2026-10-24)

Служба спецвозможностей (`autosrv/`, AccessibilityService) нажимала пункты меню AA сама. Убрана: сервер ГУ теперь включает пользователь (⋮ -> «Вкл. сервер головного устр-ва»), приложение лишь открывает настройки AA. Заодно приложение само не закрывает живую AA-сессию (смена видео-режима и DPI применяется после перезапуска сервера): сервер одноразовый, перезапустить его некому. Ниже описание того, как служба работала, и названия пунктов меню. Названия пунктов меню в AA 17.7 (ресурсы `menu_action_developer_head_unit_network_server[_stop]`) **[П]**:

| | en | ru |
|---|---|---|
| пункт «запустить» | `Start head unit server` | `Вкл. сервер головного устр-ва` (именно так, без слова «Запустить») |
| пункт «остановить» | `Stop head unit server` | `Остановить сервер головного устройства` |
| кнопка меню ⋮ (contentDescription) | | `Ещё` (видно в `uiautomator dump`) |

Настройки AA в 17.7 открываются как `Material3SettingsActivity` (редирект с `DefaultSettingsActivity`), у тулбара `ImageView desc='Ещё' clickable=true` **[П]**.

Сценарий службы (`ServerAutomation`, удалена; проверен JVM-тестами на поддельном экране): экран включён и без keyguard -> `startActivity(DefaultSettingsActivity, NEW_TASK|CLEAR_TASK)` -> «Ещё» -> если есть «Остановить»: нажать, подождать, снова «Ещё», дождаться «Запустить» (до 5 попыток), нажать -> проверка (меню снова, там должен быть «Остановить») -> возврат в приложение (`MainActivity` с `cmd=front`). Триггеры (`Trigger`): кнопка / `--es cmd aa_server_restart [--ei delay_s N]`, USB_ATTACH (до первой попытки `AaConnector`, ждём до 8 с, потом коннектор как обычно), AFTER_SESSION (AA-сессия кончилась, USB жив, ждём до 12 с), RETRY (прошлая попытка упёрлась в блокировку, пока сервера нет). Заблокированный телефон: на ГУ заглушка «Разблокируйте телефон».

Включить службу: Настройки -> Спецвозможности. На этом телефоне (HyperOS, Android 16) `adb shell settings put secure enabled_accessibility_services ...` и `pm grant ... WRITE_SECURE_SETTINGS` дают `SecurityException: must have WRITE_SECURE_SETTINGS` (нужен тумблер «USB-отладка (настройки безопасности)») **[П]**; включать руками. Для sideload-приложения пункт может быть серым: Настройки -> Приложения -> Headswap -> ⋮ -> «Разрешить ограниченные настройки» **[Д]**.

## 11. Микрофон машины для AA (2026-10-07)

AA-сторона (`AapSession`, `AaMicUplink`; формат сверен с headunit-revived `MicSessionController`, `MicFlowControl`, `MicUplinkFrame`, `MicCaptureFormat`) **[П]**:
- `MicrophoneRequest{open=1, anc=2, ec=3, max_unacked=4}` (0x8005) на канале MIC=7. Open: ответ `MicrophoneResponse{status=0, session_id}` (0x8006), session_id с 1; отказ: status -7, session_id 0 (как раньше). Close: ответ успехом с прежним session_id.
- DATA: канал 7, type `0x0000`, тело `[timestamp u64 BE мкс][PCM16 LE mono 16 кГц]`, кадр шифрованный bulk (flags 0x0b). Кадры по 2048 сэмплов = 4096 Б = 128 мс; хвост при закрытии отбрасывается, нулями не дополняется. timestamp = момент первого байта кадра.
- Окно: не больше `max_unacked` неподтверждённых кадров; `Ack{session_id=1, ack=2, receive_timestamp_ns=3}` на канале 7 (счёт = число `receive_timestamp_ns`, если есть, иначе `ack`). Сверх окна в очереди до 4 кадров, дальше сессия прерывается (`MicrophoneResponse` с ошибкой). До ответа на Open данные копятся.

CarLife-сторона (протокол CarLife 2.0, наблюдения на ГУ) **[П]**:
- старт записи у ГУ: CMD `MIC_RECORD_RECOG_START` 0x00010024 (пустое тело), затем `MODULE_STATUS{cnt=1, VR(4)=1}`; стоп: `MIC_RECORD_END` 0x00010023 и `MODULE_STATUS VR=0`. `MIC_RECORD_WAKEUP_START` 0x00010022 штатное приложение шлёт при постоянном прослушивании wake-слова, мы не используем.
- данные HU->MD: канал VR=5, type `0x00058001` (`CommonParams.MSG_VR_DATA = 360449`), заголовок 12 байт `[len][time][type]`, тело PCM16 mono 16 кГц, шифрование выключено. Штатное приложение CarLife выбрасывает первые 20 пакетов после старта записи; у нас по умолчанию 0 (`AaMicRouter.HU_SKIP_PACKETS`), пока не известен размер пакета ГУ Honda.
- ГУ Honda в FEATURE_CONFIG ключа `VOICE_MIC` не присылает (4 ключа: BLUETOOTH_AUTO_PAIR, BLUETOOTH_INTERNAL_UI, FOCUS_UI, VOICE_WAKEUP, по логам поездки 2026-10-07) -> статус MIC=0 (микрофон ГУ).

Маршрутизация (`AaMicRouter`, режим в UI «Микрофон: машина / телефон / авто»): машина = только ГУ; телефон = только `AudioRecord` VOICE_RECOGNITION 16 кГц; авто = ГУ, а если данных нет 2 с, `MIC_RECORD_END` и переход на телефон. Лог с префиксом `MIC` (open/close от AA, запросы к ГУ, байты с ГУ раз в секунду, fallback). Микрофон телефона требует RECORD_AUDIO; для работы в фоне сервис берёт foreground-тип `connectedDevice|microphone`, если разрешение выдано.

