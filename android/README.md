# NOX Android

Нативная Android-версия NOX — офлайн-медиатеки для VK Video. Отдельный
проект в каталоге `android/`; iPhone/Pythonista-версия в корне репозитория
не затрагивается и живёт независимо.

Текущая версия — 0.2.0 (ветка `android-stage-02-complete`).

## Что внутри

- Kotlin, Jetpack Compose. Своя дизайн-система «жидкого стекла» в
  `ui/glass` и `ui/components`: GlassSurface, GlassButton, GlassBottomBar,
  QualitySelector, DownloadCard, MediaCard, ContinueWatchingCard, листы
  NoxBottomSheet. Темы и фон — `settings/AppSettings.kt`, `ui/theme`.
- Room — задания, медиатека, позиции просмотра. Единственный источник
  правды: после гибели процесса всё восстанавливается из базы. Схемы
  лежат в `app/schemas`, миграция 1→2 проверяется тестом на заполненной
  базе 0.1.0; разрушительного fallback нет.
- Обновления внутри приложения — `updates/`: манифест `nox-update.json`
  из последнего GitHub Release, проверка файла и подписи, PackageInstaller.
- Папка пользователя (SAF), экспорт, импорт, резервная копия — `storage/`.
- OkHttp — передача файла потоком на диск, Range/resume, `.part`.
- Chaquopy + yt-dlp — только разбор ссылки (`app/src/main/python/resolver.py`).
  В передаче файла Python не участвует.
- Media3 ExoPlayer — плеер локальных MP4 без сети.
- Package id: `com.nox.offline`. minSdk 26 (Android 8.0), targetSdk 35.

## Как собрать

Нужны JDK 17, Android SDK (platform 35, build-tools 35.0.0) и Python 3.12
на машине сборки (его использует Chaquopy для установки yt-dlp).

```
cd android
./gradlew testDebugUnitTest # JVM-тесты (в т.ч. миграция Room)
./gradlew lintDebug
./gradlew assembleRelease   # APK: app/build/outputs/apk/release/
python3 -m unittest discover -s app/src/test/python -v   # тесты резолвера
```

Подпись релиза читается из переменных окружения `NOX_KEYSTORE_PATH`,
`NOX_KEYSTORE_PASSWORD`, `NOX_KEY_ALIAS`, `NOX_KEY_PASSWORD`. Без них
релизная сборка не подписывается, а с `-Pnox.requireReleaseKey=true`
(так делает CI) не собирается вовсе. Подробности — `signing/README.md`.

## Где APK

GitHub Actions (`.github/workflows/android-apk.yml`) на каждый push в ветку
`android-stage-02-complete` прогоняет тесты, lint и сборку. Если тега
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
  Media/       готовые видео  <название> [<id>].mp4
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
  `.part`; продолжение шлёт `Range: bytes=<размер .part>-`. Протухший прямой
  адрес (401/403/404/410) заменяется новым через yt-dlp, `.part` остаётся.

## Что поддержано

Вставка одной или сразу нескольких ссылок VK Video (и «Поделиться» в NOX),
качество 360/480/720/MAX, очередь с MAX 3, фоновая загрузка с уведомлением,
пауза/продолжение/отмена по одной и для всех, восстановление после гибели
процесса и после обновления, раздельные дорожки по желанию (склейка
MediaMuxer без перекодирования), медиатека с поиском, сортировкой и
фильтрами, переименование, экспорт, «Поделиться», импорт своих видео,
выбор папки для готовых видео и перенос уже скачанных, резервная копия,
плеер Media3 с «картинкой в картинке», скоростью, двойным касанием и
выбором дорожек, темы и свой фон, обновления внутри приложения.
