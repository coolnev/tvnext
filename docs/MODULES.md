# Модули TVNext

Список модулей определён в `settings.gradle.kts`. Модули разделены на три группы: `core/*` (ядро), `features/*` (экраны), `apps/*` (исполняемые приложения). Все модули — Kotlin Multiplatform (`commonMain` + платформенные source sets).

Обозначения: **З** — зависит от модулей (по `build.gradle.kts`), **Платформа** — платформенные source sets и их содержимое.

---

# core/* — ядро

## core:common

**Назначение**: базовые абстракции и утилиты, от которых зависит весь проект: работа с результатами, логирование, MVI-каркас, платформенная абстракция, время.

**Ключевые пакеты и классы**:

| Пакет | Класс | Описание |
|---|---|---|
| `result` | `AppResult<T>` | Результат операции: `Success` / `Failure`. |
| `result` | `AppError` | Иерархия ошибок: `Network`, `Database`, `InvalidData`, `License`, `Playback`, `InvalidPlaylist`, `Parse`, `EmptyPlaylist`. |
| `mvi` | `MviComponent<S,I,E>` | Базовый класс MVI-компонента: `state: StateFlow`, `dispatch(intent)`, `reduce()` + `handle()`, `setState{}`. |
| `mvi` | `MviState`, `MviIntent`, `MviEffect` | Маркерные интерфейсы. |
| `log` | `Logger` | Абстракция логирования (`d`/`e`); actual: Android `Log`, Desktop `println`. |
| `platform` | `Platform` | `isAndroidPlatform()`, `isDesktop()`. |
| `platform` | `AppDataReset`, `AppAutostart` | expect/actual: сброс данных приложения, автозапуск. |
| `util` | `TimeProvider`, `TimeFormat` | expect/actual: `currentTimeMillis()`, форматирование дат (`formatDateTime`, `startOfDay`, …). |
| `di` | `CommonKoinModule` | Базовые binding (таймер, main-dispatcher). |

**З**: нет (лист).
**Платформа**: `Logger.android/desktop`, `Platform.android/desktop`, `TimeFormat.android/desktop`, `TimeProvider.android/desktop`.

---

## core:ui

**Назначение**: переиспользуемый Compose-слой: фирменная тема, TV-компоненты, загрузка изображений (логотипы каналов/постеры).

**Ключевые пакеты и классы**:

| Пакет | Класс | Описание |
|---|---|---|
| `theme` | `Theme`, `Color`, `AccentColor` | Material 3-тема, палитра (тёмная для TV), акцентные цвета. |
| `theme` | `Dimension` (`TVNextDimens`) | Единая сетка отступов/размеров для TV-интерфейса. |
| `theme` | `Type`, `Fonts`, `Shape` | Типографика, шрифты (expect/actual), скругления. |
| `component` | `TvCardButton`, `TvControls`, `TvListState` | TV-кнопки (фокус, скан), пульт-навигация, состояние списков. |
| `component` | `TvPlayerPanel`, `TvPlayerPlaceholder` | Панель управления плеером, заглушка. |
| `component` | `TvProgressBar`, `TvSearchField`, `ScreenHeader`, `AutoScrollText` | Прогресс, поле поиска, заголовок экрана, бегущая строка. |
| `image` | `LogoImageLoader` | Загрузка и кеширование логотипов (дисковый кеш, Coil-подобный). |
| `image` | `ChannelLogo` | Composable отображения логотипа канала. |
| `image` | `ImageDecoder`, `LogoImageFiles` | expect/actual: декодирование изображений, пути к кешу логотипов. |
| `screen` | `PlaceholderScreen` | Экран-заглушка (нет данных/ошибка). |

**З**: `core:common`.
**Платформа**: `ImageDecoder.android/desktop`, `LogoImageFiles.android/desktop`, `Fonts.android/desktop`, `DesktopHover.android/desktop`.

---

## core:app

**Назначение**: каркас приложения — корневой компонент Decompose, табы, back-обработка, точка сборки всего UI.

**Ключевые классы**:

| Класс | Описание |
|---|---|
| `DefaultAppComponent` | Корневой компонент: создаёт фабрику табов, регистрирует `BackHandler`, владеет фоном и подписками. |
| `AppShell` | Composable-обёртка над компонентом (состояние, рендер активного таба). |
| `AppLogo` | Логотип приложения (expect/actual). |
| `tab/AppTab`, `tab/AppTabComponent` | Перечисление табов и фабрика компонента таба. |
| `di/AppKoinModule` | DI: фабрика табов, сквозные зависимости. |

Ключевая деталь: **единственный автоматический EPG-импорт** запускается здесь (процесс-лайфтайм, через ~15 с после старта) — это гарантирует, что телепрограмма загрузится независимо от того, какие табы открывает пользователь.

**З**: `core:common`, `core:ui`, `core:iptv`, `core:player`, `core:licensing`, `core:database`, `core:network`, `core:tmdb`, все `features/*`.
**Платформа**: `AppLogo.android/desktop`.

---

## core:database

**Назначение**: SQLDelight-база данных (фабрика, мета-хранилище, raw SQL).

**Ключевые классы**:

| Класс | Описание |
|---|---|
| `DatabaseFactory` | expect/actual: создание `TvNextDatabase` (Android: контекст, Desktop: файл в `~/.tvnext`). |
| `AppMetaRepository` | Key/value-хранилище служебных метаданных (версии парсеров, маркеры, время). |
| `RawSql` | expect/actual: выполнение сырых SQL (для массовых операций/миграций). |
| `di/DatabaseKoinModule` (+ platform) | Регистрация БД. |

Таблицы: плейлисты, каналы, VOD-элементы, история, напоминания, избранное, EPG (устаревшие, не используются новым движком), мета-настройки.

**З**: `core:common`.
**Платформа**: `DatabaseFactory.android/desktop`, `RawSql.android/desktop`, платформенные Koin-модули.

---

## core:network

**Назначение**: единый HTTP-клиент.

**Ключевые классы**:

| Класс | Описание |
|---|---|
| `HttpClientFactory` | expect/actual: создание `HttpClient` с таймаутами и User-Agent. |
| `di/NetworkKoinModule` | Регистрация `HttpClient` как синглтона. |

**З**: `core:common`, Ktor.
**Платформа**: `HttpClient.android` (OkHttp) / `HttpClient.desktop` (CIO).

---

## core:tmdb

**Назначение**: интеграция с TMDB для метаданных VOD (постеры, описания, жанры).

**Ключевые классы**:

| Класс | Описание |
|---|---|
| `TmdbRepository` | Запросы к API TMDB (ключ задаётся в настройках). |
| `TmdbModels` | Модели ответов (фильм, сериал, изображения). |
| `di/TmdbKoinModule` | DI. |

**З**: `core:common`, `core:network`.

---

## core:iptv

**Назначение**: ядро IPTV — плейлисты, каналы, VOD, **EPG (телепрограмма)**, архив (catch-up), история, напоминания. Самый крупный модуль.

### Пакеты

| Пакет | Содержимое |
|---|---|
| `domain` | Модели: `Channel` (id, имя, номер, категория, URL, `tvgId`, избранное, `playlistId`, catch-up-поля), `Playlist` (id, URL, `epgUrl` из заголовка M3U, enabled, type), `MediaItem`, `WatchContentType`. |
| `m3u` | `M3uParser` — потоковый парсер M3U: атрибуты `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, catch-up-атрибуты, заголовочный `url-tvg`/`x-tvg-url` (EPG-адрес плейлиста). |
| `xmltv` | Модели XMLTV (`EpgChannel`, `EpgProgramme`, `XmltvDocument`), `XmltvParser` (общий потоковый парсер, используется тестами/резервно), `XmltvTimestamp`. |
| `data` | Репозитории, хранилища и EPG-движок (см. ниже). |
| `archive` | Catch-up/архив: `ArchiveRepository`, `CatchupUrlBuilder` (подстановка `{utc}` в `catchup-source`), `ArchiveDepthResolver` (глубина архива), `ArchiveCapabilityStore`, `ArchiveProbe`, `ArchiveModels`. |
| `di` | `IptvKoinModule` — DI-граф модуля. |
| `util` | `Gzip` (expect/actual). |

### data — репозитории и хранилища

| Класс | Описание |
|---|---|
| `PlaylistRepository` / `IptvPlaylistRepository` | CRUD плейлистов, добавление/удаление/обновление, чистка связанных данных при удалении. |
| `ChannelsRepository` / `IptvChannelsRepository` | Каналы из БД, избранное, `isConfigured()`. |
| `MediaLibraryRepository` / `IptvMediaLibraryRepository` | VOD-элементы: список, категории, поиск. |
| `EpgRepository` | **Контракт EPG**: `observeState()`, `observeVersion()`, `refreshState()`, `refreshIfStale()`, `refreshPlaylist()`, `getUpcomingProgrammes()`, `getNowNext()`, `getNowNextForChannels()`, `getNowPlaying()`, `getProgrammesInRange()`, `searchProgrammes()`, `getMatchingDiagnostics()`. |
| `LiteEpgRepository` | **Реализация EPG-движка** (порт подхода LiteTV). См. раздел «EPG» ниже. |
| `LiteXmltvParser` | Потоковый XMLTV-парсер: скользящий буфер, фильтр по каналам плейлиста, `title` всегда + `description` в окне «−2 дня…+7 дней», нормализация (сортировка, дедупликация, восстановление `stop`). |
| `EpgMatcher` | Старый матчинг каналов (сохранён как источник `EpgDiagnostics`). |
| `EpgStore` / `SqlDelightEpgStore` | SQLite-хранилище EPG — **не используется** новым движком; оставлено для `deleteByPlaylist` при удалении плейлиста. |
| `EpgFetcher` / `KtorEpgFetcher`, `EpgIo` | Файловое скачивание XMLTV (резервный путь) и `expect`-функции: `openEpgTextSource`, `withEpgStream`, `platformEpgCacheDir`, `writeEpgCacheFile`, `readEpgCacheFile`, `localTimezoneOffsetMs`. |
| `EpgConfigProvider` | Настройки EPG (включён/выключен, пользовательский URL) — реализуется в `features/settings`. |
| `EpgDiagnosticReporter` | Сборщик состояния EPG для диагностики (Brevo/лог). |
| `HistoryRepository` / `HistoryStore` | История просмотров. |
| `ScheduledRepository` / `ScheduledReminderService` | Напоминания о будущих передачах (периодическая проверка, показ баннера). |
| `ChannelStore`, `MediaItemStore` | SQLDelight-хранилища каналов и VOD. |
| `ChannelIdMigration`, `IdGenerator` | Миграция id каналов, генерация стабильных id. |

### EPG — как устроен движок (`LiteEpgRepository` + `LiteXmltvParser`)

Замена тяжёлого SQLite/нативного конвейера (который на слабых Android TV приводил к молчаливому убийству процесса через ~15–25 с из-за пиков памяти) лёгким решением в стиле LiteTV:

1. **Хранение**: только в памяти — `Map<playlistId|channelId, List<EpgProgramme>>`. Без SQLite, без транзакций, без нативных парсеров.
2. **Парсинг**: `LiteXmltvParser` читает HTTP-поток (`withEpgStream`, gzip-aware) скользящим буфером (≤ 2 МБ в памяти). Сохраняются только передачи каналов, присутствующих в плейлисте. Матчинг по `tvg-id`, затем по нормализованному имени (удаляются пробелы/`-`/`_`/`.`/`·`, срезаются суффиксы `4k/8k/uhd/fhd/hd/超清/高清/标清/蓝光`). Поля: `title` + `start`/`stop` (таймзона из XMLTV, offset-варианты поддерживаются) + `description` (обрезан до 500 символов, только для передач в окне времени).
3. **Инкрементальная публикация**: парсер пишет в потокобезопасный `EpgBuffer`; отдельная корутина каждые ~800 мс сливает батчи в память и нормализует (сортировка по start, дедупликация, восстановление отсутствующих `stop` из следующего `start`). Первые каналы появляются на экране задолго до конца скачивания.
4. **Свежесть**: дисковый кеш `epg_programs.dat` (построчный, с экранированием) в `platformEpgCacheDir()`; считается свежим, если «моложе 12 ч И тот же календарный день» — иначе скачивается заново. Загрузка кеша в память происходит лениво при первом запросе — почти мгновенно.
5. **Версия**: `observeVersion()` инкрементируется **только** при реальном изменении данных (не при пустом refresh) — экраны (ТВ-гид) не сбрасывают загруженные ряды понапрасну.
6. **Защита от циклов**: одна загрузка за раз (mutex), после неудачи повторная попытка не раньше чем через 60 с; ошибка/статус остаются видимыми в `EpgState` (не затираются при `refreshState`).

### archive — catch-up

| Класс | Описание |
|---|---|
| `ArchiveRepository` / `IptvArchiveRepository` | Определение возможности архива у канала, построение URL передачи. |
| `CatchupUrlBuilder` | Подстановка `{utc}`/`{start}`/`{end}` в `catchup-source`. |
| `ArchiveDepthResolver` | Проверка доступности нужной глубины архива (проба). |
| `ArchiveCapabilityStore` | Кеш возможностей архивов. |

**З**: `core:common`, `core:database`, `core:network`.
**Платформа**: `EpgIo.android/desktop`, `EpgDiagnosticReporter.android/desktop`, `Gzip.android/desktop`.

---

## core:player

**Назначение**: воспроизведение контента на обеих платформах через единый интерфейс.

**Общие классы**:

| Класс | Описание |
|---|---|
| `PlaybackController` | Интерфейс управления: `play(item)`, `pause()`, `resume()`, `stop()`, `release()`, `state: StateFlow<PlayerState>`. |
| `PlayerState` | Состояние: item, позиция, isPlaying, буферизация. |
| `PlaybackError` | Ошибки воспроизведения. |
| `VideoDecodeMode` | Режим декодирования (auto/hw/sw). |
| `PlayerSurface` | expect/actual: Compose-поверхность видео. |
| `HardwareOverlayHost` | expect/actual: хост аппаратного оверлея (Android). |
| `ClockPosition` | Позиция часовой метки (для EPG-записей). |
| `di/PlayerKoinModule` | Общий DI. |

**Android** (`androidMain`):

| Класс | Описание |
|---|---|
| `AndroidPlaybackController` | Плеер на ExoPlayer (Media3): LIVE/VOD/ARCHIVE, автоперезапуски, таймауты, hardware декодирование. |
| `PlayerSurface.android` | SurfaceView/TextureView-интеграция. |
| `di/PlayerAndroidKoinModule` | DI. |

**Desktop** (`desktopMain`):

| Класс | Описание |
|---|---|
| `MpvPlaybackController` / `DesktopPlaybackController` | Плеер на mpv через JNA (`MpvJna`). |
| `MpvRuntimeLocator`, `VlcRuntimeLocator` | Поиск mpv.dll / libvlc. |
| `VlcRenderSurface`, `DesktopVideoSurface` | Поверхности рендеринга (VLC/Compose). |
| `ComposeHwSurfaceProbe`, `VlcHwProbe`, `HlsPrefetchProbe` | Зонды возможностей железа/форматов. |
| `HlsPrefetchServer` | Локальный прокси-префетч HLS. |
| `KeepAwake` (в `apps:desktop`) | Запрет сна при воспроизведении. |

**З**: `core:common`, `core:network`, ExoPlayer/Media3 (Android), JNA (Desktop).

---

## core:licensing

**Назначение**: «премиум-гейт» — лицензирование функций (в текущей версии статическая лицензия, всё доступно).

**Ключевые классы**:

| Класс | Описание |
|---|---|
| `PremiumGate` | Интерфейс проверки премиум-доступа. |
| `DefaultPremiumGate` | Реализация. |
| `LicenseProvider` / `StaticLicenseProvider` | Источник лицензии (статическая — всё открыто). |
| `LicenseStatus` | Статусы. |
| `di/LicensingKoinModule` | DI. |

**З**: `core:common`.

---

# features/* — экраны

Каждый feature-модуль содержит `ui/` (MVI-компонент + Compose-экран), `domain/` (модели), `di/` (Koin-модуль), при необходимости `data/` (локальные репозитории).

## features:home

**Назначение**: главная страница — подборки для быстрого старта.

- `HomeComponent` — MVI: загрузка контента, автопродолжение последнего канала, обновление по версии EPG.
- `HomeScreen` — сетка карточек: «Продолжить просмотр», избранное, LIVE-каналы (с «сейчас идёт»), VOD.
- `HomeRepository` — сборка контента из репозиториев ядра (история, избранное, каналы, VOD, EPG now/next).
- `HomeModels` — модели подборок.
- `di/HomeKoinModule` — DI.

**З**: `core:common`, `core:ui`, `core:iptv`, `core:player`, `core:tmdb`.

## features:channels

**Назначение**: список каналов и просмотр LIVE-телевидения (основной экран).

- `ChannelsComponent` — MVI: загрузка каналов, категории, поиск, выбор канала → воспроизведение, автозапуск последнего канала на Android TV, now-playing из EPG, catch-up-статус.
- `ChannelsScreen` — список каналов (с логотипами и «сейчас идёт»), панель плеера, категорийная навигация, боковая панель, инфо-панель передачи.
- `Channel` (domain) — локальная модель представления.
- `di/ChannelsKoinModule` — DI.

**З**: `core:common`, `core:ui`, `core:app`, `core:iptv`, `core:player`, `core:database`, `core:licensing`.

## features:settings

**Назначение**: настройки приложения.

- `SettingsComponent` — MVI: управление плейлистами (добавить/обновить/удалить), настройки EPG (включить, URL, статус, обновление), настройки архива, TMDB-ключ, сброс данных, диагностика.
- `SettingsScreen` — экран настроек по секциям.
- `SettingsRepository` + `SettingsModels` — AppSettings (persisted в БД).
- `SettingsEpgConfigProvider`, `SettingsArchiveConfigProvider` — реализация конфиг-провайдеров ядра.
- `di/SettingsKoinModule` — DI.

**З**: `core:common`, `core:ui`, `core:app`, `core:iptv`, `core:database`, `core:network`, `core:tmdb`, `core:player`.

## features:media

**Назначение**: медиатека (VOD).

- `MediaComponent` — MVI: списки VOD, категории, поиск, продолжение просмотра.
- `MediaScreen` — постеры, категории, детальный просмотр.
- `VodProgressStore` — сохранение прогресса просмотра.
- `MediaModels` — модели.
- `di/MediaKoinModule` — DI.

**З**: `core:common`, `core:ui`, `core:app`, `core:iptv`, `core:player`, `core:database`, `core:licensing`.

## features:tvg

**Назначение**: ТВ-гид — сетка телепрограммы (EPG).

- `TvgComponent` — MVI: загрузка сетки, окно дней (вчера…+7), категории каналов, **viewport-загрузка EPG** (запрашиваются только видимые ряды), инфо-панель передачи, архив (catch-up), напоминания. Слушает `observeVersion()` EPG — при фоновом импорте перезагружает видимые ряды.
- `TvgScreen` — сетка: колонка каналов слева, часы сверху, передачи; линия текущего времени; D-pad-навигация; день-навигация (−7/−1/0/+1/+7). Горизонтальный скролл дней на desktop: перетаскивание левой кнопкой мыши и колесо мыши (`Modifier.dragDayHorizontal`/`Modifier.mouseWheelHorizontal` поверх общего `ScrollState`, переведённые на Android в D-pad-навигацию по передачам).
- `di/TvgKoinModule` — DI.

**З**: `core:common`, `core:ui`, `core:app`, `core:iptv`, `core:player`, `core:database`.

## features:search

**Назначение**: глобальный поиск.

- `SearchComponent` — MVI: поиск по каналам, VOD и телепрограмме (`EpgRepository.searchProgrammes`).
- `SearchScreen` — поле поиска, результаты с группировкой.
- `di/SearchKoinModule` — DI.

**З**: `core:common`, `core:ui`, `core:app`, `core:iptv`, `core:player`.

---

# apps/* — приложения

## apps:android

**Назначение**: Android TV приложение.

| Файл | Описание |
|---|---|
| `MainActivity` | Точка входа: fullscreen (immersive), сборка корневого `DefaultAppComponent`, `onStart/onStop` — стоп/рестарт LIVE, `onDestroy` — штатный выход. |
| `TVNextApplication` | Koin-граф всех модулей, `installEpgAndroidContext`, `seedDemoData()` (предзаполнение демо-источников), наблюдатель ошибок EPG, boot-диагностика. |
| `AndroidBootDiagnostics` | Boot-маркер + `tvnext_diag.log`: определяет, был ли предыдущий процесс убит системой (LMK/native crash), пишет память/размер БД. |
| `AndroidAppDataReset` | Сброс всех данных (маркер + перезапуск). |
| `BrevoDiagnosticReporter` | Отправка диагностики сбоев (реализация сбора). |

Зависимости: все `core/*` и `features/*`.

## apps:desktop

**Назначение**: Windows desktop приложение.

| Файл | Описание |
|---|---|
| `Main.kt` | Точка входа: Koin-граф, окно Compose, `DefaultAppComponent`, обработка выхода. |
| `DesktopAppAutostart` | Автозапуск при старте Windows. |
| `KeepAwake` | Запрет перехода системы в сон при воспроизведении. |
| `DesktopAppDataReset` | Сброс данных. |

Зависимости: все `core/*` и `features/*`.

---

# Потоки данных между модулями (кратко)

```
MainActivity (apps:android) ──► DefaultAppComponent (core:app)
                                    │  создаёт табы через AppTabComponent
                                    ▼
                        ChannelsComponent (features:channels)
                                    │
                                    ├──► ChannelsRepository ──► ChannelStore (SQLDelight)
                                    ├──► PlaybackController.play(...) (core:player)
                                    └──► EpgRepository.getNowPlaying(...) (core:iptv)
                                                        ▲
                        TvgComponent (features:tvg) ─────┤ getProgrammesInRange
                        HomeComponent  (features:home) ──┤ getNowNextForChannels
                        SearchComponent(features:search)─┤ searchProgrammes
                        SettingsComponent (features:settings) ──► refreshIfStale / refreshState
```

Репозитории ядра — единственные владельцы данных; feature-компоненты только подписываются на их `StateFlow`/`observe*` и вызывают suspend-методы.
