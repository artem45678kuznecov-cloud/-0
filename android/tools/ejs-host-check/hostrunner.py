"""Исполнитель для nox_jsc на компьютере: тот же C-код движка (nox_js.c + QuickJS-NG), собранный как .so."""
import ctypes
import os
import time

_lib = ctypes.CDLL(os.environ.get('NOX_JS_HOST_LIB', os.path.join(os.path.dirname(__file__), 'build', 'libnoxjs_host.so')))
_lib.nox_host_run.restype = ctypes.c_int
_lib.nox_host_run.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_size_t, ctypes.c_size_t, ctypes.c_long,
                              ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_size_t),
                              ctypes.POINTER(ctypes.c_void_p)]
_lib.nox_host_version.restype = ctypes.c_char_p
_lib.nox_host_free.argtypes = [ctypes.c_void_p]

# Те же пределы, что у JsEngine.kt на телефоне.
MEMORY = 384 << 20
STACK = 4 << 20
TIMEOUT_MS = 150_000
OUTPUT = 32 << 20
STATS = []


def run(script, mem=MEMORY, stack=STACK, timeout=TIMEOUT_MS, outlim=OUTPUT, timeout_ms=None):
    if timeout_ms:
        timeout = int(timeout_ms)
    b = script.encode('utf-8')
    out, n, err = ctypes.c_void_p(), ctypes.c_size_t(), ctypes.c_void_p()
    t = time.time()
    st = _lib.nox_host_run(b, len(b), mem, stack, timeout, outlim, ctypes.byref(out), ctypes.byref(n), ctypes.byref(err))
    STATS.append((st, round(time.time() - t, 2)))
    try:
        if st != 0:
            e = ctypes.string_at(err.value).decode('utf-8', 'replace') if err.value else ''
            raise RuntimeError(f'status {st}: {e}')
        return ctypes.string_at(out.value, n.value).decode('utf-8') if out.value else ''
    finally:
        if out.value:
            _lib.nox_host_free(out)
        if err.value:
            _lib.nox_host_free(err)


run.version = lambda: _lib.nox_host_version().decode()
