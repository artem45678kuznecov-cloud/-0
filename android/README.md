# NOX Android

Нативная Android-версия NOX — офлайн-медиатеки для YouTube, VK Video и
других сайтов, которые понимает yt-dlp. Отдельный
проект в каталоге `android/`; iPhone/Pythonista-версия в корне репозитория
не затрагивается и живёт независимо.

Текущая версия — 0.3.0 (ветка `android-youtube-dynamic-quality`).

## Что внутри

- Kotlin, Jetpack Compose. Своя дизайн-система «жидкого стекла» в
  `ui/glass` и `ui/components`: GlassSurface, GlassButton, GlassBottomBar,
  DownloadCard, MediaCard, ContinueWatchingCard, список вариантов качества
  (`ui/downloads/VariantList.kt`), листы
  NoxBottomSheet. Темы и фон — `settings/AppSettings.kt`, `ui/theme`.
- Room — задания, медиатека, позиции просмотра. Единственный источник
  правды: после гибели процесса всё восстанавливается из базы. Схемы
  лежат в `app/schemas`, миграции 1→2 и 2→3 проверяются тестами на
  заполненных базах 0.1.0 и 0.2.1; разрушительного fallback нет.
- Обновления внутри приложения — `updates/`: манифест `nox-update.json`
  из последнего GitHub Release, проверка файла и подписи, PackageInstaller.
- Папка пользователя (SAF), экспорт, импорт, резервная копия — `storage/`.
- OkHttp — передача файла потоком на диск, Range/resume, `.part`.
- Chaquopy + yt-dlp 2026.8.19 — только разбор ссылки: `resolver.analyze`
  (один extract_info → каталог дорожек, `nox_catalog.py`) и `resolver.plan`
  (адреса ровно выбранных format ID). В передаче файла Python не участвует.
- Встроенный JS-движок для задач YouTube: QuickJS-NG v0.17.0 (исходники с
  контрольными суммами — `app/src/main/cpp/quickjs-ng/NOX_VENDOR.md`),
  JNI-мост `libnoxjs.so` (arm64-v8a, x86_64) и провайдер `nox_jsc.py` для
  скриптов EJS из пакета yt-dlp-ejs 0.8.0. Скрипту недоступны файлы, сеть и
  Android API; пределы памяти, стека, времени и вывода. Внешние программы
  (deno, node, qjs) не ищутся и не запускаются, скрипты из сети не качаются.
- Каталог вариантов и выбор — Kotlin (`downloader/catalog`): ступень по
  настоящему кадру, 30/60 к/с и HDR отдельно, язык звука, маршруты склейки
  MediaMuxer: H.264/H.265 + AAC → MP4, VP9 + Opus → WebM (Android 10+),
  AV1 + AAC → MP4 (Android 14+).
- Media3 ExoPlayer — плеер локальных MP4 и WebM без сети.
- Package id: `com.nox.offline`. minSdk 26 (Android 8.0), targetSdk 35.

## Как собрать

Нужны JDK 17, Android SDK (platform 35, build-tools 35.0.0), NDK
27.0.12077973 и CMake 3.22.1 (сборка встроенного JS-движка) и Python 3.12
на машине сборки (его использует Chaquopy для установки yt-dlp).

```
cd android
./gradlew testDebugUnitTest # JVM-тесты (в т.ч. миграция Room)
./gradlew lintDebug
./gradlew assembleRelease   # APK: app/build/outputs/apk/release/
python3 -m unittest discover -s app/src/test/python -v   # тесты резолвера и каталога
tools/ejs-host-check/run.sh  # движок NOX против настоящих задач YouTube (нужна сеть)
```

Подпись релиза читается из переменных окружения `NOX_KEYSTORE_PATH`,
`NOX_KEYSTORE_PASSWORD`, `NOX_KEY_ALIAS`, `NOX_KEY_PASSWORD`. Без них
релизная сборка не подписывается, а с `-Pnox.requireReleaseKey=true`
(так делает CI) не собирается вовсе. Подробности — `signing/README.md`.

## Где APK

GitHub Actions (`.github/workflows/android-apk.yml`) на каждый push в ветки
выпусков прогоняет тесты, lint, сборку и тесты на эмуляторе. Если тега
`android-v<версия>` ещё нет, публикует Release с файлами
`NOX-Android-v<версия>.apk`, `nox-update.json` и `SHA256SUMS`:

https://github.com/artem45678kuznecov-cloud/-0/releases/latest

Публикация идёт только с постоянным ключом из GitHub Secrets
`NOX_ANDROID_KEYSTORE_BASE64`, `NOX_ANDROID_KEYSTORE_PASSWORD`,
`NOX_ANDROID_KEY_ALIAS`, `NOX_ANDROID_KEY_PASSWORD`, и только если
сертификат APK совпадает с `signing/expected-cert-sha256.txt`. Без
секретов релиз не публикуется. Опубликованные теги не перезаписываются.

Переход с 0.1.0 (`android-v0.1.0`, другой ключ) — однократная ручная
переустановка: удалить 0.1.0, поставить 0.2.0. Начиная с 0.2.0 все версии
подписаны одним постоянным ключом и обновляются поверх, из приложения,
с сохранением данных.

## Где лежат файлы

App-specific external storage, без «доступа ко всем файлам»:

```
Android/data/com.nox.offline/files/NOX/
  Downloads/   .part незавершённых загрузок (всегда здесь, даже если
               готовые видео сохраняются в выбранную папку)
  Media/       готовые видео  <название> [<id>].mp4 / .webm
  Covers/      обложки
  Metadata/    служебное
```

## Фоновая загрузка

- Android 14+: `TransferJobService` — User-Initiated Data Transfer Job
  (`setUserInitiated(true)`, `RUN_USER_INITIATED_JOBS`, уведомление через
  `setNotification`). Планируется, когда приложение видно или из действия в
  уведомлении. `onStopJob` пишет причину остановки в журнал; `.part`
  сохраняются, задания возвращаются в очередь.
- Android 13 и ниже: `TransferForegroundService` типа dataSync.
- В обоих случаях передачи ведёт один `DownloadCoordinator`: не больше трёх
  одновременно, четвёртая ждёт. Пауза обрывает HTTP-запрос и оставляет
  `.part`; продолжение шлёт `Range: bytes=<размер .part>-` (у YouTube —
  кусками по 10 МБ). Точный размер дорожки сверяется с ответом: файл другого
  размера к `.part` не дописывается. Протухший адрес (401/403/404/410)
  заменяется новым для того же format ID, `.part` остаётся; если вариант
  пропал у источника, задание останавливается с причиной.
- Разбор страницы («Найти видео») не занимает слот передачи и идёт
  параллельно загрузкам.

## Что поддержано

Вставка одной или сразу нескольких ссылок YouTube, VK Video и других сайтов
(и «Поделиться» в NOX), «Найти видео» → карточка → настоящие варианты
качества этого видео (включая 1440p и выше) → «Скачать», очередь с MAX 3, фоновая загрузка с уведомлением,
пауза/продолжение/отмена по одной и для всех, восстановление после гибели
процесса и после обновления, видео + звук автоматически (объединение
MediaMuxer без перекодирования, проверка файла перед «Готово»), медиатека с поиском, сортировкой и
фильтрами, переименование, экспорт, «Поделиться», импорт своих видео,
выбор папки для готовых видео и перенос уже скачанных, резервная копия,
плеер Media3 с «картинкой в картинке», скоростью, двойным касанием и
выбором дорожек, темы и свой фон, обновления внутри приложения.
