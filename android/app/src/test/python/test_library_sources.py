# coding: utf-8
"""0.4.0: субтитры, главы и плейлисты источника (без сети, синтетические словари yt-dlp)."""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'main', 'python'))

import nox_catalog  # noqa: E402


def track(ext, name='', url='https://www.youtube.com/api/timedtext?v=x&lang=en&fmt='):
    return {'ext': ext, 'url': url + ext, 'name': name}


class SubtitlesTest(unittest.TestCase):
    def test_author_tracks_and_only_original_auto(self):
        e = {
            'subtitles': {
                'ru': [track('json3'), track('vtt', 'Русский')],
                'en-US': [track('srt', 'English')],
                'live_chat': [track('json')],
                'de': [track('ttml')],          # формат, который NOX не читает
            },
            'automatic_captions': {
                'en-orig': [track('vtt', 'English (Original)')],
                'en': [track('vtt', 'English')],
                'fr': [track('vtt', 'French from English')],
            },
        }
        subs = nox_catalog.subtitles_of(e)
        self.assertEqual([(s['key'], s['auto'], s['ext']) for s in subs],
                         [('ru', False, 'vtt'), ('en-US', False, 'srt'), ('en-orig', True, 'vtt')])
        self.assertEqual(subs[1]['lang'], 'en')
        self.assertEqual(subs[2]['lang'], 'en')
        # Машинные переводы на другие языки не предлагаются.
        self.assertFalse(any(s['key'] == 'fr' for s in subs))

    def test_single_auto_track_without_orig_suffix(self):
        subs = nox_catalog.subtitles_of({'automatic_captions': {'ru': [track('vtt')]}})
        self.assertEqual([(s['key'], s['auto']) for s in subs], [('ru', True)])

    def test_nothing_when_absent(self):
        self.assertEqual(nox_catalog.subtitles_of({}), [])
        self.assertEqual(nox_catalog.subtitles_of({'subtitles': None, 'automatic_captions': 'x'}), [])

    def test_find_subtitle_prefers_vtt(self):
        e = {'subtitles': {'ru': [track('srt'), track('vtt')]}}
        ext, url = nox_catalog.find_subtitle(e, 'ru', False)
        self.assertEqual(ext, 'vtt')
        self.assertTrue(url.endswith('vtt'))
        self.assertIsNone(nox_catalog.find_subtitle(e, 'en', False))
        self.assertIsNone(nox_catalog.find_subtitle(e, 'ru', True))


class ChaptersTest(unittest.TestCase):
    def test_source_chapters_in_ms_and_invalid_dropped(self):
        e = {'chapters': [
            {'start_time': 0, 'end_time': 90.5, 'title': 'Intro'},
            {'start_time': 90.5, 'end_time': 90.5, 'title': 'empty'},
            {'start_time': 90.5, 'end_time': 90000, 'title': 'Long'},
            'junk',
        ]}
        self.assertEqual(nox_catalog.chapters_of(e), [
            {'title': 'Intro', 'start_ms': 0, 'end_ms': 90500},
            {'title': 'Long', 'start_ms': 90500, 'end_ms': 90000000},
        ])
        self.assertEqual(nox_catalog.chapters_of({}), [])

    def test_details_carry_subtitles_and_chapters(self):
        d = nox_catalog.details_of({'id': 'x', 'title': 't', 'chapters': [{'start_time': 1, 'end_time': 2, 'title': 'a'}]})
        self.assertEqual(d['subtitles'], [])
        self.assertEqual(len(d['chapters']), 1)


class PlaylistEntryTest(unittest.TestCase):
    def test_youtube_flat_entry(self):
        e = {'id': 'abc123', 'url': 'https://www.youtube.com/watch?v=abc123', 'title': 'Серия 1', 'duration': 1440.0,
             'ie_key': 'Youtube', 'channel': 'Канал', 'thumbnails': [{'url': 'https://i.ytimg.com/a.jpg'}]}
        p = nox_catalog.playlist_entry(3, e)
        self.assertEqual(p['index'], 3)
        self.assertEqual(p['url'], 'https://www.youtube.com/watch?v=abc123')
        self.assertEqual(p['duration'], 1440)
        self.assertEqual(p['unavailable'], '')

    def test_id_only_youtube_entry_gets_watch_url(self):
        p = nox_catalog.playlist_entry(1, {'id': 'zzz', 'url': 'zzz', 'ie_key': 'Youtube', 'title': 'x'})
        self.assertEqual(p['url'], 'https://www.youtube.com/watch?v=zzz')

    def test_private_and_deleted_have_reasons(self):
        self.assertEqual(nox_catalog.playlist_entry(1, {'id': 'a', 'title': '[Private video]', 'url': 'https://y/a'})['unavailable'],
                         'Закрытое видео')
        self.assertEqual(nox_catalog.playlist_entry(2, {'id': 'b', 'title': '[Deleted video]', 'url': 'https://y/b'})['unavailable'],
                         'Видео удалено')
        self.assertEqual(nox_catalog.playlist_entry(3, {'id': 'c', 'title': 'ok', 'availability': 'subscriber_only',
                                                         'url': 'https://y/c'})['unavailable'], 'Только для спонсоров канала')
        self.assertEqual(nox_catalog.playlist_entry(4, {'title': 'no link'})['unavailable'], 'Нет ссылки на видео')


class DirectFileTest(unittest.TestCase):
    def test_direct_whole_file_is_one_av_track(self):
        info = {'id': 'clip', 'title': 'clip', 'direct': True, 'extractor_key': 'Generic',
                'formats': [{'format_id': 'mp4', 'url': 'http://10.0.2.2/clip.mp4', 'ext': 'mp4', 'vcodec': None,
                             'protocol': 'http'}]}
        tracks = nox_catalog.tracks_of(info)
        self.assertEqual(len(tracks), 1)
        self.assertEqual(tracks[0]['kind'], 'av')
        self.assertEqual(tracks[0]['vcodec'], '')
        self.assertEqual(tracks[0]['acodec'], '')

    def test_unknown_codecs_without_direct_are_still_skipped(self):
        info = {'id': 'x', 'formats': [{'format_id': 'a', 'url': 'http://x/a.mp4', 'ext': 'mp4', 'protocol': 'http'}]}
        self.assertEqual(nox_catalog.tracks_of(info), [])


if __name__ == '__main__':
    unittest.main()
