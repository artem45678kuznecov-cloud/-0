# coding: utf-8
"""
NOX BACKGROUND TEST — поэтапный native probe.

Основной NOX здесь не запускается: ни NoxApp, ни DOWNLOADER, ни STATE,
ни медиатека.

ЧТО ИЗМЕНИЛОСЬ. Прошлая версия завершала Pythonista нативно сразу после
Run, до появления интерфейса. Она сама виновата: BGTestView.__init__
вызывал _restore_on_launch(), а тот ещё ДО present() успевал создать
конфигурацию, ObjC-класс делегата, NSURLSession, ObjCBlock и позвать
getAllTasksWithCompletionHandler:. Любой из этих мостов, убив процесс,
не оставлял человеку ни одного слова на экране.

Теперь при запуске выполняются ТОЛЬКО: импорты, сборка обычного
ui.View, чтение двух маленьких файлов и present(). Ни одного нативного
вызова, даже import objc_util отложен. Всё остальное — по кнопкам,
строго по одной операции за нажатие, и каждая под отметкой в
NOX_Data/nox_bg_boot.json.

Если Pythonista умрёт на каком-то этапе, следующий запуск прочитает
этот файл и назовёт последнюю записанную нативную операцию.

Запускать: открыть NOX_BG_TEST.py в Pythonista и нажать Run.
Во время фонового теста НЕ смахивать Pythonista из App Switcher и НЕ
нажимать Stop — это force quit, а проверяется suspension.
"""

import os
import sys
import time
import traceback

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


def label(text, size=13, color=TEXT, mono=False):
    lb = ui.Label()
    lb.text = text
    lb.font = (MONO if mono else 'HelveticaNeue', size)
    lb.text_color = color
    lb.alignment = ui.ALIGN_LEFT
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
    Технический экран. Никакого дизайна NOX и никаких нативных вызовов
    в __init__: только subviews и чтение двух файлов.
    """

    def __init__(self, *args, **kwargs):
        ui.View.__init__(self, *args, **kwargs)
        self.name = 'NOX BACKGROUND TEST'
        self.background_color = BLACK
        self.alive = True
        self.status = 'Готов. Нативного ничего не выполнялось.'
        self.banner = ''
        self.banner_color = DIM
        self.detail = ''
        self.quality = '480'
        self._last_tasks_time = 0.0
        self._pending_mark = ''          # 'before:A' / 'after:B'
        self._build()
        self._show_previous_boot()

    # =================================================================
    #  СБОРКА
    # =================================================================
    def _build(self):
        self.sv = ui.ScrollView()
        self.sv.background_color = BLACK
        self.add_subview(self.sv)

        self.title_lb = label('NOX BACKGROUND TEST', 20)
        self.title_lb.font = ('HelveticaNeue-Bold', 20)
        self.sv.add_subview(self.title_lb)

        self.warn_lb = label(
            'Не смахивайте Pythonista из App Switcher и не нажимайте Stop: '
            'проверяется suspension, а не force quit.', 11, WARN)
        self.sv.add_subview(self.warn_lb)

        self.boot_lb = label('', 13, BAD)
        self.boot_lb.font = ('HelveticaNeue-Bold', 13)
        self.sv.add_subview(self.boot_lb)

        self.probe_hdr = label('NATIVE PROBE', 15, ACCENT)
        self.probe_hdr.font = ('HelveticaNeue-Bold', 15)
        self.sv.add_subview(self.probe_hdr)

        self.probe_note = label(
            'Каждая кнопка делает ровно свой этап и не запускает следующий. '
            'После 4 подождите 10 секунд, прежде чем жать 5.', 11, DIM)
        self.sv.add_subview(self.probe_note)

        # Восемь этапов строго по порядку.
        self.stage_buttons = []
        for stage, caption in BG.STAGES:
            b = button(caption, self._stage_action(stage))
            b.name = stage
            self.stage_buttons.append((stage, b))
            self.sv.add_subview(b)

        self.url_field = ui.TextField()
        self.url_field.placeholder = 'https://m.vkvideo.ru/video-...  (для этапа 6)'
        self.url_field.background_color = PANEL
        self.url_field.text_color = TEXT
        self.url_field.tint_color = ACCENT
        self.url_field.bordered = False
        self.url_field.corner_radius = 8
        self.url_field.font = (MONO, 12)
        self.url_field.autocapitalization_type = ui.AUTOCAPITALIZE_NONE
        self.url_field.autocorrection_type = False
        self.sv.add_subview(self.url_field)

        self.paste_btn = button('Вставить', self.on_paste)
        self.sv.add_subview(self.paste_btn)

        self.quality_seg = ui.SegmentedControl()
        self.quality_seg.segments = tuple(BG.QUALITIES)
        self.quality_seg.selected_index = 1
        self.quality_seg.tint_color = ACCENT
        self.quality_seg.action = self.on_quality
        self.sv.add_subview(self.quality_seg)

        self.status_lb = label('', 14)
        self.status_lb.font = ('HelveticaNeue-Bold', 14)
        self.sv.add_subview(self.status_lb)

        self.banner_lb = label('', 15, DIM)
        self.banner_lb.font = ('HelveticaNeue-Bold', 15)
        self.sv.add_subview(self.banner_lb)

        self.detail_lb = label('', 11, DIM, mono=True)
        self.sv.add_subview(self.detail_lb)

        self.info_lb = label('', 11, DIM, mono=True)
        self.sv.add_subview(self.info_lb)

        self.test_hdr = label('ТЕСТ ФОНА', 15, OK)
        self.test_hdr.font = ('HelveticaNeue-Bold', 15)
        self.sv.add_subview(self.test_hdr)

        self.test_seg = ui.SegmentedControl()
        self.test_seg.segments = ('TEST A', 'TEST B', 'TEST C')
        self.test_seg.selected_index = 0
        self.test_seg.tint_color = OK
        self.test_seg.action = self.on_test_pick
        self.sv.add_subview(self.test_seg)

        self.extra_buttons = []
        for title, action in (
                ('Восстановить прошлую session', self.on_restore),
                ('Проверить сейчас', self.on_check),
                ('Отметить перед фоном', self.on_mark),
                ('Я вернулся', self.on_return),
                ('Скопировать отчёт', self.on_copy),
                ('Отменить задачу', self.on_cancel),
                ('Сбросить эксперимент', self.on_reset)):
            b = button(title, action)
            self.extra_buttons.append(b)
            self.sv.add_subview(b)

        self.report_tv = ui.TextView()
        self.report_tv.editable = False
        self.report_tv.background_color = PANEL
        self.report_tv.text_color = DIM
        self.report_tv.font = (MONO, 10)
        self.report_tv.corner_radius = 8
        self.sv.add_subview(self.report_tv)

    def layout(self):
        w = self.width
        self.sv.frame = (0, 0, w, self.height)
        pad = 14
        inner = max(120, w - pad * 2)
        y = pad
        for view, h in ((self.title_lb, 26), (self.warn_lb, 34)):
            view.frame = (pad, y, inner, h)
            y += h + 6
        self.boot_lb.frame = (pad, y, inner, 76)
        y += 82
        self.probe_hdr.frame = (pad, y, inner, 20)
        y += 24
        self.probe_note.frame = (pad, y, inner, 34)
        y += 40
        for stage, b in self.stage_buttons:
            b.frame = (pad, y, inner, 36)
            y += 40
        y += 6
        self.url_field.frame = (pad, y, inner - 96, 34)
        self.paste_btn.frame = (pad + inner - 90, y, 90, 34)
        y += 42
        self.quality_seg.frame = (pad, y, inner, 30)
        y += 40
        self.status_lb.frame = (pad, y, inner, 20)
        y += 24
        self.banner_lb.frame = (pad, y, inner, 60)
        y += 64
        self.detail_lb.frame = (pad, y, inner, 150)
        y += 156
        self.info_lb.frame = (pad, y, inner, 120)
        y += 126
        self.test_hdr.frame = (pad, y, inner, 20)
        y += 24
        self.test_seg.frame = (pad, y, inner, 28)
        y += 36
        cols = 2 if inner >= 340 else 1
        bw = (inner - 8 * (cols - 1)) / float(cols)
        for i, b in enumerate(self.extra_buttons):
            b.frame = (pad + (i % cols) * (bw + 8),
                       y + (i // cols) * 40, bw, 34)
        y += ((len(self.extra_buttons) + cols - 1) // cols) * 40 + 8
        self.report_tv.frame = (pad, y, inner, 420)
        y += 420 + pad
        self.sv.content_size = (w, y)

    # =================================================================
    #  СТАРТ: ТОЛЬКО ЧТЕНИЕ ФАЙЛОВ
    # =================================================================
    def _show_previous_boot(self):
        """
        Единственное, что делается при запуске помимо сборки экрана:
        чтение nox_bg_boot.json и nox_bg_state.json. Обычный файловый
        ввод-вывод, ни одного нативного вызова.
        """
        try:
            BG.ensure_dirs()
            boot = BG.read_boot()
            pending = BG.pending_stage(boot)
            st = BG.state()
            self.url_field.text = st.get('page_url') or ''
            try:
                self.quality = st.get('quality') or '480'
                self.quality_seg.selected_index = BG.QUALITIES.index(
                    self.quality)
            except Exception:
                self.quality = '480'
            try:
                self.test_seg.selected_index = ['A', 'B', 'C'].index(
                    st.get('current_test') or 'A')
            except Exception:
                pass
            if pending:
                self.boot_lb.text_color = BAD
                self.boot_lb.text = (
                    'ПРЕДЫДУЩИЙ NATIVE PROBE НЕ ЗАВЕРШИЛСЯ\n\n'
                    'Последняя операция:\n%s\n\n'
                    'Последняя отметка:\nbefore-%s'
                    % (BG.stage_title(pending), pending))
                self.status = 'Предыдущий запуск оборвался на native-этапе'
                self.banner = ('ВОЗМОЖНОЕ НАТИВНОЕ ЗАВЕРШЕНИЕ НА ЭТАПЕ\n%s\n'
                               '(причина не доказана — это последняя '
                               'записанная операция)' % BG.stage_title(pending))
                self.banner_color = BAD
            elif boot:
                self.boot_lb.text_color = DIM
                self.boot_lb.text = ('Прошлый native probe завершился штатно.\n'
                                     'Последняя отметка: %s-%s'
                                     % (boot.get('phase'), boot.get('stage')))
                self.status = 'Готов. Нативного ничего не выполнялось.'
            else:
                self.boot_lb.text_color = DIM
                self.boot_lb.text = ('Boot-маркера нет: native-этапы ещё не '
                                     'запускались.')
            BG.log('test-start', pending_stage=pending or None,
                   env=str(BG.environment()))
        except Exception as e:
            self.boot_lb.text_color = WARN
            self.boot_lb.text = 'Не удалось прочитать boot-файл: %r' % (e,)
        self.refresh()
        self.show_report()

    # =================================================================
    #  ОБЩЕЕ
    # =================================================================
    def say(self, status, banner='', color=DIM, detail=None):
        self.status = status
        self.banner = banner
        self.banner_color = color
        if detail is not None:
            self.detail = detail
        self.refresh()

    def refresh(self):
        try:
            self.status_lb.text = self.status
            self.banner_lb.text = self.banner
            self.banner_lb.text_color = self.banner_color
            self.detail_lb.text = self.detail
            self.info_lb.text = self._info_text()
            self._paint_stage_buttons()
        except Exception:
            pass

    def _paint_stage_buttons(self):
        results = BG.all_results()
        for stage, b in self.stage_buttons:
            res = results.get(stage)
            if not res:
                b.background_color = PANEL
                b.tint_color = TEXT
            elif res.get('ok'):
                b.background_color = '#12331f'
                b.tint_color = OK
            else:
                b.background_color = '#331416'
                b.tint_color = BAD

    def _info_text(self):
        st = BG.state()
        last = BG.last_snapshot()
        got = BG.tasks_snapshot()
        task = BG.known_task()
        received = task['received'] if task else last.get('received') or 0
        expected = (task['expected'] if task and task['expected']
                    else last.get('expected') or st.get('expected_size') or 0)
        pct = BG.pct_of(received, expected)
        rows = [
            'название      %s' % (st.get('title') or '-'),
            'format_id     %s' % (st.get('format_id') or '-'),
            'direct host   %s' % (st.get('direct_host') or '-'),
            'session id    %s' % BG.BG_SESSION_ID,
            'task id       %s' % (task['task_id'] if task
                                  else st.get('task_identifier')),
            'state         %s' % (task['state_name'] if task
                                  else (last.get('stage') or '-')),
            'скачано       %s' % BG.fmt_mb(received),
            'всего         %s' % (BG.fmt_mb(expected) if expected
                                  else 'неизвестно'),
            'процент       %s' % ('%.1f%%' % pct if pct is not None else '-'),
            'срез задач    %s' % ('ждём completion...' if got['pending']
                                  else ('%.0f c назад'
                                        % (time.time() - got['time'])
                                        if got['time'] else 'не запрашивался')),
            'callbacks     %s' % last.get('callbacks'),
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
            self.report_tv.text = 'Отчёт не собрался: %r\n%s' % (
                e, traceback.format_exc())

    def _test_name(self):
        return ['A', 'B', 'C'][self.test_seg.selected_index]

    def _apply(self, stage, res):
        """Единая отрисовка результата этапа: успех или Python-исключение."""
        caption = dict(BG.STAGES).get(stage, BG.stage_title(stage))
        if res.get('ok'):
            self.say('%s — пройден' % caption, 'Этап пройден.', OK,
                     detail=res.get('text') or '')
        else:
            tb = res.get('traceback') or ''
            self.say('%s — ошибка' % caption,
                     'PYTHON EXCEPTION\n%s' % (res.get('text') or
                                               res.get('error') or '?'),
                     BAD,
                     detail=((res.get('text') or '') + ('\n\n' + tb if tb
                                                        else '')))
        self.show_report()

    # =================================================================
    #  ВОСЕМЬ ЭТАПОВ
    # =================================================================
    def _stage_action(self, stage):
        def run(sender):
            try:
                if stage == 'api':
                    res = BG.probe_api()
                elif stage == 'configuration':
                    res = BG.probe_configuration()
                elif stage == 'delegate':
                    res = BG.probe_delegate()
                elif stage == 'session':
                    res = BG.probe_session()
                elif stage == 'get-all-tasks':
                    res = BG.probe_get_all_tasks(on_done=self._tasks_arrived)
                elif stage == 'resolve':
                    BG.state()['page_url'] = (self.url_field.text or '').strip()
                    BG.state()['quality'] = self.quality
                    BG.save_state()
                    res = BG.probe_resolve((self.url_field.text or '').strip(),
                                           self.quality)
                elif stage == 'create-task':
                    res = BG.probe_create_task()
                elif stage == 'resume':
                    res = BG.probe_resume()
                else:
                    res = {'ok': False, 'text': 'неизвестный этап'}
            except Exception as e:
                # Сюда попасть не должно: этапы ловят своё сами. Но если
                # попали — человек всё равно видит тип, repr и traceback.
                res = {'ok': False, 'text': '%s: %r' % (type(e).__name__, e),
                       'error': repr(e), 'traceback': traceback.format_exc()}
            self._apply(stage, res)
        return run

    def _tasks_arrived(self, items, error):
        """
        Completion getAllTasks. Приходит на главную очередь, но интерфейс
        отсюда НЕ трогаем: только доводим до конца отложенный замер.
        Перерисовку сделает такт.
        """
        try:
            if self._pending_mark:
                kind, _, name = self._pending_mark.partition(':')
                self._pending_mark = ''
                if kind == 'before':
                    mark = BG.mark_before(name)
                    self.status = 'Точка зафиксирована (TEST %s)' % name
                    self.banner = ('Точка зафиксирована.\nТеперь сверните '
                                   'Pythonista / заблокируйте экран.\n'
                                   'было %s, state=%s'
                                   % (BG.fmt_mb(mark['bytes_before']),
                                      mark['task_state_before']))
                    self.banner_color = OK
                elif kind == 'after':
                    mark, err = BG.mark_after(name)
                    if err:
                        self.status = 'TEST %s: %s' % (name, err)
                        self.banner = err
                        self.banner_color = WARN
                    else:
                        text = BG.verdict(mark)
                        self.status = 'TEST %s: замер закрыт' % name
                        self.banner = text
                        self.banner_color = (
                            OK if text.startswith('ФОНОВАЯ ПЕРЕДАЧА')
                            else WARN)
        except Exception:
            pass

    # =================================================================
    #  ОСТАЛЬНЫЕ КНОПКИ
    # =================================================================
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

    def on_restore(self, sender):
        """
        Восстановление прошлой сессии — тоже через этапы: сама сессия
        собирается кнопками 1-4, здесь только асинхронный запрос задач.
        Автоматически при запуске это не делается никогда.
        """
        res = BG.probe_restore(on_done=self._tasks_arrived)
        self._apply('restore', res)

    def on_check(self, sender):
        """Асинхронный запрос: ответ придёт сам, интерфейс не блокируется."""
        res = BG.probe_get_all_tasks(on_done=self._tasks_arrived)
        self._apply('get-all-tasks', res)

    def on_mark(self, sender):
        """
        Сначала спрашиваем систему, потом фиксируем точку. Байты обязаны
        прийти от NSURLSessionTask, а не из памяти Python.
        """
        name = self._test_name()
        if BG._SESSION is None:
            self.say('Нет сессии', 'Сначала этапы 1-4.', WARN)
            return
        self._pending_mark = 'before:' + name
        BG.probe_get_all_tasks(on_done=self._tasks_arrived)
        self.say('TEST %s: спрашиваем систему...' % name,
                 'Запрошен срез NSURLSessionTask.\nТочка будет закрыта, '
                 'когда придёт ответ.', DIM)

    def on_return(self, sender):
        name = self._test_name()
        if BG._SESSION is None:
            self.say('Нет сессии', 'Сначала этапы 1-4.', WARN)
            return
        self._pending_mark = 'after:' + name
        BG.probe_get_all_tasks(on_done=self._tasks_arrived)
        self.say('TEST %s: спрашиваем систему...' % name,
                 'Запрошен срез NSURLSessionTask.\nЗамер закроется, '
                 'когда придёт ответ.', DIM)

    def on_copy(self, sender):
        text = BG.build_report()
        self.report_tv.text = text
        if clipboard_set(text):
            self.say(self.status, 'Отчёт скопирован в буфер обмена.', OK)
        else:
            self.say(self.status,
                     'Буфер обмена недоступен, отчёт показан ниже.', WARN)

    def on_cancel(self, sender):
        res = BG.probe_cancel()
        self._apply('cancel', res)

    def on_reset(self, sender):
        """Чистим ТОЛЬКО своё. Основной NOX и NoxMedia не трогаются."""
        try:
            done = BG.reset_experiment(invalidate=True)
        except Exception as e:
            self.say('Сброс не удался', repr(e), BAD,
                     detail=traceback.format_exc())
            return
        self._pending_mark = ''
        self.boot_lb.text_color = DIM
        self.boot_lb.text = 'Boot-маркера нет: эксперимент сброшен.'
        self.say('Эксперимент сброшен',
                 'Состояние очищено. Этапы можно проходить заново.', DIM,
                 detail='\n'.join(done) or 'нечего было удалять')
        self.show_report()

    # =================================================================
    #  ТАКТ
    # =================================================================
    def tick(self):
        """
        Раз в секунду перерисовываем показания. Ни сети, ни ObjC: только
        чтение уже собранных Python-значений.
        """
        if not self.alive:
            return
        try:
            got = BG.tasks_snapshot()
            if got['time'] != self._last_tasks_time:
                self._last_tasks_time = got['time']
                self.show_report()
            self.refresh()
        except Exception:
            pass
        ui.delay(self.tick, 1.0)

    def will_close(self):
        self.alive = False
        try:
            BG.save_state()
        except Exception:
            pass


def main():
    # Здесь не должно быть ни одного нативного вызова: сначала экран.
    BG.ensure_dirs()
    view = BGTestView(frame=(0, 0, 400, 800))
    view.present('fullscreen', hide_title_bar=False)
    view.tick()


if __name__ == '__main__':
    main()
