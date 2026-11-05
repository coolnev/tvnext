# TVNext — актуальный контекст, 2026-11-05

Текущая версия TVNextR: `0.5.31-recovery-preview`, versionCode 531, Android release, пакет `com.tvnext.app.recovery`. Подпись та же, что у 0.5.28–0.5.30.

Актуальный checkout: `/workspace/work/tvnext-current`.
GitHub: https://github.com/coolnev/tvnext/tree/source/android-navigation-20261105
Старый `/workspace/tvnext` — прежняя версия 0.2.0. Из неё восстановлен только отсутствовавший в новом ZIP тестовый ресурс real-world-ru.m3u.

Исходная база: загруженный пользователем TVnextR-source.zip. Нетронутый ZIP лежит в `source-archives/TVnextR-source-2026-11-05.zip`, SHA-256 b3284f0ddbc6a5f3608327a3c2c74e551ca81f03adcd24a466564d70e13c5bde. Первый коммит 0445ce8 сохраняет исходную базу. Не восстанавливать исходники из APK и не заменять старые ветки.

APK: `releases/android/TVNextR-0.5.31.apk`.
Описание: `RELEASE_ANDROID_0.5.31_RU.md`.
Сводка: `releases/android/validation-0.5.31.json`.

В 0.5.31 Android HTTP отделён от разбора/SQLite: EpgSourceDownload сохраняет исходник в noBackupFilesDir/epg-downloads-v1 (частичный файл + валидатор HTTP для Range/If-Range). После успешной загрузки HTTP закрывается; XMLTV/gzip читается из файла. Файл сохраняется до коммита базы, затем удаляется. Уход Activity в фон и общий порог heap не обрывают ограниченный по памяти импорт; новые задачи ждут foreground. Повторы временных ошибок ограничены четырьмя попытками, без повтора 401/403/404. SQLite пишет до 512 строк/256 КиБ на транзакцию и до 64 строк на SQL; два лишних индекса удалены только в отдельной EPG-базе. Резервные источники проверяются после основного и не скачиваются при обеспеченных видимых каналах. notice показывает скачивание в МБ отдельно от обработки передач. Набор: 205 IPTV + 4 Settings + 22 Android native проверки API 28/35; большой Android импорт 20 000 передач, XML парсер 64,8 МБ / 50 000 передач. Полные исходники уже сохранены коммитом 9a4d654.

Критическая регрессия 0.5.29 исправлена: `driver.execute(PRAGMA busy_timeout=3000)` в AndroidSqliteDriver вызывал SQLiteException (PRAGMA возвращает строку, драйвер использует executeUpdateDelete). EPG не открывался, вход в настройки завершал процесс. Подтверждено на реальном APK 0.5.29 в эмуляторе и native Android SQLite в Robolectric. В 0.5.30 PRAGMA удалены, WAL/пул соединений настраивает FrameworkSQLite. Настройки безопасно обрабатывают ошибку чтения кэша. Не выдавать 0.5.29 как актуальную версию. Формат кэша и данные сохранены.

Android EPG реализует IndexedEpgRepository и IndexedEpgStore. Adapter AndroidEpgRepository создаёт отдельный WAL SQLite index в noBackupFilesDir/epg-index-v1.db. Прежние компактные файлы импортируются потоково. UI читает индекс без HTTP и без гидрации полного EPG; видимые каналы задают приоритет, свежесть источника постоянная (12ч), публикация постепенная, старая программа сохраняется при сбоях. Desktop остаётся на LiteEpgRepository. EpgStore основного приложения не участвует в новом импорте.

Стартовая вкладка задаётся сразу по сохранённым настройкам. Нет ложного приглашения добавить плейлист во время первичной подготовки. Home/VOD и профильные проекции вынесены с UI. Соотношение сторон — «Авто».

TMDB/Kinopoisk: повторы временных ошибок, сохранение удачного кэша/частичных карточек, объединение запросов, дозагрузка кадров через /v1.5/image. Изображения скачивает ограниченный отменяемый fetchArtworkBytes. Меню/пульт, непрерывные сутки EPG, архивный фокус и постоянный размер окна из 0.5.28 сохранены.

Предыдущие проверки 0.5.29 (не обнаружили Android-регрессию): 345 тестов (IPTV 202, TMDB 19, network 3, channels/UI/artwork 51, Home 4, player 66), checkEncoding, assembleRelease/lintVitalRelease, подпись/zipalign/ZIP. Живые TMDB/Кинопоиск/CDN блокируются политикой сети окружения; локальные HTTP/SQLite/декодер проверки проходят. Физический ТВ не использовался.

```bash
. /workspace/.tools/tvnext-env.sh
cd /workspace/work/tvnext-current
DISPLAY=:100 gradle --no-daemon --console=plain --max-workers=2 checkEncoding :core:iptv:desktopTest :core:tmdb:desktopTest :core:network:desktopTest :features:channels:desktopTest :features:home:desktopTest :core:player:desktopTest :apps:android:assembleRelease
```

Xvfb :100 запускался из /workspace/.tools/apt/x11/usr/bin/Xvfb; процесс не гарантирован в следующей сессии. JDK 21, Gradle 9.4.1, SDK 36 в /workspace/.tools. ReleaseSign использует /workspace/.tools/user-home/.android/debug.keystore; приватный ключ не опубликован.

Полные исходники, исходный ZIP, APK и проверки сохранять на GitHub при дальнейших изменениях, как просил пользователь.

Проверки исправления 0.5.30: 6 Android native SQLite/Settings проверок на API 28 и 35, 202 IPTV и 4 Settings desktop-теста; checkEncoding, assembleRelease/lintVitalRelease, подпись v2 и zipalign. Регрессионные тесты — apps/android/src/test/kotlin/com/tvnext/app/AndroidEpgRegressionTest.kt. Настольный SQLite недостаточен для Android-драйвера.

Сам APK 0.5.30 проверен в Android 9 эмуляторе поверх 0.5.29: настройки открыты, XMLTV импортирован (1/3). После force-stop и удаления adb reverse (источник отключён) видны архивная/текущая/следующая передачи, Вправо открывает список программ. За весь сценарий один GET /epg.xml. После офлайн-перезапуска нет FATAL/ANR. Скриншоты и известное ограничение программного эмулятора без KVM — в validation-0.5.30.json и каталоге рядом.

Проверка APK 0.5.31: Android 9 эмулятор, обновление поверх 0.5.30. Свежий кэш не вызывает HTTP. Локальный gzip из 3000 передач намеренно оборван; повтор с сохранённым префиксом использует Range bytes=79099- и If-Range. Импорт завершился с 3003 записями (3 старых архивных), старые передачи доступны во время обновления. Временный файл удалён после коммита. FATAL/ANR приложения не было. См. validation-0.5.31.json, HTTP журнал и скриншоты рядом; физическая плавность ТВ не подтверждена.
