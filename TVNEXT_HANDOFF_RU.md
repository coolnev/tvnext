# TVNext — актуальный контекст, 2026-11-05

Текущая база — предоставленный пользователем TVNextR, версия `0.5.28-recovery-preview`, Android release, пакет `com.tvnext.app.recovery`, versionCode `528`.

Актуальные исходники: `/workspace/work/tvnext-current`. Старый `/workspace/tvnext` — прежняя версия 0.2.0, не использовать как базу текущего релиза.

GitHub: https://github.com/coolnev/tvnext/tree/source/android-navigation-20261105
В этой ветке сохранены исходный ZIP пользователя, распакованные исходники, коммит исправлений, APK и контрольная сумма. Первый коммит `0445ce8` сохраняет исходную базу до изменений. Не заменять ветки с прежними APK и исходниками.

APK: `releases/android/TVNextR-0.5.28.apk`.
Оригинальный ZIP: `source-archives/TVnextR-source-2026-11-05.zip`.
Изменения и ограничения: `RELEASE_ANDROID_0.5.28_RU.md`.

Исправлены непрерывный список EPG через все доступные даты, восстановление фокуса архивной передачи, навигация оконного режима каналы → программа → видео → OK/полный экран, часы внутри названия канала, постоянная область видео и перенос управляющих кнопок по ширине.

Проверки: 48 тестов (включая 5 Compose UI), checkEncoding, assembleRelease/lintVitalRelease, APK v2, zipalign, целостность APK. Испытаний на физическом ТВ не было.

Окружение:

```bash
. /workspace/.tools/tvnext-env.sh
cd /workspace/work/tvnext-current
DISPLAY=:99 gradle --no-daemon --console=plain --max-workers=2 checkEncoding :features:channels:desktopTest :apps:android:assembleRelease
```

Compose-тесты используют Skiko текущей ОС. Для этого запуска был поднят Xvfb :99; процесс не гарантирован в следующей сессии. JDK 21, Gradle 9.4.1, SDK 36 находятся в /workspace/.tools. Подпись использует локальное debug.keystore согласно apps/android/build.gradle.kts; приватный ключ не публикуется.

Не восстанавливать исходники из APK: полная база и последующие изменения сохранены в GitHub. Дальнейшие изменения также коммитить и сохранять в GitHub по запросу пользователя.
