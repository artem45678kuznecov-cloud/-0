# Референсы → экраны и компоненты (0.2.0)

Утверждённые изображения — визуальные образцы, а не макеты для копирования
пикселей: текст и цифры в приложении настоящие, строки состояния, часов и
«домашней полоски» в интерфейсе нет (их рисует система).

## Изображение 1 — «Главная»

| Элемент референса | Где в коде |
|---|---|
| Шапка «NOX» + капсула состояния справа | `ui/components/Header.kt` → `NoxHeader`, `StatusInfo` (реальные: активные загрузки, свободное место) |
| Поиск + кнопка фильтра | `Header.kt` → `SearchRow`; фильтр — `ui/library/LibraryScreen.kt` → `FilterSheet` |
| «Медиатека» / «Все» | `ui/components/Controls.kt` → `SectionHeader`; «Все» открывает `LibraryScreen` |
| Большая карточка «Продолжить просмотр» | `ui/components/MediaCards.kt` → `ContinueWatchingCard` (только если есть реально начатое видео, `LibraryQuery.continueWatching`) |
| Плитки видео с длительностью | `MediaCards.kt` → `MediaTile`, `DurationBadge` |
| Блок загрузок / пустое состояние | `ui/components/DownloadCards.kt` → `DownloadCard`, `EmptyDownloads` |
| Плавающая нижняя панель со скользящей капсулой | `ui/glass/GlassBottomBar.kt` |

## Изображение 2 — «Загрузчик»

| Элемент референса | Где в коде |
|---|---|
| Заголовок «Загрузчик» + кнопка настроек | `ui/downloads/DownloadsScreen.kt` (`ScreenTitle`, `GlassIconButton`) |
| Поле ссылки с «Вставить» | `DownloadsScreen.kt` → `UrlGroup` |
| Четыре плитки качества с подписями | `ui/components/QualitySelector.kt` (360p низкое · 480p среднее · 720p высокое · MAX максимум) |
| Капсула «Скачать» | `Controls.kt` → `GlassButton` (стиль `GlassStyles.Primary`) |
| Подпись о фоне | «Загрузка продолжается в фоне. Можно свернуть NOX или заблокировать экран.» (текст референса «не закрывайте и не сворачивайте» заменён на правдивый) |
| Карточки очереди с отдельными ⏸/▶ и × | `DownloadCards.kt` → `DownloadCard`; прогресс — `ui/components/Progress.kt` (определённый / неопределённый / стоящий) |
| Карточка памяти (NOX / другое / свободно) | `DownloadCards.kt` → `StorageCard` |

## Изображение 3 — вкладка «Плеер»

| Элемент референса | Где в коде |
|---|---|
| Большая карточка текущего видео с кнопкой ▶ | `MediaCards.kt` → `PlayerHeroCard`, `GlassPlayButton` |
| Список видео строками | `MediaCards.kt` → `PlayerRowCard` |
| Меню видео (продолжить, с начала, переименовать, экспорт, поделиться, источник, удалить) | `ui/NoxRoot.kt` → `mediaMenu`, листы `ui/components/Sheets.kt` |
| Сам плеер, PiP | `player/PlayerActivity.kt` (Media3) |

## Иконка с лисой

`res/mipmap-*/ic_launcher_foreground.png` + `ic_launcher_background.png`
(adaptive icon), `drawable/ic_launcher_monochrome.xml` (тематические иконки
Android 13+), `drawable-nodpi/nox_splash_logo.png` (заставка до Android 12),
`drawable/ic_notification.xml` (значок уведомлений).
