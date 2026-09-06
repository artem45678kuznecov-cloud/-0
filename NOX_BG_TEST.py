# coding: utf-8
"""
NOX BACKGROUND TEST — отдельная точка входа для эксперимента.

Основной NOX здесь не запускается: ни NoxApp, ни DOWNLOADER, ни STATE,
ни медиатека. Этот скрипт открывает только nox_background.py и свой
технический интерфейс.

Что проверяется: продолжает ли NSURLSessionDownloadTask получать байты,
пока Pythonista свёрнута, экран заблокирован или человек работает в
другом приложении. Ответ даёт не Python, а сама система: перед
сворачиванием и после возвращения счётчик байт спрашивается у живой
NSURLSession.

Запускать так: открыть NOX_BG_TEST.py в Pythonista и нажать Run.
Во время фонового теста НЕ смахивать Pythonista из App Switcher и НЕ
нажимать Stop — это force quit, а проверяется suspension.
"""

import os
import sys
import time

import ui

import nox_background as BG


BLACK = '#0b0d10'
PANEL = '#151a21'
LINE = '#232a34'
TEXT = '#e8edf4'
DIM = '#8b97a8'
OK = '#3ddc84'
WARN = '#ffb020'
BAD = '#ff5a5f'
ACCENT = '#4c8dff'

MONO = 'Menlo'


def clipboard_get():
    try:
        import clipboard
        return clipboard.get() or ''
    except Exception:
        return ''


def clipboard_set(text):
    try:
        import clipboard
        clipboard.set(text)
        return True
    except Exception:
        return False


def label(text, size=13, color=TEXT, mono=False, align=ui.ALIGN_LEFT):
    lb = ui.Label()
    lb.text = text
    lb.font = (MONO if mono else 'HelveticaNeue', size)
    lb.text_color = color
    lb.alignment = align
    lb.number_of_lines = 0
    return lb


def button(title, action, color=PANEL, text_color=TEXT):
    b = ui.Button()
    b.title = title
    b.font = ('HelveticaNeue-Bold', 13)
    b.background_color = color
    b.tint_color = text_color
    b.corner_radius = 8
    b.action = action
    return b


class BGTestView(ui.View):
    """
    Технический интерфейс: ничего общего с дизайном NOX. Один таймер
    ui.delay на обновление показаний — своего цикла передачи здесь нет и
    быть не может, файл качает система.
    """

    def __init__(self, *args, **kwargs):
        ui.View.__init__(self, *args, **kwargs)
        self.name = 'NOX BACKGROUND TEST'
        self.background_color = BLACK
        self.quality = BG.state().get('quality') or '480'
        self.status = 'Готов'
        self.banner = ''
        self.banner_color = DIM
        self.alive = True
        self._build()
        self._restore_on_launch()

    # -- сборка -----------------------------------------------------
    def _build(self):
        self.title_lb = label('NOX BACKGROUND TEST', 20, TEXT)
        self.title_lb.font = ('HelveticaNeue-Bold', 20)
        self.add_subview(self.title_lb)

        self.warn_lb = label(
            'Не смахивайте Pythonista из App Switcher и не нажимайте Stop: '
            'проверяется suspension, а не force quit.', 11, WARN)
        self.add_subview(self.warn_lb)

        self.url_field = ui.TextField()
        self.url_field.placeholder = 'https://m.vkvideo.ru/video-...'
        self.url_field.background_color = PANEL
        self.url_field.text_color = TEXT
        self.url_field.tint_color = ACCENT
        self.url_field.bordered = False
        self.url_field.corner_radius = 8
        self.url_field.font = (MONO, 12)
        self.url_field.autocapitalization_type = ui.AUTOCAPITALIZE_NONE
        self.url_field.autocorrection_type = False
        self.url_field.text = BG.state().get('page_url') or ''
        self.add_subview(self.url_field)

        self.paste_btn = button('Вставить', self.on_paste)
        self.add_subview(self.paste_btn)

        self.quality_seg = ui.SegmentedControl()
        self.quality_seg.segments = tuple(BG.QUALITIES)
        try:
            self.quality_seg.selected_index = BG.QUALITIES.index(self.quality)
        except Exception:
            self.quality_seg.selected_index = 1
        self.quality_seg.tint_color = ACCENT
        self.quality_seg.action = self.on_quality
        self.add_subview(self.quality_seg)

        self.start_btn = button('Начать системную загрузку', self.on_start,
                                ACCENT, '#ffffff')
        self.add_subview(self.start_btn)

        self.status_lb = label('Готов', 14, TEXT)
        self.status_lb.font = ('HelveticaNeue-Bold', 14)
        self.add_subview(self.status_lb)

        self.banner_lb = label('', 15, DIM)
        self.banner_lb.font = ('HelveticaNeue-Bold', 15)
        self.add_subview(self.banner_lb)

        self.info_lb = label('', 11, DIM, mono=True)
        self.add_subview(self.info_lb)

        self.buttons = []
        for title, action in (('Проверить сейчас', self.on_check),
                              ('Отметить перед фоном', self.on_mark),
                              ('Я вернулся', self.on_return),
                              ('Скопировать отчёт', self.on_copy),
                              ('Отменить тест', self.on_cancel),
                              ('Очистить тест', self.on_clear)):
            b = button(title, action)
            self.buttons.append(b)
            self.add_subview(b)

        self.test_seg = ui.SegmentedControl()
        self.test_seg.segments = ('TEST A', 'TEST B', 'TEST C')
        names = ['A', 'B', 'C']
        try:
            self.test_seg.selected_index = names.index(
                BG.state().get('current_test') or 'A')
        except Exception:
            self.test_seg.selected_index = 0
        self.test_seg.tint_color = OK
        self.test_seg.action = self.on_test_pick
        self.add_subview(self.test_seg)

        self.report_tv = ui.TextView()
        self.report_tv.editable = False
        self.report_tv.background_color = PANEL
        self.report_tv.text_color = DIM
        self.report_tv.font = (MONO, 10)
        self.report_tv.corner_radius = 8
        self.add_subview(self.report_tv)

    def layout(self):
        w = self.width
        pad = 14
        inner = w - pad * 2
        y = pad + 4
        self.title_lb.frame = (pad, y, inner, 26)
        y += 30
        self.warn_lb.frame = (pad, y, inner, 32)
        y += 38
        self.url_field.frame = (pad, y, inner - 96, 34)
        self.paste_btn.frame = (pad + inner - 90, y, 90, 34)
        y += 42
        self.quality_seg.frame = (pad, y, inner, 30)
        y += 38
        self.start_btn.frame = (pad, y, inner, 40)
        y += 48
        self.status_lb.frame = (pad, y, inner, 20)
        y += 24
        self.banner_lb.frame = (pad, y, inner, 58)
        y += 62
        self.info_lb.frame = (pad, y, inner, 126)
        y += 132
        self.test_seg.frame = (pad, y, inner, 28)
        y += 36
        cols = 2
        bw = (inner - 8) / float(cols)
        for i, b in enumerate(self.buttons):
            b.frame = (pad + (i % cols) * (bw + 8),
                       y + (i // cols) * 40, bw, 34)
        y += ((len(self.buttons) + cols - 1) // cols) * 40 + 6
        self.report_tv.frame = (pad, y, inner, max(80, self.height - y - pad))

    # -- вспомогательное --------------------------------------------
    def say(self, status, banner='', color=DIM):
        self.status = status
        self.banner = banner
        self.banner_color = color
        self.refresh()

    def refresh(self):
        try:
            self.status_lb.text = self.status
            self.banner_lb.text = self.banner
            self.banner_lb.text_color = self.banner_color
            self.info_lb.text = self._info_text()
        except Exception:
            pass

    def _info_text(self):
        st = BG.state()
        last = BG.last_snapshot()
        snap = getattr(self, '_snap', None)
        received = snap['received'] if snap else last.get('received') or 0
        expected = (snap['expected'] if snap and snap['expected']
                    else last.get('expected') or st.get('expected_size') or 0)
        pct = BG.pct_of(received, expected)
        rows = [
            'название      %s' % (st.get('title') or '-'),
            'format_id     %s' % (st.get('format_id') or '-'),
            'direct host   %s' % (st.get('direct_host') or '-'),
            'session id    %s' % BG.BG_SESSION_ID,
            'task id       %s' % (snap['task_id'] if snap
                                  else st.get('task_identifier')),
            'state         %s' % (snap['state_name'] if snap
                                  else (last.get('stage') or '-')),
            'скачано       %s' % BG.fmt_mb(received),
            'всего         %s' % (BG.fmt_mb(expected) if expected
                                  else 'неизвестно'),
            'процент       %s' % ('%.1f%%' % pct if pct is not None else '-'),
        ]
        if st.get('final_path'):
            rows.append('файл          %s' % os.path.basename(st['final_path']))
        if st.get('last_error'):
            rows.append('ошибка        %s' % st['last_error'])
        return '\n'.join(rows)

    def show_report(self):
        try:
            self.report_tv.text = BG.build_report()
        except Exception as e:
            self.report_tv.text = 'Отчёт не собрался: %r' % (e,)

    def _test_name(self):
        return ['A', 'B', 'C'][self.test_seg.selected_index]

    # -- запуск ------------------------------------------------------
    def _restore_on_launch(self):
        """
        При запуске поднимаем ТУ ЖЕ фоновую сессию и спрашиваем систему,
        какие задачи в ней живы. Дубль не создаётся никогда: если задача
        нашлась, кнопка старта просто не нужна.
        """
        BG.ensure_dirs()
        BG.log('test-start', identifier=BG.BG_SESSION_ID,
               env=str(BG.environment()))
        if not BG.objc_available():
            self.say('BACKGROUND NSURLSESSION НЕДОСТУПНА',
                     'objc_util не импортируется:\n%s'
                     % (BG.OBJC_ERROR or 'модуля нет'), BAD)
            self.show_report()
            return
        sess, err = BG.open_session()
        if sess is None:
            self.say('BACKGROUND NSURLSESSION НЕДОСТУПНА',
                     'backgroundSessionConfigurationWithIdentifier:\n%s'
                     % err, BAD)
            self.show_report()
            return
        snap, err2 = BG.current_task()
        self._snap = snap
        if snap:
            BG.log('task-restored', task_id=snap['task_id'],
                   state=snap['state_name'], received=snap['received'],
                   expected=snap['expected'])
            self.say('Создана background session',
                     'ВОССТАНОВЛЕНА СИСТЕМНАЯ ЗАГРУЗКА\n'
                     'Task #%s   %s / %s'
                     % (snap['task_id'], BG.fmt_mb(snap['received']),
                        BG.fmt_mb(snap['expected'])
                        if snap['expected'] else '?'), OK)
        else:
            self.say('Создана background session',
                     'Системная задача не обнаружена.'
                     + (('\n%s' % err2) if err2 else ''), DIM)
        self.show_report()

    # -- действия ----------------------------------------------------
    def on_paste(self, sender):
        text = clipboard_get().strip()
        if text:
            self.url_field.text = text
            self.say('Ссылка вставлена')
        else:
            self.say('В буфере обмена пусто')

    def on_quality(self, sender):
        try:
            self.quality = BG.QUALITIES[sender.selected_index]
        except Exception:
            self.quality = '480'
        BG.state()['quality'] = self.quality
        BG.save_state()

    def on_test_pick(self, sender):
        BG.state()['current_test'] = self._test_name()
        BG.save_state()
        self.show_report()

    def on_start(self, sender):
        if not BG.objc_available():
            self.say('BACKGROUND NSURLSESSION НЕДОСТУПНА',
                     'objc_util не импортируется:\n%s'
                     % (BG.OBJC_ERROR or 'модуля нет'), BAD)
            return
        snap, _ = BG.current_task()
        if snap and snap.get('state_name') in ('running', 'suspended'):
            # Дубль системной задачи не создаём никогда.
            self._snap = snap
            self.say('Скачивается',
                     'Задача уже существует: Task #%s.\n'
                     'Сначала «Отменить тест».' % snap['task_id'], WARN)
            return
        url = (self.url_field.text or '').strip()
        self.say('Разбор ссылки', 'yt-dlp: один extract_info(download=False)',
                 DIM)
        data, err = BG.resolve(url, self.quality)
        if err:
            self.say('Ошибка', err, BAD)
            self.show_report()
            return
        self.say('Создана background session',
                 '%s / %s' % (data['title'], data['format_id']), DIM)
        tid, err = BG.start_download(data)
        if err:
            self.say('Ошибка', 'Системная задача не создана:\n%s' % err, BAD)
            self.show_report()
            return
        self._snap = None
        self.say('Создан NSURLSessionDownloadTask',
                 'Task #%s запущен.\nДальше файл качает iOS.' % tid, OK)
        self.show_report()

    def on_check(self, sender):
        """Источник правды — живая NSURLSessionTask, а не память Python."""
        snap, err = BG.current_task()
        self._snap = snap
        if snap is None:
            final = BG.state().get('final_path') or ''
            if final and os.path.exists(final):
                self.say('Завершена',
                         'Файл на диске: %s\n%s'
                         % (os.path.basename(final),
                            BG.fmt_mb(BG._size_of(final))), OK)
            else:
                self.say('Системная задача не обнаружена',
                         err or 'В сессии нет задач.', WARN)
            self.show_report()
            return
        pct = BG.pct_of(snap['received'], snap['expected'])
        stage = {'running': 'Скачивается',
                 'suspended': 'Приостановлена системой',
                 'canceling': 'Отменяется',
                 'completed': 'Завершена'}.get(snap['state_name'],
                                               snap['state_name'])
        self.say(stage,
                 'Task #%s  %s\n%s / %s%s'
                 % (snap['task_id'], snap['state_name'],
                    BG.fmt_mb(snap['received']),
                    BG.fmt_mb(snap['expected']) if snap['expected'] else '?',
                    ('   %.1f%%' % pct) if pct is not None else ''),
                 OK if snap['state_name'] == 'running' else WARN)
        self.show_report()

    def on_mark(self, sender):
        name = self._test_name()
        mark = BG.mark_before(name)
        self._snap, _ = BG.current_task()
        self.say('Точка зафиксирована (TEST %s)' % name,
                 'Точка зафиксирована.\nТеперь сверните Pythonista / '
                 'заблокируйте экран.\nбыло %s, state=%s'
                 % (BG.fmt_mb(mark['bytes_before']),
                    mark['task_state_before']), OK)
        self.show_report()

    def on_return(self, sender):
        name = self._test_name()
        mark, err = BG.mark_after(name)
        self._snap, _ = BG.current_task()
        if err:
            self.say('Ошибка', err, WARN)
            return
        text = BG.verdict(mark)
        good = text.startswith('ФОНОВАЯ ПЕРЕДАЧА ПОДТВЕРЖДЕНА')
        self.say('TEST %s: замер закрыт' % name, text, OK if good else WARN)
        self.show_report()

    def on_copy(self, sender):
        text = BG.build_report()
        self.report_tv.text = text
        if clipboard_set(text):
            self.say(self.status, 'Отчёт скопирован в буфер обмена.', OK)
        else:
            self.say(self.status, 'Буфер обмена недоступен, '
                                  'отчёт показан ниже.', WARN)

    def on_cancel(self, sender):
        n, err = BG.cancel_all()
        self._snap = None
        self.say('Тест отменён',
                 'Отменено задач: %s%s' % (n, ('\n%s' % err) if err else ''),
                 WARN)
        self.show_report()

    def on_clear(self, sender):
        """Чистим ТОЛЬКО своё: файлы эксперимента, ничего из NOX."""
        BG.cancel_all()
        try:
            for name in os.listdir(BG.BG_MEDIA_DIR):
                if name.startswith('BG_TEST_'):
                    os.remove(os.path.join(BG.BG_MEDIA_DIR, name))
        except Exception:
            pass
        BG.reset_state()
        self._snap = None
        self.say('Готов', 'Состояние эксперимента очищено.', DIM)
        self.show_report()

    # -- редкий опрос показаний -------------------------------------
    def tick(self):
        """
        Раз в две секунды перерисовываем цифры делегата. Это не передача
        файла и не опрос сети: сам байтовый поток идёт мимо Python.
        """
        if not self.alive:
            return
        try:
            self.refresh()
        except Exception:
            pass
        ui.delay(self.tick, 2.0)

    def will_close(self):
        self.alive = False
        BG.save_state()


def main():
    BG.ensure_dirs()
    view = BGTestView(frame=(0, 0, 400, 800))
    view.present('fullscreen', hide_title_bar=False)
    view.tick()


if __name__ == '__main__':
    main()
