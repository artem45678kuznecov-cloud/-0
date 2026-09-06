# coding: utf-8
"""
NOX RESUME DATA TEST — отдельная точка входа для probe.

Рабочий NOX здесь не запускается и этим файлом не пользуется. Проверяется
ровно один вопрос: можно ли снять нативную задачу с сессии, НЕ потеряв
уже скачанные байты.

Сейчас в рабочем NOX это невозможно. Приостановленную задачу отпускают
через task.cancel(), а он уничтожает временный файл системы: всё, что
задача скачала и что ещё не попало в .part, пропадает. Цена записана в
журнал до байта, но она есть.

Спасти эти байты может только resumeData, и к ней ведут две дороги:

  4a. обычный cancel() и resumeData из NSError.userInfo — БЕЗ единого
      Python-блока, в том самом делегате, который на устройстве уже
      работает;
  4b. cancelByProducingResumeData: — принимает Python-блок, а на
      Python-блоке процесс уже один раз умер нативно.

Если сработает 4a, рабочая пауза станет полностью бесплатной. Поэтому
кнопки идут именно в таком порядке: сначала безопасная дорога.

Запуск: открыть NOX_RESUME_DATA_TEST.py в Pythonista и нажать Run.
При запуске НИЧЕГО нативного не выполняется — сначала экран.
Во время скачивания не смахивайте Pythonista из App Switcher.
"""

import os
import sys
import time
import traceback

import ui

import nox_resume_data_probe as P


BLACK = '#0b0d10'
PANEL = '#151a21'
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


def button(title, action, color=PANEL, tint=TEXT):
    b = ui.Button()
    b.title = title
    b.font = ('HelveticaNeue-Bold', 13)
    b.background_color = color
    b.tint_color = tint
    b.corner_radius = 8
    b.action = action
    return b


class ResumeProbeView(ui.View):
    """Никаких нативных вызовов в __init__: только subviews и два файла."""

    def __init__(self, *args, **kwargs):
        ui.View.__init__(self, *args, **kwargs)
        self.name = 'NOX RESUME DATA PROBE'
        self.background_color = BLACK
        self.alive = True
        self.status = 'Готов. Нативного ничего не выполнялось.'
        self.banner = ''
        self.banner_color = DIM
        self.detail = ''
        self._build()
        self._show_previous_boot()

    def _build(self):
        self.sv = ui.ScrollView()
        self.sv.background_color = BLACK
        self.add_subview(self.sv)

        self.title_lb = label('NOX RESUME DATA PROBE', 20)
        self.title_lb.font = ('HelveticaNeue-Bold', 20)
        self.sv.add_subview(self.title_lb)

        self.warn_lb = label(
            'Проверяем, можно ли снять задачу с сессии, не потеряв '
            'скачанное. Не смахивайте Pythonista из App Switcher.', 11, WARN)
        self.sv.add_subview(self.warn_lb)

        self.boot_lb = label('', 13, BAD)
        self.boot_lb.font = ('HelveticaNeue-Bold', 13)
        self.sv.add_subview(self.boot_lb)

        self.url_field = ui.TextField()
        self.url_field.placeholder = 'Прямой https-адрес файла (для этапа 3)'
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

        self.stage_buttons = []
        for stage, caption in P.STAGES:
            b = button(caption, self._stage_action(stage))
            b.name = stage
            self.stage_buttons.append((stage, b))
            self.sv.add_subview(b)

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

        self.extra_buttons = []
        for title, action in (('Скопировать отчёт', self.on_copy),
                              ('Сбросить probe', self.on_reset)):
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
        self.title_lb.frame = (pad, y, inner, 26); y += 32
        self.warn_lb.frame = (pad, y, inner, 34); y += 40
        self.boot_lb.frame = (pad, y, inner, 76); y += 82
        self.url_field.frame = (pad, y, inner - 96, 34)
        self.paste_btn.frame = (pad + inner - 90, y, 90, 34); y += 44
        for stage, b in self.stage_buttons:
            b.frame = (pad, y, inner, 36); y += 40
        y += 6
        self.status_lb.frame = (pad, y, inner, 20); y += 24
        self.banner_lb.frame = (pad, y, inner, 76); y += 80
        self.detail_lb.frame = (pad, y, inner, 140); y += 146
        self.info_lb.frame = (pad, y, inner, 130); y += 136
        cols = 2 if inner >= 340 else 1
        bw = (inner - 8 * (cols - 1)) / float(cols)
        for i, b in enumerate(self.extra_buttons):
            b.frame = (pad + (i % cols) * (bw + 8),
                       y + (i // cols) * 40, bw, 34)
        y += ((len(self.extra_buttons) + cols - 1) // cols) * 40 + 8
        self.report_tv.frame = (pad, y, inner, 380)
        y += 380 + pad
        self.sv.content_size = (w, y)

    # -- старт: только чтение файлов -------------------------------
    def _show_previous_boot(self):
        try:
            P.ensure_dirs()
            boot = P.read_boot()
            pending = P.pending_stage(boot)
            if pending:
                self.boot_lb.text_color = BAD
                self.boot_lb.text = (
                    'ПРЕДЫДУЩИЙ PROBE НЕ ЗАВЕРШИЛСЯ\n\n'
                    'Последняя операция:\n%s\n\nОтметка: before-%s'
                    % (P.stage_title(pending), pending))
                self.status = 'Предыдущий запуск оборвался на native-этапе'
                self.banner = ('ВОЗМОЖНОЕ НАТИВНОЕ ЗАВЕРШЕНИЕ НА ЭТАПЕ\n%s\n'
                               '(причина не доказана — это последняя '
                               'записанная операция)' % P.stage_title(pending))
                self.banner_color = BAD
            elif boot:
                self.boot_lb.text_color = DIM
                self.boot_lb.text = ('Прошлый probe завершился штатно.\n'
                                     'Последняя отметка: %s-%s'
                                     % (boot.get('phase'), boot.get('stage')))
            else:
                self.boot_lb.text_color = DIM
                self.boot_lb.text = 'Boot-маркера нет: probe ещё не запускался.'
            P.log('probe-start', pending_stage=pending or None)
        except Exception as e:
            self.boot_lb.text_color = WARN
            self.boot_lb.text = 'Не удалось прочитать boot-файл: %r' % (e,)
        self.refresh()
        self.show_report()

    # -- общее ------------------------------------------------------
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
            for stage, b in self.stage_buttons:
                res = P.stage_result(stage)
                if not res:
                    b.background_color, b.tint_color = PANEL, TEXT
                elif res.get('ok'):
                    b.background_color, b.tint_color = '#12331f', OK
                else:
                    b.background_color, b.tint_color = '#331416', BAD
        except Exception:
            pass

    def _info_text(self):
        st = P.snapshot()
        rows = [
            'получено      %s' % P.fmt_mb(st['received']),
            'всего         %s' % (P.fmt_mb(st['expected'])
                                  if st['expected'] else 'неизвестно'),
            'resume data   %s' % (
                '%s (%s)' % (P.fmt_mb(st['resume_bytes']),
                             st['resume_source'] or '?')
                if st['resume_bytes'] else 'НЕТ'),
            'после resume  %s' % P.fmt_mb(st['restored_received']),
            'callbacks     %s' % st['callbacks'],
        ]
        if st['final_path']:
            rows.append('файл          %s' % os.path.basename(st['final_path']))
        if st['error']:
            rows.append('ошибка        %s' % st['error'])
        return '\n'.join(rows)

    def show_report(self):
        try:
            self.report_tv.text = P.build_report()
        except Exception as e:
            self.report_tv.text = 'Отчёт не собрался: %r' % (e,)

    def _stage_action(self, stage):
        def run(sender):
            try:
                if stage == 'api':
                    res = P.probe_api()
                elif stage == 'session':
                    res = P.probe_session()
                elif stage == 'task':
                    res = P.probe_task((self.url_field.text or '').strip())
                elif stage == 'cancel-error':
                    res = P.probe_cancel_via_error()
                elif stage == 'cancel-block':
                    res = P.probe_cancel_via_block()
                elif stage == 'restore':
                    res = P.probe_restore()
                else:
                    res = {'ok': False, 'text': 'неизвестный этап'}
            except Exception as e:
                res = {'ok': False, 'text': '%s: %r' % (type(e).__name__, e),
                       'error': repr(e), 'traceback': traceback.format_exc()}
            caption = dict(P.STAGES).get(stage, stage)
            if res.get('ok'):
                self.say('%s — пройден' % caption, res.get('text') or '', OK,
                         detail='')
            else:
                tb = res.get('traceback') or ''
                self.say('%s — ошибка' % caption,
                         'PYTHON EXCEPTION\n%s' % (res.get('text')
                                                   or res.get('error') or '?'),
                         BAD, detail=tb)
            self.show_report()
        return run

    def on_paste(self, sender):
        text = clipboard_get().strip()
        if text:
            self.url_field.text = text
            self.say('Ссылка вставлена')
        else:
            self.say('В буфере обмена пусто')

    def on_copy(self, sender):
        text = P.build_report()
        self.report_tv.text = text
        if clipboard_set(text):
            self.say(self.status, 'Отчёт скопирован в буфер обмена.', OK)
        else:
            self.say(self.status, 'Буфер недоступен, отчёт показан ниже.', WARN)

    def on_reset(self, sender):
        try:
            done = P.reset_probe()
        except Exception as e:
            self.say('Сброс не удался', repr(e), BAD,
                     detail=traceback.format_exc())
            return
        self.boot_lb.text_color = DIM
        self.boot_lb.text = 'Boot-маркера нет: probe сброшен.'
        self.say('Probe сброшен', 'Этапы можно проходить заново.', DIM,
                 detail='\n'.join(done) or 'нечего было удалять')
        self.show_report()

    def tick(self):
        if not self.alive:
            return
        try:
            self.refresh()
        except Exception:
            pass
        ui.delay(self.tick, 1.0)

    def will_close(self):
        self.alive = False


def main():
    P.ensure_dirs()
    view = ResumeProbeView(frame=(0, 0, 400, 800))
    view.present('fullscreen', hide_title_bar=False)
    view.tick()


if __name__ == '__main__':
    main()
