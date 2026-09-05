# coding: utf-8
"""
NOX / ядро данных.

Здесь живёт то, что нужно всем подсистемам сразу: пути проекта,
state.json, медиатека и общие форматтеры. Модуль намеренно НЕ импортирует
ui и console на верхнем уровне — благодаря этому nox_download может
работать, ничего не зная про интерфейс Pythonista.

Код перенесён из монолитного NOX.py дословно.
"""

import os
import io
import re
import sys
import json
import time
import shutil


# =====================================================================
#  ОБЩИЕ КОНСТАНТЫ
# =====================================================================

APP_NAME = 'NOX'
APP_VERSION = '1.0'

# Досмотрено, если до конца осталось меньше этого; продолжать предлагаем,
# только если посмотрено больше WATCH_MIN_START.
WATCH_DONE_TAIL = 20.0
WATCH_MIN_START = 10.0
HISTORY_LIMIT = 200
# Ключ сохранённой очереди в state.json. Именно v2: в старом ключе 'jobs'
# лежал формат прежней архитектуры, и читать его нельзя.
JOBS_KEY = 'download_jobs_v2'


SIDECAR_EXT = '.nox.json'

SORT_OPTIONS = [
    ('new', 'Сначала новые'),
    ('old', 'Сначала старые'),
    ('title', 'По названию'),
    ('size', 'По размеру'),
    ('duration', 'По длительности'),
]
QUALITY_FILTERS = [
    ('all', 'Все'),
    ('360', '360p'),
    ('480', '480p'),
    ('720', '720p'),
    ('1080', '1080p+'),
    ('max', 'MAX/4K'),
]

VIDEO_EXT   = ('.mp4', '.mov', '.m4v', '.mkv', '.webm')
# В карточки отдаём только JPEG/PNG. WEBP может лежать рядом от прежних
# загрузок — он удаляется вместе с видео, но в UI не передаётся.
IMAGE_EXT   = ('.jpg', '.jpeg', '.png')
IMAGE_EXT_ALL = ('.jpg', '.jpeg', '.png', '.webp')
TEMP_EXT    = ('.part', '.ytdl')

QUALITIES = [
    ('360', '360p', 'низкое'),
    ('480', '480p', 'среднее'),
    ('720', '720p', 'высокое'),
    ('MAX', 'MAX',  'максимум'),
]


# =====================================================================
#  ФОРМАТТЕРЫ
# =====================================================================

def fmt_size(n):
    try:
        n = float(n)
    except Exception:
        return ''
    if n >= 1024.0 ** 3:
        return '%.1f GB' % (n / 1024.0 ** 3)
    if n >= 1024.0 ** 2:
        return '%.0f MB' % (n / 1024.0 ** 2)
    if n >= 1024.0:
        return '%.0f KB' % (n / 1024.0)
    return '%d B' % int(n)


def fmt_duration(sec):
    try:
        sec = int(float(sec))
    except Exception:
        return ''
    if sec <= 0:
        return ''
    h = sec // 3600
    m = (sec % 3600) // 60
    s = sec % 60
    if h:
        return '%d ч %d мин' % (h, m)
    if m:
        return '%d мин' % m
    return '%d с' % s


def fmt_speed(bps):
    """Реальная скорость из progress_hook. Нет данных — пустая строка."""
    try:
        v = float(bps)
    except Exception:
        return ''
    if v <= 0:
        return ''
    if v >= 1024.0 ** 3:
        return '%.1f GB/s' % (v / 1024.0 ** 3)
    if v >= 1024.0 ** 2:
        return '%.1f MB/s' % (v / 1024.0 ** 2)
    if v >= 1024.0:
        return '%.0f KB/s' % (v / 1024.0)
    return '%d B/s' % int(v)


def fmt_eta(seconds):
    """Реальный остаток из progress_hook: 00:38 или 1:02:03."""
    try:
        s = int(float(seconds))
    except Exception:
        return ''
    if s < 0:
        return ''
    h = s // 3600
    m = (s % 3600) // 60
    sec = s % 60
    if h:
        return '%d:%02d:%02d' % (h, m, sec)
    return '%02d:%02d' % (m, sec)


def fmt_clock(seconds):
    """12:03 или 1:14:26 для плашки на обложке. Нет данных — пусто."""
    try:
        s = int(float(seconds))
    except Exception:
        return ''
    if s <= 0:
        return ''
    h = s // 3600
    m = (s % 3600) // 60
    sec = s % 60
    if h:
        return '%d:%02d:%02d' % (h, m, sec)
    return '%02d:%02d' % (m, sec)


def safe_name(text, limit=40):
    text = (text or '').strip()
    if len(text) > limit:
        return text[:limit - 1].rstrip() + '…'
    return text

# =====================================================================
#  СОСТОЯНИЕ / ХРАНИЛИЩЕ
# =====================================================================

def _project_dir():
    """
    Папка проекта = папка, в которой лежит сам NOX.py.

    При открытии NOX.py из «Файлы → На iPhone → NOX» через External Files
    Pythonista это папка NOX, поэтому NoxMedia и NOX_Data оказываются
    ровно рядом со скриптом. Никаких путей AppGroup в коде нет.
    """
    try:
        return os.path.dirname(os.path.abspath(__file__))
    except Exception:
        pass
    try:
        argv0 = sys.argv[0]
        if argv0:
            return os.path.dirname(os.path.abspath(argv0))
    except Exception:
        pass
    return os.path.abspath(os.getcwd())


PROJECT_DIR = _project_dir()
MEDIA_DIR = os.path.join(PROJECT_DIR, 'NoxMedia')
DATA_DIR = os.path.join(PROJECT_DIR, 'NOX_Data')
ICON_PATH = os.path.join(PROJECT_DIR, 'NOX_icon.png')
YT_DLP_DIR = os.path.join(PROJECT_DIR, 'yt_dlp')
STATE_PATH = os.path.join(DATA_DIR, 'state.json')
DEBUG_LOG = os.path.join(DATA_DIR, 'download_debug.txt')
LEGACY_STATE = os.path.join(os.path.expanduser('~/Documents'), '.nox_state.json')

# Показываем пользователю понятный путь, а не контейнер приложения.
MEDIA_LABEL = 'На iPhone / %s / NoxMedia' % os.path.basename(PROJECT_DIR)


def ensure_dirs():
    """Создаёт NoxMedia и NOX_Data рядом с NOX.py. True, если обе на месте."""
    ok = True
    for path in (MEDIA_DIR, DATA_DIR):
        try:
            os.makedirs(path, exist_ok=True)
        except Exception:
            ok = False
        if not os.path.isdir(path):
            ok = False
    return ok


_LAST_ERROR = {'text': '', 'time': 0.0}


def log_debug(text):
    """
    Диагностика уходит в NOX_Data/download_debug.txt, а не в консоль:
    консольный мост Pythonista при открытом fullscreen ui.View лучше
    не трогать вовсе. Вызывается только главным потоком и только на сбое.
    """
    try:
        with io.open(DEBUG_LOG, 'a', encoding='utf-8') as f:
            f.write('%s\t%s\n' % (time.strftime('%Y-%m-%d %H:%M:%S'), text))
    except Exception:
        pass


def run_on_main(func):
    """
    Одноразовый перенос действия на главный поток. Периодического таймера
    не создаёт. Всё, что меняет ui.View, обязано идти через него.

    ui импортируется здесь, а не на уровне модуля: иначе nox_download,
    импортирующий ядро, тянул бы за собой весь интерфейс Pythonista.
    """
    if not callable(func):
        return
    try:
        import ui
        ui.delay(func, 0)
    except Exception:
        pass


def nox_error(text):
    _LAST_ERROR['text'] = str(text)
    _LAST_ERROR['time'] = time.time()
    try:
        import console
        console.hud_alert(safe_name(str(text), 70), 'error', 1.4)
    except Exception:
        pass


def nox_ok(text):
    try:
        import console
        console.hud_alert(safe_name(str(text), 70), 'success', 1.2)
    except Exception:
        pass


class State(object):
    def __init__(self):
        self.data = {
            'quality': '720',
            'last_opened': None,
            'sort': 'new',
            'quality_filter': 'all',
            'watch_progress': {},
            'download_history': [],
            JOBS_KEY: [],
        }
        self.load()

    def load(self):
        ensure_dirs()
        source = STATE_PATH if os.path.exists(STATE_PATH) else LEGACY_STATE
        try:
            if os.path.exists(source):
                with io.open(source, 'r', encoding='utf-8') as f:
                    raw = json.load(f)
                if isinstance(raw, dict):
                    # Устаревшие ключи прежней архитектуры (a-Shell/Ярлык) и
                    # активные задания в JSON не восстанавливаются.
                    for dead in ('folder', 'bookmark', 'use_shortcut', 'jobs'):
                        raw.pop(dead, None)
                    self.data.update(raw)
        except Exception:
            pass

    def save(self):
        ensure_dirs()
        try:
            tmp = STATE_PATH + '.tmp'
            with io.open(tmp, 'w', encoding='utf-8') as f:
                f.write(json.dumps(self.data, ensure_ascii=False, indent=1))
            if os.path.exists(STATE_PATH):
                os.remove(STATE_PATH)
            os.rename(tmp, STATE_PATH)
        except Exception:
            pass

    # --- удобные свойства ---
    def get(self, key, default=None):
        return self.data.get(key, default)

    def set(self, key, value):
        self.data[key] = value
        self.save()

    @property
    def folder(self):
        """Медиатека всегда одна: PROJECT_DIR/NoxMedia рядом с NOX.py."""
        return MEDIA_DIR

    def ensure_folder(self):
        return ensure_dirs()

    # --- последний открытый файл ---
    def remember_opened(self, path):
        self.data['last_opened'] = {'path': path, 'time': time.time()}
        self.save()

    # --- позиция просмотра -----------------------------------------
    def watch_map(self):
        wp = self.data.get('watch_progress')
        if not isinstance(wp, dict):
            wp = {}
            self.data['watch_progress'] = wp
        return wp

    def watch_get(self, video_id):
        entry = self.watch_map().get(video_id)
        return entry if isinstance(entry, dict) else None

    def watch_set(self, video_id, position, duration=None):
        """
        Сохраняет АБСОЛЮТНУЮ позицию воспроизведения.

        Раньше здесь было old_position + elapsed: к сохранённому числу
        прибавлялось время, которое просмотрщик был открыт. После любой
        перемотки или паузы это переставало быть позицией видео. Теперь
        сюда приходит ровно currentTime AVPlayer, и он ЗАМЕЩАЕТ прежнее
        значение — назад позиция тоже двигается.

        Досмотрено почти до конца — запись удаляется, и в следующий раз
        видео начнётся сначала.
        """
        if not video_id:
            return
        try:
            position = float(position or 0.0)
        except Exception:
            return
        if position < 0:
            position = 0.0
        wp = self.watch_map()
        prev = wp.get(video_id) if isinstance(wp.get(video_id), dict) else {}
        try:
            total = float(duration or prev.get('duration') or 0.0)
        except Exception:
            total = 0.0
        if total > 0:
            position = min(position, total)
            if position >= total - WATCH_DONE_TAIL:
                if wp.pop(video_id, None) is not None:
                    self.save()
                return
        if position <= WATCH_MIN_START:
            # В самом начале продолжать нечего — запись только мешала бы
            # «Продолжить просмотр» на главной.
            if wp.pop(video_id, None) is not None:
                self.save()
            return
        wp[video_id] = {'position': round(position, 1),
                        'duration': round(total, 1) if total else None,
                        'updated_at': time.time()}
        self.save()

    def watch_forget(self, video_id):
        if self.watch_map().pop(video_id, None) is not None:
            self.save()

    # --- история загрузок ------------------------------------------
    def history(self):
        h = self.data.get('download_history')
        if not isinstance(h, list):
            h = []
            self.data['download_history'] = h
        return h

    def add_history(self, entry):
        h = self.history()
        h.append(entry)
        self.data['download_history'] = h[-HISTORY_LIMIT:]
        self.save()

    def last_opened_path(self):
        lo = self.data.get('last_opened')
        if not isinstance(lo, dict):
            return None
        p = lo.get('path')
        if p and os.path.exists(p):
            return p
        return None


STATE = State()

# =====================================================================
#  СКАНИРОВАНИЕ МЕДИАТЕКИ (только реальные файлы)
# =====================================================================

class MediaItem(object):
    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        self.stem, self.ext = os.path.splitext(self.name)
        self.ext = self.ext.lower()
        try:
            st = os.stat(path)
            self.size = st.st_size
            self.mtime = st.st_mtime
        except Exception:
            self.size = 0
            self.mtime = 0.0
        self.info = {}
        self.meta_source = ''
        self.title = self.stem
        self.duration = None
        self.uploader = ''
        self.video_id = ''
        self.webpage_url = ''
        self.height = None
        self.format_id = ''
        self.thumb_path = None
        self._load_info()
        self._find_thumb()

    def _meta_candidates(self):
        """Приоритет: свой .nox.json -> старый .info.json -> имя файла."""
        folder = os.path.dirname(self.path)
        base = re.sub(r'\.f\d+$', '', self.stem)
        stems = [self.stem] if base == self.stem else [self.stem, base]
        out = []
        for suffix in (SIDECAR_EXT, '.info.json'):
            for stem in stems:
                out.append((os.path.join(folder, stem + suffix), suffix))
        return out

    def _load_info(self):
        for path, suffix in self._meta_candidates():
            if not os.path.exists(path):
                continue
            try:
                with io.open(path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
            except Exception:
                continue
            if not isinstance(data, dict):
                continue
            self.info = data
            self.meta_source = suffix
            t = data.get('title')
            if isinstance(t, str) and t.strip():
                self.title = t.strip()
            d = data.get('duration')
            if isinstance(d, (int, float)) and d > 0:
                self.duration = float(d)
            up = data.get('uploader') or data.get('channel') or ''
            if isinstance(up, str):
                self.uploader = up.strip()
            vid = data.get('id')
            if isinstance(vid, str):
                self.video_id = vid
            wu = data.get('webpage_url')
            if isinstance(wu, str):
                self.webpage_url = wu
            h = data.get('height')
            if isinstance(h, (int, float)) and h > 0:
                self.height = int(h)
            fid = data.get('format_id')
            if isinstance(fid, str):
                self.format_id = fid
            break

    def _find_thumb(self):
        folder = os.path.dirname(self.path)
        base = re.sub(r'\.f\d+$', '', self.stem)
        for stem in (self.stem, base):
            for ext in IMAGE_EXT:
                p = os.path.join(folder, stem + ext)
                if os.path.exists(p):
                    self.thumb_path = p
                    return

    def load_thumb_image(self):
        """
        Только данные и только проверенные JPEG/PNG: ui.Image.named для
        произвольного локального файла не используется. Не распозналось —
        None, без исключения наружу.
        """
        if not self.thumb_path:
            return None
        try:
            with io.open(self.thumb_path, 'rb') as f:
                raw = f.read()
        except Exception:
            return None
        if not raw:
            return None
        if not (raw[:8] == b'\x89PNG\r\n\x1a\n' or raw[:3] == b'\xff\xd8\xff'):
            return None
        try:
            import ui
            return ui.Image.from_data(raw)
        except Exception:
            return None

    @property
    def watch_id(self):
        """Стабильный ключ: id из метаданных, иначе имя файла."""
        if self.video_id:
            return self.video_id
        return os.path.basename(self.path)

    @property
    def quality_label(self):
        """Реальное качество: высота из метаданных или format_id вида url480."""
        h = self.height
        if not h and self.format_id:
            m = re.search(r'(\d{3,4})', self.format_id)
            if m:
                try:
                    h = int(m.group(1))
                except Exception:
                    h = None
        if not h:
            return ''
        for step in (2160, 1440, 1080, 720, 480, 360, 240, 144):
            if h >= step:
                return '4K' if step == 2160 else '%dp' % step
        return '%dp' % h

    @property
    def meta_line(self):
        parts = []
        d = fmt_duration(self.duration) if self.duration else ''
        if d:
            parts.append(d)
        q = self.quality_label
        if q:
            parts.append(q)
        if self.size:
            parts.append(fmt_size(self.size))
        return '  •  '.join(parts)

    @property
    def fmt_label(self):
        return self.ext.lstrip('.').upper()

    def sidecar_paths(self):
        """Файлы-спутники ИМЕННО этого видео — для удаления."""
        folder = os.path.dirname(self.path)
        base = re.sub(r'\.f\d+$', '', self.stem)
        stems = {self.stem, base}
        out = []
        for stem in stems:
            out.append(os.path.join(folder, stem + SIDECAR_EXT))
            out.append(os.path.join(folder, stem + '.info.json'))
            for ext in IMAGE_EXT_ALL:
                out.append(os.path.join(folder, stem + ext))
        return out


class TempItem(object):
    """Незавершённая загрузка: .part / .ytdl."""

    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        try:
            self.size = os.path.getsize(path)
            self.mtime = os.path.getmtime(path)
        except Exception:
            self.size = 0
            self.mtime = 0.0
        # "Название [id].mp4.part" -> "Название [id]"
        base = self.name
        for suf in ('.part', '.ytdl'):
            if base.endswith(suf):
                base = base[:-len(suf)]
        base = re.sub(r'-Frag\d+$', '', base)
        self.stem = os.path.splitext(base)[0]
        self.stem = re.sub(r'\.f\d+$', '', self.stem)
        self.title = self.stem
        self.total = None
        self._load_total()

    def _load_total(self):
        folder = os.path.dirname(self.path)
        p = os.path.join(folder, self.stem + '.info.json')
        if not os.path.exists(p):
            return
        try:
            with io.open(p, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except Exception:
            return
        if not isinstance(data, dict):
            return
        t = data.get('title')
        if isinstance(t, str) and t.strip():
            self.title = t.strip()
        total = None
        rd = data.get('requested_downloads')
        if isinstance(rd, list) and rd and isinstance(rd[0], dict):
            total = rd[0].get('filesize') or rd[0].get('filesize_approx')
        if not total:
            total = data.get('filesize') or data.get('filesize_approx')
        if isinstance(total, (int, float)) and total > 0:
            self.total = float(total)

    @property
    def progress(self):
        """Реальный прогресс или None. Никаких выдуманных процентов."""
        if self.total and self.total > 0:
            return max(0.0, min(1.0, self.size / self.total))
        return None


class Library(object):
    def __init__(self, state):
        self.state = state
        self.items = []
        self.temps = []
        self.names = []
        self.total_bytes = 0
        self.error = ''

    def scan(self):
        self.items = []
        self.temps = []
        self.names = []
        self.total_bytes = 0
        self.error = ''
        folder = self.state.folder
        if not os.path.isdir(folder):
            if not self.state.ensure_folder():
                self.error = 'Папка NOX недоступна'
                return
        try:
            names = os.listdir(folder)
        except Exception:
            self.error = 'Папка NOX недоступна'
            return
        self.names = sorted(names)
        for n in self.names:
            if n.startswith('.'):
                continue
            p = os.path.join(folder, n)
            if not os.path.isfile(p):
                continue
            try:
                self.total_bytes += os.path.getsize(p)   # видео + обложки + метаданные
            except Exception:
                pass
            low = n.lower()
            if low.endswith(TEMP_EXT) or '.part' in low:
                self.temps.append(TempItem(p))
                continue
            if low.endswith(VIDEO_EXT):
                self.items.append(MediaItem(p))
        self.items.sort(key=lambda i: i.mtime, reverse=True)
        self.temps.sort(key=lambda i: i.mtime, reverse=True)

    def orphan_temps(self, managed_paths):
        """
        Незавершённые файлы, за которыми НЕ стоит карточка загрузки.

        Сюда не попадают файлы приостановленных и упавших заданий: у них
        уже есть своя карточка с кнопками, и вторая строка «Не завершено»
        для того же файла была бы дублем.
        """
        busy = set()
        for p in managed_paths or ():
            if not p:
                continue
            busy.add(os.path.normpath(p))
            busy.add(os.path.normpath(p + '.part'))
        out = []
        for t in self.temps:
            base = t.path
            for suf in ('.part', '.ytdl'):
                if base.endswith(suf):
                    base = base[:-len(suf)]
            if os.path.normpath(t.path) in busy or os.path.normpath(base) in busy:
                continue
            out.append(t)
        return out

    @staticmethod
    def _quality_bucket(item):
        label = item.quality_label
        if not label:
            return ''
        if label == '4K':
            return 'max'
        try:
            h = int(label.rstrip('p'))
        except Exception:
            return ''
        if h >= 1440:
            return 'max'
        if h >= 1080:
            return '1080'
        for step in (720, 480, 360):
            if h >= step:
                return str(step)
        return ''

    def filtered(self, query, sort=None, quality=None):
        """Локальный поиск по названию, автору и имени файла + фильтр и сортировка."""
        out = list(self.items)
        q = (query or '').strip().lower()
        if q:
            out = [i for i in out
                   if q in (i.title + ' ' + i.name + ' ' + i.uploader).lower()]
        quality = quality or self.state.get('quality_filter', 'all')
        if quality and quality != 'all':
            out = [i for i in out if self._quality_bucket(i) == quality]
        sort = sort or self.state.get('sort', 'new')
        if sort == 'old':
            out.sort(key=lambda i: i.mtime)
        elif sort == 'title':
            out.sort(key=lambda i: i.title.lower())
        elif sort == 'size':
            out.sort(key=lambda i: i.size, reverse=True)
        elif sort == 'duration':
            out.sort(key=lambda i: i.duration or 0.0, reverse=True)
        else:
            out.sort(key=lambda i: i.mtime, reverse=True)
        return out

    def find_by_watch_id(self, watch_id):
        for i in self.items:
            if i.watch_id == watch_id:
                return i
        return None

    def used_bytes(self):
        """Весь объём медиатеки: MP4 + обложки + метаданные + незавершённые."""
        if self.total_bytes:
            return self.total_bytes
        return sum(i.size for i in self.items) + sum(t.size for t in self.temps)

    def disk(self):
        """(total, free) реального тома или (None, None)."""
        try:
            u = shutil.disk_usage(self.state.folder if os.path.isdir(self.state.folder)
                                  else PROJECT_DIR)
            return float(u.total), float(u.free)
        except Exception:
            return None, None


LIB = Library(STATE)
