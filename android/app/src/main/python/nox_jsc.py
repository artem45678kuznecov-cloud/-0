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

import os
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
    limits_cls = jclass('com.nox.offline.js.JsEngine$Limits')

    def run(script, timeout_ms=None):
        if timeout_ms:
            base = limits_cls()
            limits = limits_cls(base.getMemory(), base.getStack(), int(timeout_ms), base.getOutput())
            return str(engine.run(script, limits))
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
        # Разобранный плеер YouTube кэшируется (см. PlayerCache): первое
        # решение на телефоне занимает секунды, следующие — доли секунды.
        _ENABLE_PREPROCESSED_PLAYER_CACHE = True

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


class PlayerCache(object):
    """
    Кэш разобранного плеера YouTube для EJS: в памяти (2 плеера) и на диске
    во временной папке приложения (не больше DISK_MAX файлов). Ключ включает
    версию yt-dlp-ejs: разбор другой версией не используется. Остальные
    обращения к кэшу yt-dlp передаются исходному (отключённому) кэшу.
    """
    SECTION = 'challenge-solver'
    MEMORY_MAX = 2
    DISK_MAX = 3

    def __init__(self, inner, directory=None):
        import tempfile
        self._inner = inner
        self._dir = directory or os.path.join(tempfile.gettempdir(), 'nox-ejs')
        self._mem = _MEMORY

    def __getattr__(self, name):
        return getattr(self._inner, name)

    @staticmethod
    def _ejs_version():
        try:
            import yt_dlp_ejs
            return str(yt_dlp_ejs.version)
        except Exception:
            return 'none'

    def _path(self, key):
        import hashlib
        h = hashlib.sha1(f'{self._ejs_version()}|{key}'.encode()).hexdigest()[:24]
        return os.path.join(self._dir, f'player-{h}.js')

    def _mine(self, section, key):
        return section == self.SECTION and isinstance(key, str) and key.startswith('player:')

    def load(self, section, key, *args, **kwargs):
        if not self._mine(section, key):
            return self._inner.load(section, key, *args, **kwargs)
        with _cache_lock:
            if key in self._mem:
                self._mem.move_to_end(key)
                return self._mem[key]
            path = self._path(key)
            try:
                with open(path, encoding='utf-8') as f:
                    data = f.read()
                os.utime(path, None)
            except OSError:
                return None
            self._remember(key, data)
            return data

    def store(self, section, key, data, *args, **kwargs):
        if not self._mine(section, key):
            return self._inner.store(section, key, data, *args, **kwargs)
        if not isinstance(data, str) or not data:
            return
        with _cache_lock:
            self._remember(key, data)
            try:
                os.makedirs(self._dir, exist_ok=True)
                path = self._path(key)
                tmp = path + '.tmp'
                with open(tmp, 'w', encoding='utf-8') as f:
                    f.write(data)
                os.replace(tmp, path)
                files = sorted((os.path.join(self._dir, n) for n in os.listdir(self._dir) if n.endswith('.js')),
                               key=os.path.getmtime, reverse=True)
                for old in files[self.DISK_MAX:]:
                    os.remove(old)
            except OSError:
                pass

    def _remember(self, key, data):
        self._mem[key] = data
        self._mem.move_to_end(key)
        while len(self._mem) > self.MEMORY_MAX:
            self._mem.popitem(last=False)


_cache_lock = threading.Lock()
_MEMORY = __import__('collections').OrderedDict()


def install_cache(ydl, directory=None):
    """Подключить кэш разобранного плеера к экземпляру YoutubeDL (память общая на процесс)."""
    ydl.cache = PlayerCache(ydl.cache, directory)
    return ydl


def solve_file(player_path, player_url, n_challenges=(), sig_challenges=(), cache_dir=None, timeout_ms=None):
    """
    Самопроверка встроенного пути: решить задачи n/sig для плеера YouTube,
    сохранённого в файл, тем же провайдером, что работает при разборе.
    Возвращает JSON {"n": {...}, "sig": {...}, "errors": [...], "runs": k}.
    Сети не использует. Нужна инструментальному тесту и CI. timeout_ms —
    только для медленных эмуляторов без аппаратного ускорения.
    """
    import json
    base_runner = _get_runner()
    if timeout_ms and base_runner is not None:
        set_runner(lambda script: base_runner(script, timeout_ms=timeout_ms))
    try:
        return json.dumps(_solve_file(player_path, player_url, n_challenges, sig_challenges, cache_dir), ensure_ascii=False)
    finally:
        if timeout_ms and base_runner is not None:
            set_runner(base_runner)


def _solve_file(player_path, player_url, n_challenges, sig_challenges, cache_dir):
    import resolver
    from yt_dlp import YoutubeDL
    from yt_dlp.extractor.youtube.jsc.provider import (
        JsChallengeRequest, JsChallengeType, NChallengeInput, SigChallengeInput)

    cls = register()
    with open(player_path, encoding='utf-8') as f:
        code = f.read()
    stats = {'runs': 0, 'failed': 0, 'ms': 0}
    opts = resolver.ydl_opts()
    opts['nox_js_stats'] = stats
    ydl = install_cache(YoutubeDL(opts), cache_dir)
    ie = ydl.get_info_extractor('Youtube')
    ie._load_player = lambda *a, **k: code

    class _Log:
        def trace(self, m): pass
        def debug(self, m, *, once=False): pass
        def info(self, m, *, once=False): pass
        def warning(self, m, *, once=False): pass
        def error(self, m): pass

    provider = cls(ie, _Log(), None)
    player_id = player_url.split('/player/', 1)[-1].split('/', 1)[0]
    reqs = []
    if n_challenges:
        reqs.append(JsChallengeRequest(JsChallengeType.N, NChallengeInput(player_url, [str(c) for c in n_challenges]), player_id))
    if sig_challenges:
        reqs.append(JsChallengeRequest(JsChallengeType.SIG, SigChallengeInput(player_url, [str(c) for c in sig_challenges]), player_id))
    out = {'n': {}, 'sig': {}, 'errors': [], 'available': provider.is_available()}
    for r in provider.bulk_solve(reqs):
        if r.response is None:
            out['errors'].append(str(r.error)[:300])
            continue
        key = 'n' if r.request.type is JsChallengeType.N else 'sig'
        out[key].update(r.response.output.results)
    out.update(stats)
    return out
