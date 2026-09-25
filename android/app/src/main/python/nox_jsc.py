"""Встроенный путь выполнения JS-задач YouTube для NOX.

yt-dlp решает JS-задачи YouTube (n/sig) скриптами EJS (пакет yt-dlp-ejs),
которые нужно выполнить во внешней JS-среде. На Android внешних программ
(deno, node, qjs) нет и не будет: NOX не запускает исполняемые файлы.
Поэтому здесь описан провайдер JS-задач, который отдаёт тот же скрипт EJS
встроенному в APK движку QuickJS-NG (libnoxjs.so, JNI) через функцию-исполнитель.

Исполнитель задаётся set_runner(); по умолчанию используется Kotlin-объект
com.nox.offline.js.JsEngine через Chaquopy. Скрипту движок не даёт ни файлов,
ни сети, ни Android API, а также ограничивает память, время и объём вывода.

Модуль опирается на внутренний EJSBaseJCP yt-dlp, поэтому версии yt-dlp и
yt-dlp-ejs закреплены в build.gradle.kts (см. EXPECTED_YTDLP / EXPECTED_EJS).
"""

from __future__ import annotations

import threading
import time

EXPECTED_YTDLP = '2026.08.19'
EXPECTED_EJS = '0.8.0'
RUNTIME_KEY = 'noxjs'

_runner = None
_runner_lock = threading.Lock()
_registered = False
_provider_cls = None
_last_error = ''


class JsEngineUnavailable(Exception):
    pass


def set_runner(fn):
    """fn(script: str) -> str (stdout) ; должна бросать исключение при ошибке."""
    global _runner
    with _runner_lock:
        _runner = fn


def _android_runner():
    from java import jclass  # Chaquopy
    engine = jclass('com.nox.offline.js.JsEngine').INSTANCE

    def run(script):
        return str(engine.run(script))

    run.version = lambda: str(engine.version())
    return run


def _get_runner():
    global _runner, _last_error
    with _runner_lock:
        if _runner is None:
            try:
                _runner = _android_runner()
            except Exception as e:  # нет Chaquopy/движка
                _last_error = f'{type(e).__name__}: {e}'
                return None
        return _runner


def engine_version():
    r = _get_runner()
    if r is None:
        return ''
    v = getattr(r, 'version', None)
    try:
        return v() if v else 'unknown'
    except Exception as e:
        return f'error: {e}'


def last_error():
    return _last_error


def register():
    """Регистрирует JS-среду и провайдер в yt-dlp. Идемпотентно."""
    global _registered, _provider_cls
    if _registered:
        return _provider_cls
    from yt_dlp.globals import supported_js_runtimes
    from yt_dlp.utils import version_tuple
    from yt_dlp.utils._jsruntime import JsRuntime, JsRuntimeInfo
    from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP
    from yt_dlp.extractor.youtube.jsc.provider import (
        JsChallengeProviderError,
        register_preference,
        register_provider,
    )

    class NoxJsRuntime(JsRuntime):
        def _info(self):
            if _get_runner() is None:
                return None
            ver = engine_version() or 'unknown'
            if ver.startswith('error'):
                return None
            return JsRuntimeInfo(
                name='quickjs-ng', path='embedded:libnoxjs.so', version=ver,
                version_tuple=version_tuple(ver, lenient=True), supported=True)

    supported_js_runtimes.value[RUNTIME_KEY] = NoxJsRuntime

    @register_provider
    class NoxQuickJsJCP(EJSBaseJCP):
        PROVIDER_NAME = 'nox-quickjs'
        PROVIDER_VERSION = '1'
        BUG_REPORT_LOCATION = 'NOX'
        JS_RUNTIME_NAME = RUNTIME_KEY

        def _run_js_runtime(self, stdin, /):
            global _last_error
            runner = _get_runner()
            if runner is None:
                raise JsChallengeProviderError('NOX JS engine is not available')
            stats = self.ie.get_param('nox_js_stats')
            if not isinstance(stats, dict):
                stats = {}
            started = time.monotonic()
            stats['runs'] = stats.get('runs', 0) + 1
            try:
                return runner(stdin)
            except Exception as e:
                stats['failed'] = stats.get('failed', 0) + 1
                _last_error = f'{type(e).__name__}: {str(e)[:300]}'
                raise JsChallengeProviderError(f'NOX JS engine failed: {str(e)[:300]}')
            finally:
                stats['ms'] = stats.get('ms', 0) + int((time.monotonic() - started) * 1000)

    @register_preference(NoxQuickJsJCP)
    def _preference(provider, requests):
        return 1000

    _provider_cls = NoxQuickJsJCP
    _registered = True
    return _provider_cls
