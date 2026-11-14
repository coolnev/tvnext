# Android: аппаратное видео Amlogic — 1.0.13

Пользователь подтвердил: на Ugoos X3 дёргается 4K-изображение, звук идёт ровно; проблема заметна также с выключенным AFR. Конкретный канал и поток для воспроизведения в этой среде не предоставлены. Это основание проверить очередь видеодекодера и выдачу кадров; оно само по себе не доказывает причину на физической приставке.

## Проверенные источники

- [Ugoos: X3 Cube / Pro / Plus](https://ugoos.com/x3-tv-box-family-series-devices-based-on-android-9-0): Amlogic S905X3, Cortex-A55, Mali-G31MP2, штатный Android 9; заявлено HEVC Main10 до 4K 60 кадров/с. Это характеристика поддерживаемых форматов, а не гарантия любого кодека, профиля или битрейта.
- [Google Media3: асинхронная очередь](https://developer.android.com/media/media3/exoplayer/customization#enabling-asynchronous-buffer-queueing): рекомендуемый для отдельных устройств с пропусками кадров механизм с отдельными потоками обработки callback и подачи буферов. Доступен с API 23; по умолчанию включён с API 31.
- [Google: диагностика рывков видео](https://developer.android.com/media/media3/exoplayer/troubleshooting#video-playback-is-stuttering): асинхронная очередь — один из вариантов для API 23–30. Производительность зависит и от поддерживаемого формата.
- [Media3 1.11.0: DefaultMediaCodecAdapterFactory](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/DefaultMediaCodecAdapterFactory.java): на старом Android обычный путь синхронный, кроме отдельного штатного исключения Amazon TV. В TVNext 1.0.12 эта политика применялась без настройки для Amlogic.
- [Media3 1.11.0: AsynchronousMediaCodecAdapter](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/AsynchronousMediaCodecAdapter.java): при ошибке создания освобождает созданный MediaCodec и потоки перед передачей исключения вызывающему коду.

## Изменение

`AmlogicMediaCodecAdapterFactory` подключён через `AndroidAudioRenderersFactory.getCodecAdapterFactory()`. При API 23–30 только фактические аппаратные видеодекодеры `OMX.amlogic.*` и `c2.amlogic.*` получают штатную Media3-фабрику с `forceEnableAsynchronous()`.

Решение принимает параметры реального выбранного MediaCodec. Оно не зависит от названия телевизора, модели, AFR, разрешения интерфейса или языка. Исходная Configuration с Surface, MediaFormat, размерами, частотой кадров и DRM передаётся целиком. Выбор аппаратного/программного декодера не подменяется.

При IOException или RuntimeException во время асинхронной инициализации та же Configuration один раз направляется в синхронную Media3-фабрику. Для этого имени кодека повторные попытки async отключаются на срок жизни ExoPlayer. При повторной ошибке исключение доходит до обычной обработки Media3 с сохранением обеих причин. Ошибки выполнения уже запущенного кодека сохраняют прежнюю обработку приложения.

Аудиодекодеры, программное видео, другие производители и API 31+ используют прежнюю штатную фабрику. Dolby/PCM, буферы, AFR, SurfaceView, размеры видео и разрешение Android не менялись.

В существующей диагностике Output показывает `Amlogic async` либо `Amlogic sync fallback` после фактической успешной инициализации. Числовые счётчики `video.amlogicAsyncQueue` и `video.amlogicAsyncInitFallbacks` добавлены к прежнему учёту пропущенных кадров. Полных кадров видео и потоков в диагностике не сохраняется.

## Проверки и границы результата

Тесты на Android API 28 и 35 проверяют выбор очереди для аппаратного HEVC/UHD, неизменность Configuration 3840×2160/50, сохранение штатного пути аудио/программного видео/других производителей/нового Android, отказ async с однократным повтором на том же аппаратном кодеке, запоминание отказа отдельно для каждого кодека, передачу обеих ошибок и подключение политики во всех режимах звука. Матрица решений также проверяет SDK 22/23/28/30/31/35.

Это проверки интеграции и логики фабрики. Они не измеряют скорость физического Amlogic VDEC. APK не запускался на самом Ugoos X3/TCL; реальный проблемный 4K-поток и физический GPU/декодер здесь недоступны. Нельзя утверждать измеренное устранение рывков или конкретный прирост FPS до проверки на приставке.
