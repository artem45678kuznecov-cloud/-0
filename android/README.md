# NOX Android

Нативная Android-версия NOX — офлайн-медиатеки для VK Video. Отдельный
проект в каталоге `android/`; iPhone/Pythonista-версия в корне репозитория
не затрагивается и живёт независимо.

Stage 01: приложение, APK, фоновая загрузка, медиатека, плеер.

## Что внутри

- Kotlin, Jetpack Compose (Material 3 как каркас, оформление NOX: ночной
  сине-чёрный фон, лавандовые акценты, стеклянные карточки).
- Room — задания, медиатека, позиции просмотра. Единственный источник
  правды: после гибели процесса всё восстанавливается из базы.
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
./gradlew test              # JVM-тесты
./gradlew assembleRelease   # APK: app/build/outputs/apk/release/
python3 -m unittest discover -s app/src/test/python -v   # тесты резолвера
```

Подпись релиза читается из переменных окружения `NOX_KEYSTORE_PATH`,
`NOX_KEYSTORE_PASSWORD`, `NOX_KEY_ALIAS`, `NOX_KEY_PASSWORD`. Без них
релизная сборка не подписывается (для CI ключ создаётся на месте, см. ниже).

## Где APK

GitHub Actions (`.github/workflows/android-apk.yml`) на каждый push в ветку
`android-stage-01` собирает, прогоняет тесты и публикует Release
`android-v0.1.0` с файлом `NOX-Android-v0.1.0.apk`:

https://github.com/artem45678kuznecov-cloud/-0/releases/tag/android-v0.1.0

Стабильный ключ подписи берётся из GitHub Secrets
`NOX_ANDROID_KEYSTORE_BASE64`, `NOX_ANDROID_KEYSTORE_PASSWORD`,
`NOX_ANDROID_KEY_ALIAS`, `NOX_ANDROID_KEY_PASSWORD`. Пока их нет, CI
подписывает временным ключом: такой APK ставится, но следующая сборка
поверх него не встанет.

## Где лежат файлы

App-specific external storage, без «доступа ко всем файлам»:

```
Android/data/com.nox.offline/files/NOX/
  Downloads/   .part незавершённых загрузок
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

## Что поддержано в Stage 01

Вставка ссылки VK Video (и «Поделиться» в NOX), качество 360/480/720/MAX по
лестнице NOX, очередь с MAX 3, фоновая загрузка с уведомлением
(Пауза/Продолжить/Отменить), пауза/продолжение/отмена, восстановление после
гибели процесса, медиатека с обложками, плеер (play/pause, перемотка, время,
полный экран, продолжение с сохранённой позиции), диагностика в настройках.

Не поддержано пока: склейка video+audio (ffmpeg), экспорт в общую папку
(SAF), редактирование названий, настройка пути хранения, повторное
использование одного файла разных качеств.
