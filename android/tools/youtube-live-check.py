"""
Живая проверка YouTube → 1440p (для CI, на компьютере-раннере).

Тот же путь, что в APK, кроме передачи байтов: resolver.analyze (один
extract_info, встроенный JS-движок NOX через nox_jsc) → выбор 1440p
VP9 + Opus → resolver.plan (адреса ровно этих format ID) → скачивание
кусками по Range с проверкой точного размера. Склейку и проверку файла
делает инструментальный тест на эмуляторе (RealYoutubeMergeDeviceTest).

YouTube может отказать сетям облачных раннеров («подтвердите, что вы не
бот», 403, 429) — тогда скрипт честно завершается с кодом 3 и понятным
сообщением, без повторов по кругу.

Запуск: python3 youtube-live-check.py <папка> [ссылка]
"""
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, 'ejs-host-check'))
sys.path.insert(0, os.path.join(HERE, '..', 'app', 'src', 'main', 'python'))

import hostrunner  # noqa: E402
import nox_jsc  # noqa: E402
import resolver  # noqa: E402

DEFAULT_URL = 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'   # Big Buck Bunny 60fps (Blender Foundation), есть 1440p
DEADLINE = time.time() + 12 * 60
CHUNK = 10 * 1024 * 1024


def refuse(msg):
    print('LIVE-CHECK: NOT CONFIRMED —', msg)
    sys.exit(3)


def pick(tracks):
    vids = [t for t in tracks if t['kind'] == 'video' and t['transport'] == 'http' and t['vcodec'] == 'vp9'
            and t['container'] == 'webm' and min(t['width'], t['height']) == 1440 and t['filesize_exact']]
    auds = [t for t in tracks if t['kind'] == 'audio' and t['transport'] == 'http' and t['acodec'] == 'opus'
            and t['container'] == 'webm' and not t['drc'] and t['filesize_exact']]
    if not vids or not auds:
        return None, None
    v = max(vids, key=lambda t: (t['fps'], t['filesize']))
    a = max(auds, key=lambda t: (t['audio_role'] == 'original', t['language_preference'], t['abr']))
    return v, a


def download(comp, path):
    total = comp['filesize']
    done = os.path.getsize(path) if os.path.exists(path) else 0
    with open(path, 'ab') as f:
        while done < total:
            if time.time() > DEADLINE:
                refuse('не уложились в предел времени скачивания')
            end = min(done + CHUNK, total) - 1
            req = urllib.request.Request(comp['url'], headers={**comp['headers'], 'Range': f'bytes={done}-{end}',
                                                               'Accept-Encoding': 'identity'})
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    cr = r.headers.get('Content-Range', '')
                    if r.status != 206 or not cr.startswith(f'bytes {done}-') or not cr.endswith(f'/{total}'):
                        refuse(f'неожиданный ответ {r.status} {cr!r}')
                    data = r.read()
            except urllib.error.HTTPError as e:
                refuse(f'HTTP {e.code} при скачивании {comp["format_id"]}')
            except Exception as e:
                refuse(f'сеть: {type(e).__name__}: {str(e)[:120]}')
            if len(data) != end - done + 1:
                refuse(f'кусок {done}-{end} пришёл не целиком ({len(data)})')
            f.write(data)
            done += len(data)
    if os.path.getsize(path) != total:
        refuse('размер файла не совпал с точным размером источника')


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'build-live')
    url = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_URL
    os.makedirs(out, exist_ok=True)
    nox_jsc.set_runner(hostrunner.run)
    t0 = time.time()
    a = json.loads(resolver.analyze(url))
    if not a.get('ok'):
        refuse(f"анализ: {a.get('kind')}: {a.get('detail', '')[:200]}")
    d, tracks, js = a['details'], a['tracks'], a['js']
    tiers = sorted({min(t['width'], t['height']) for t in tracks if t['kind'] == 'video' and t['width']}, reverse=True)
    print(f"analyze {time.time() - t0:.1f}s: {d['extractor']} {d['video_id']} «{d['title']}» tracks={len(tracks)} "
          f"tiers={tiers} js runs={js.get('runs')} failed={js.get('failed')} ms={js.get('ms')} engine={js.get('engine')}")
    v, au = pick(tracks)
    if v is None:
        refuse('у видео не нашлось 1440p VP9 + Opus по HTTP')
    p = json.loads(resolver.plan(url, v['id'], au['id']))
    if not p.get('ok'):
        refuse(f"план: {p.get('kind')}: {p.get('detail', p.get('error', ''))[:200]}")
    print('plan: video', v['id'], f"{v['width']}x{v['height']}@{v['fps']}", v['filesize'], '| audio', au['id'], au['filesize'],
          '| cached', p['cached'])
    t1 = time.time()
    download(p['video'], os.path.join(out, 'video.webm'))
    download(p['audio'], os.path.join(out, 'audio.webm'))
    meta = {
        'url': url, 'video_id': d['video_id'], 'title': d['title'], 'duration': d['duration'],
        'video_format': v['id'], 'audio_format': au['id'], 'width': v['width'], 'height': v['height'], 'fps': v['fps'],
        'video_bytes': v['filesize'], 'audio_bytes': au['filesize'], 'js': js,
        'download_sec': round(time.time() - t1, 1),
    }
    with open(os.path.join(out, 'meta.json'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    print('LIVE-CHECK: DOWNLOADED', json.dumps(meta, ensure_ascii=False))


if __name__ == '__main__':
    main()
