#!/usr/bin/env bash
# Собрать движок NOX (тот же C-код, что в APK) для компьютера и проверить
# им настоящие задачи YouTube через yt-dlp 2026.8.19 + yt-dlp-ejs 0.8.0.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../../app/src/main/cpp"
mkdir -p "$HERE/build"
gcc -O2 -shared -fPIC -D_GNU_SOURCE -I"$CPP" -I"$CPP/quickjs-ng" "$HERE/shim.c" "$CPP/nox_js.c" \
    "$CPP"/quickjs-ng/{quickjs,libregexp,libunicode,dtoa}.c -lm -lpthread -o "$HERE/build/libnoxjs_host.so"
python3 -m venv "$HERE/build/venv"
"$HERE/build/venv/bin/pip" install -q "yt-dlp==2026.8.19" "yt-dlp-ejs==0.8.0"
"$HERE/build/venv/bin/python" "$HERE/check.py" "${1:-$HERE/build}"
