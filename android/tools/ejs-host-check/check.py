"""
Проверка встроенного пути JS для YouTube на компьютере (CI):
провайдер NOX (nox_jsc.py) + тот же C-код движка решают настоящие задачи
n/sig из набора тестов yt-dlp 2026.08.19. Плеер скачивается с youtube.com.

Запуск: python3 check.py <папка для плееров>
"""
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'python'))

import hostrunner  # noqa: E402
import nox_jsc  # noqa: E402

# Векторы из yt-dlp test/test_jsc/test_ejs_integration.py (Unlicense).
SIG_IN = 'NJAJEij0EwRgIhAI0KExTgjfPk-MPM9MAdzyyPRt=BM8-XO5tm5hlMCSVpAiEAv7eP3CURqZNSPow8BXXAoazVoXgeMP7gH9BdylHCwgw=gwzz'
CASES = [
    ('74edf1a3', 'player_ias.vflset/en_US/base.js',
     {'IlLiA21ny7gqA2m4p37': '9nRTxrbM1f0yHg', 'eabGFpsUKuWHXGh6FR4': 'izmYqDEY6kl7Sg'},
     {SIG_IN: 'NJAJEij0EwRgIhAI0KExTgjfPk-MPM9MAdzyyPRt=BM8-XO5tm5hzMCSVpAiEAv7eP3CURqZNSPow8BXXAoazVoXgeMP7gH9BdylHCwgw=gwzl'}),
    ('901741ab', 'player_ias.vflset/en_US/base.js',
     {'BQoJvGBkC2nj1ZZLK-': 'UMPovvBZRh-sjb'},
     {SIG_IN: 'wgwCHlydB9Hg7PMegXoVzaoAXXB8woPSNZqRUC3Pe7vAEiApVSCMlhwmt5ON-8MB=5RPyyzdAM9MPM-kPfjgTxEK0IAhIgRwE0jiEJA'}),
]


def fetch(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 100_000:
        return
    last = None
    for _ in range(3):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            with open(path, 'wb') as f:
                f.write(data)
            return
        except Exception as e:  # сеть CI иногда рвётся — ограниченный повтор
            last = e
    raise SystemExit(f'не удалось скачать плеер {url}: {last}')


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'build')
    os.makedirs(out_dir, exist_ok=True)
    nox_jsc.set_runner(hostrunner.run)
    print('engine', hostrunner.run.version())
    failed = 0
    for player, variant, n_expected, sig_expected in CASES:
        url = f'https://www.youtube.com/s/player/{player}/{variant}'
        path = os.path.join(out_dir, f'player-{player}.js')
        fetch(url, path)
        r = json.loads(nox_jsc.solve_file(path, url, list(n_expected), list(sig_expected)))
        ok = r['n'] == n_expected and r['sig'] == sig_expected and not r['errors'] and r['runs'] == 1
        print(player, 'OK' if ok else 'FAIL', f"{r['ms']} ms", r['errors'])
        failed += 0 if ok else 1
    if failed:
        raise SystemExit(f'{failed} player(s) failed')


if __name__ == '__main__':
    main()
