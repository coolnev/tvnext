# Архитектура TVNext

## 1. Общая картина

TVNext — **Kotlin Multiplatform**-приложение, собираемое на две платформы:

- **Android TV** — приложение `apps/android` (Activity + Compose).
- **Windows desktop** — приложение `apps/desktop` (Compose Desktop).

Весь бизнес-код живёт в `commonMain` модулей `core/*` и `features/*`; платформенные отличия вынесены в `androidMain`/`desktopMain` через механизм `expect`/`actual` (файловый ввод-вывод, плеер, лог, шрифты, кеш-каталоги и т.д.).

Слои:

```
apps (android / desktop)  — точка входа, создание DI-графа, жизненный цикл
        │
features (home/channels/settings/media/tvg/search) — экраны, MVI-компоненты
        │
core (app/ui/iptv/player/database/network/tmdb/licensing/common)
        │
        └── только вверх: features зависят от core, core не зависит от features
```

## 2. Навигация (Decompose)

- Каждый экран — **компонент** (класс, реализующий `*Component` + `ComponentContext` от Decompose).
- Корневой компонент — `DefaultAppComponent` (`core:app`): владеет стеком табов, подпиской на back-обработку (`BackHandler`), жизненным циклом табов.
- Табы (`AppTab`): **Главная**, **Каналы**, **Настройки**, **Медиатека**, **ТВ-гид**, **Поиск**.
- При переключении таба предыдущий компонент уничтожается (это важно для жизненного цикла и пересоздания экранов), а при возврате — создаётся заново. Поэтому «тяжёлые» операции не должны висеть на компоненте таба — они выносятся в репозитории/процесс-уровневые корутины.
- Back-обработка: в табе Back возвращает на главный экран, на главном экране — двойной Back закрывает приложение (Android).

## 3. UI-архитектура (MVI)

Каждый компонент построен по схеме MVI на базе `MviComponent<TState, TIntent, TEffect>` (`core:common`):

- **State** — неизменяемый `data class` (например, `TvgState`, `ChannelsState`), публикуется через `StateFlow`.
- **Intent** — событие пользователя/системы (`Load`, `Refresh`, `ChannelClicked`, …).
- **reduce(intent) → State** — чистая функция трансформации состояния (без побочных эффектов).
- **handle(intent)** — побочные эффекты: запуск корутин, вызовы репозиториев, навигация.
- Экран (Compose-функция) подписывается на `state` через `collectAsState` и вызывает `component.dispatch(intent)`.

Слой данных — **репозитории** (`core:iptv/data`, `features/*/data`), поверх SQLDelight-хранилищ и сетевых источников. Репозитории возвращают `AppResult<T>` (`core:common`).

## 4. DI (Koin)

- Каждый модуль объявляет свой `*KoinModule` (`core/iptv/di/IptvKoinModule`, `features/channels/di/ChannelsKoinModule` и т.д.).
- Все модули собираются в `TVNextApplication.onCreate` (Android) и `Main.kt` (Desktop).
- Синглтоны: HttpClient, репозитории, хранилища, `EpgRepository`, `PlaybackController`, `ScheduledReminderService` и др.
- Платформенные модули (`DatabaseAndroidKoinModule`, `PlayerAndroidKoinModule`, …) подключаются отдельно.

## 5. Сеть (Ktor)

- `core:network` — единый `HttpClient` (Android: OkHttp; Desktop: CIO) с таймаутами и пользовательским агентом.
- `core:iptv` использует его для загрузки M3U, XMLTV (EPG) и запросов к VOD.
- Стриминговые источники (`withEpgStream`) читают ответ HTTP **внутри** `execute { }`, чтобы не закрывать соединение раньше времени.

## 6. Хранилища (SQLDelight)

- `core:database` — фабрика БД, таблицы и мета-репозиторий (`AppMetaRepository` — key/value для служебных настроек).
- Хранилища (`ChannelStore`, `MediaItemStore`, `HistoryStore`, `ScheduledRepository`, `ArchiveCapabilityStore`, `SqlDelightEpgStore`) — тонкие обёртки над запросами SQLDelight.
- **EPG больше не пишется в БД**: телепрограмма живёт в памяти и в лёгком дисковом кеше (см. `core:iptv` → `LiteEpgRepository`). Таблицы EPG в схеме оставлены только для обратной совместимости (удаление плейлиста чистит их через `EpgStore.deleteByPlaylist`).

## 7. Плеер

- `core:player` объявляет абстракцию `PlaybackController` + `PlayerSurface` (expect/actual).
- **Android**: `AndroidPlaybackController` на ExoPlayer (Media3), surface через `PlayerSurface.android`, hardware overlay для полноэкранного видео.
- **Desktop**: `DesktopPlaybackController`/`MpvPlaybackController` через JNA (mpv), плюс серия зондов (`VlcProbe`, `ComposeHwSurfaceProbe`, `HlsPrefetchProbe`…), выбирающих наиболее производительную поверхность рендеринга.
- Управление (play/pause/stop, kind: LIVE/VOD/ARCHIVE), состояние (`PlayerState`), ошибки (`PlaybackError`).

## 8. Потоки данных EPG

```
XMLTV (HTTP, gzip) ──withEpgStream──► LiteXmltvParser (скользящий буфер)
        │  (фильтр: только каналы плейлиста, title/start/stop, desc в окне)
        ▼
   EpgBuffer (потокобезопасный накопитель)
        │  (инкрементальная публикация каждые ~800 мс)
        ▼
   programs: Map<playlistId|channelId, List<EpgProgramme>>   ──►  дисковый кеш
        │                                                           (epg_programs.dat)
        ▼
   EpgRepository API (getNowPlaying / getNowNext / getProgrammesInRange / searchProgrammes)
        ▼
   Экраны: Каналы (now playing), ТВ-гид (сетка), Поиск (по программе), Главная (карточки)
```

Подробности — в `docs/MODULES.md` (раздел `core:iptv`).

## 9. Платформенные отличия (expect/actual)

| Возможность | Android | Desktop |
|---|---|---|
| Плеер | ExoPlayer | mpv (JNA), fallback VLC/Compose |
| Файловый кеш EPG | `context.cacheDir/epg_cache` | `~/.tvnext/cache/epg` |
| Локальный каталог приложения | `filesDir` | каталог рядом с запускаемым jar |
| Таймзона | `TimeZone.getDefault()` | то же (JVM) |
| Логотипы/шрифты | ресурсы + disk-кеш | файлы + disk-кеш |

## 10. Жизненный цикл процесса (Android)

- `TVNextApplication.onCreate`: инициализация Koin, boot-маркер/диагностика (`AndroidBootDiagnostics`), `seedDemoData()` (предзаполнение демо-источников), наблюдатель ошибок EPG.
- `MainActivity`: fullscreen, корневой компонент Decompose, `onStart/onStop` управляют LIVE-плеером (стоп в фоне, рестарт на передний план), `onDestroy` — штатный выход (чистит boot-маркер).
