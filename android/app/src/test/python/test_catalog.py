# coding: utf-8
"""
Тесты каталога форматов 0.3.0 (nox_catalog + resolver.analyze/plan).

Сеть не нужна: вместо yt-dlp подставляется функция, отдающая заранее
записанный ответ. fixtures/yt_bbb_visionos.json — настоящий ответ yt-dlp
2026.08.19 для общедоступного ролика YouTube (Big Buck Bunny 60fps 4K),
в котором адреса заменены заглушками. Остальные наборы — синтетические,
по образцу настоящих словарей yt-dlp.
"""

import copy
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'main', 'python'))

import nox_catalog  # noqa: E402
import resolver  # noqa: E402


def load(name):
    with open(os.path.join(HERE, 'fixtures', name), encoding='utf-8') as f:
        return json.load(f)


YT = load('yt_bbb_visionos.json')


def yt_fmt(fid, ext, w, h, fps, vcodec, acodec, size=None, approx=None, proto='https', **extra):
    f = {
        'format_id': fid, 'ext': ext, 'protocol': proto,
        'url': 'https://rr1---sn-x.googlevideo.com/videoplayback?itag=%s&sig=S%%3D1&n=abc' % fid,
        'width': w, 'height': h, 'fps': fps, 'vcodec': vcodec, 'acodec': acodec,
        'http_headers': {'User-Agent': 'UA', 'Accept': '*/*', 'Sec-Fetch-Mode': 'navigate'},
        'downloader_options': {'http_chunk_size': 10485760},
    }
    if size is not None:
        f['filesize'] = size
    if approx is not None:
        f['filesize_approx'] = approx
    f.update(extra)
    return f


SHORTS = {
    'id': 'shortid0001', 'title': 'Вертикальный ролик', 'extractor_key': 'Youtube', 'extractor': 'youtube',
    'channel': 'Канал', 'duration': 42, 'webpage_url': 'https://www.youtube.com/shorts/shortid0001',
    'formats': [
        yt_fmt('137', 'mp4', 1080, 1920, 30, 'avc1.640028', 'none', size=20_000_000),
        yt_fmt('248', 'webm', 1080, 1920, 30, 'vp9', 'none', size=15_000_000),
        yt_fmt('136', 'mp4', 720, 1280, 30, 'avc1.4d401f', 'none', size=9_000_000),
        yt_fmt('140', 'm4a', None, None, None, 'none', 'mp4a.40.2', size=700_000, abr=129.5, asr=44100, audio_channels=2),
        yt_fmt('251', 'webm', None, None, None, 'none', 'opus', size=650_000, abr=120.1, asr=48000, audio_channels=2),
    ],
}

MULTI_LANG = {
    'id': 'dubbed00001', 'title': 'Ролик с дубляжом', 'extractor_key': 'Youtube', 'extractor': 'youtube',
    'duration': 600,
    'formats': [
        yt_fmt('299', 'mp4', 1920, 1080, 60, 'avc1.64002a', 'none', size=300_000_000),
        yt_fmt('303', 'webm', 1920, 1080, 60, 'vp9', 'none', size=200_000_000),
        yt_fmt('298', 'mp4', 1280, 720, 60, 'avc1.4d4020', 'none', approx=150_000_000),
        yt_fmt('302', 'webm', 1280, 720, 30, 'vp9', 'none'),
        yt_fmt('140-0', 'm4a', None, None, None, 'none', 'mp4a.40.2', size=9_000_000, abr=129.0,
               language='en', language_preference=10, format_note='English original (default), medium'),
        yt_fmt('140-1', 'm4a', None, None, None, 'none', 'mp4a.40.2', size=9_100_000, abr=131.0,
               language='ru', language_preference=-1, format_note='Russian, medium'),
        yt_fmt('251-1', 'webm', None, None, None, 'none', 'opus', size=9_500_000, abr=140.0,
               language='ru', language_preference=-1, format_note='Russian, medium'),
        yt_fmt('251-0', 'webm', None, None, None, 'none', 'opus', size=9_400_000, abr=138.0,
               language='en', language_preference=10, format_note='English original (default), medium'),
        yt_fmt('251-drc', 'webm', None, None, None, 'none', 'opus', size=9_400_000, abr=138.0,
               language='en', language_preference=10, format_note='English original (default), medium, DRC'),
    ],
}

VK = {
    'id': '-1_456', 'title': 'VK видео', 'extractor_key': 'VK', 'extractor': 'vk', 'duration': 120,
    'formats': [
        {'format_id': 'hls-1080', 'protocol': 'm3u8_native', 'url': 'https://vkvd.example/video.m3u8?x=1', 'ext': 'mp4',
         'height': 1080, 'vcodec': 'avc1', 'acodec': 'mp4a'},
        {'format_id': 'dash_sep-7', 'protocol': 'http_dash_segments', 'url': 'https://vkvd.example/v.mpd', 'ext': 'mp4',
         'height': 1440, 'vcodec': 'avc1', 'acodec': 'none'},
        {'format_id': 'url720', 'protocol': 'https', 'url': 'https://vkvd.example/720.mp4?sig=1', 'ext': 'mp4'},
        {'format_id': 'url1440', 'protocol': 'https', 'url': 'https://vkvd.example/1440.mp4?sig=1', 'ext': 'mp4'},
        {'format_id': 'url360', 'protocol': 'https', 'url': 'https://vkvd.example/360.mp4?sig=1', 'ext': 'mp4'},
    ],
}


class FakeExtract(object):
    """Подмена resolver._extract: считает вызовы и отдаёт копию ответа."""

    def __init__(self, info=None, error=None):
        self.info = info
        self.error = error
        self.calls = 0

    def __call__(self, url):
        self.calls += 1
        if self.error is not None:
            raise self.error
        return copy.deepcopy(self.info), [], {'runs': 1, 'failed': 0, 'ms': 5}


class Base(unittest.TestCase):
    def setUp(self):
        resolver._cache.clear()
        self._orig = resolver._extract

    def tearDown(self):
        resolver._extract = self._orig
        resolver._cache.clear()
        resolver._cache.clock = __import__('time').time

    def use(self, info=None, error=None):
        fake = FakeExtract(info, error)
        resolver._extract = fake
        return fake


class TrackNormalizationTest(Base):
    def tracks(self, info):
        return {t['id']: t for t in nox_catalog.tracks_of(info)}

    def test_real_youtube_1440_is_present_with_true_dimensions(self):
        t = self.tracks(YT)
        self.assertEqual((t['308']['width'], t['308']['height'], t['308']['fps']), (2560, 1440, 60))
        self.assertEqual(t['308']['vcodec'], 'vp9')
        self.assertEqual(t['308']['container'], 'webm')
        self.assertEqual(t['308']['kind'], 'video')
        self.assertEqual(t['400']['vcodec'], 'av1')
        self.assertEqual(t['400']['container'], 'mp4')

    def test_youtube_dash_named_files_are_plain_http(self):
        t = self.tracks(YT)
        # webm_dash / mp4_dash у YouTube — цельные файлы по HTTPS.
        self.assertEqual(t['308']['transport'], 'http')
        self.assertEqual(t['140']['transport'], 'http')
        self.assertEqual(t['623']['transport'], 'hls')
        self.assertEqual(t['308']['chunk_size'], 10485760)

    def test_storyboards_are_not_tracks(self):
        t = self.tracks(YT)
        self.assertNotIn('sb0', t)
        self.assertNotIn('sb2', t)

    def test_itag_digits_are_never_a_resolution(self):
        f = yt_fmt('1440', 'webm', None, None, None, 'vp9', 'none')
        tr = nox_catalog.normalize_track(f)
        self.assertEqual((tr['width'], tr['height']), (0, 0))
        f2 = yt_fmt('137', 'mp4', None, None, None, 'avc1', 'none')
        self.assertEqual(nox_catalog.normalize_track(f2)['height'], 0)

    def test_vk_direct_height_from_name_is_a_separate_rule(self):
        t = self.tracks(VK)
        self.assertEqual(t['url1440']['height'], 1440)
        self.assertEqual(t['url1440']['kind'], 'av')
        self.assertTrue(t['url1440']['vk_direct'])
        self.assertEqual(t['hls-1080']['transport'], 'hls')
        self.assertEqual(t['dash_sep-7']['transport'], 'dash')

    def test_vertical_keeps_real_orientation(self):
        t = self.tracks(SHORTS)
        self.assertEqual((t['137']['width'], t['137']['height']), (1080, 1920))

    def test_exact_and_approximate_sizes_are_different_things(self):
        t = self.tracks(MULTI_LANG)
        self.assertTrue(t['299']['filesize_exact'])
        self.assertEqual(t['299']['filesize'], 300_000_000)
        self.assertFalse(t['298']['filesize_exact'])
        self.assertEqual(t['298']['filesize'], 0)
        self.assertEqual(t['298']['filesize_approx'], 150_000_000)
        self.assertEqual((t['302']['filesize'], t['302']['filesize_approx']), (0, 0))

    def test_audio_languages_and_roles(self):
        t = self.tracks(MULTI_LANG)
        self.assertEqual(t['140-0']['language'], 'en')
        self.assertEqual(t['140-0']['audio_role'], 'original')
        self.assertEqual(t['140-1']['language'], 'ru')
        self.assertTrue(t['251-drc']['drc'])
        self.assertFalse(t['251-0']['drc'])

    def test_duplicate_format_ids_collapse(self):
        info = copy.deepcopy(SHORTS)
        info['formats'].append(copy.deepcopy(info['formats'][0]))
        ids = [t['id'] for t in nox_catalog.tracks_of(info)]
        self.assertEqual(len(ids), len(set(ids)))

    def test_unknown_codecs_are_not_guessed_as_video_with_audio(self):
        f = {'format_id': 'mystery', 'protocol': 'https', 'url': 'https://x/y.mp4', 'ext': 'mp4', 'height': 720}
        self.assertIsNone(nox_catalog.normalize_track(f))

    def test_manifest_url_with_http_protocol_is_not_a_file(self):
        f = yt_fmt('m', 'mp4', 1280, 720, 30, 'avc1', 'mp4a', proto='https')
        f['url'] = 'https://example.com/master.m3u8?token=1'
        self.assertEqual(nox_catalog.transport_of(f), 'hls')


class AnalyzeTest(Base):
    def test_catalog_has_no_direct_urls_or_signatures(self):
        self.use(YT)
        raw = resolver.analyze('https://youtu.be/aqz-KE-bpKQ')
        self.assertNotIn('SECRET_SIGNATURE', raw)
        self.assertNotIn('googlevideo', raw)
        data = json.loads(raw)
        self.assertTrue(data['ok'])
        self.assertEqual(data['details']['video_id'], 'aqz-KE-bpKQ')
        self.assertEqual(data['details']['extractor'], 'Youtube')
        self.assertIn('versions', data)

    def test_one_extraction_per_analysis_and_cache_reuse(self):
        fake = self.use(YT)
        resolver.analyze('https://www.youtube.com/watch?v=aqz-KE-bpKQ')
        self.assertEqual(fake.calls, 1)
        again = json.loads(resolver.analyze('https://www.youtube.com/watch?v=aqz-KE-bpKQ'))
        self.assertEqual(fake.calls, 1)
        self.assertTrue(again['cached'])
        resolver.analyze('https://www.youtube.com/watch?v=aqz-KE-bpKQ', True)
        self.assertEqual(fake.calls, 2)

    def test_cache_expires(self):
        fake = self.use(YT)
        now = [1000.0]
        resolver._cache.clock = lambda: now[0]
        resolver.analyze('u')
        now[0] += resolver.ANALYZE_TTL + 1
        resolver.analyze('u')
        self.assertEqual(fake.calls, 2)

    def test_cache_is_bounded(self):
        self.use(SHORTS)
        for i in range(resolver.CACHE_MAX + 4):
            resolver.analyze('u%d' % i)
        self.assertEqual(len(resolver._cache), resolver.CACHE_MAX)

    def test_errors_are_explained(self):
        cases = {
            'ERROR: [youtube] abc: Private video. Sign in if you\'ve been granted access': 'private',
            'Sign in to confirm your age. This video may be inappropriate for some users.': 'age-restricted',
            'Sign in to confirm you’re not a bot. Use --cookies-from-browser': 'bot-check',
            'Unable to download webpage: HTTP Error 429: Too Many Requests': 'rate-limited',
            'Video unavailable. This video has been removed by the uploader': 'unavailable',
            '<urlopen error [Errno -3] Temporary failure in name resolution>': 'network',
            'Unsupported URL: https://example.com/': 'unsupported',
        }
        for text, kind in cases.items():
            self.use(error=Exception(text))
            data = json.loads(resolver.analyze('https://www.youtube.com/watch?v=x%s' % kind))
            self.assertFalse(data['ok'])
            self.assertEqual(data['kind'], kind, text)
            self.assertTrue(data['error'])

    def test_error_detail_hides_signed_urls(self):
        self.use(error=Exception('HTTP Error 403 for https://rr1.googlevideo.com/videoplayback?sig=SECRET&n=1'))
        data = json.loads(resolver.analyze('x'))
        self.assertNotIn('SECRET', data['detail'])

    def test_no_formats_is_specific(self):
        self.use({'id': 'x', 'title': 't', 'formats': []})
        data = json.loads(resolver.analyze('x'))
        self.assertEqual(data['kind'], 'no-formats')

    def test_live_is_refused(self):
        info = copy.deepcopy(SHORTS)
        info['is_live'] = True
        self.use(info)
        self.assertEqual(json.loads(resolver.analyze('x'))['kind'], 'live')


class PlanTest(Base):
    def test_plan_uses_cached_analysis_without_new_extraction(self):
        fake = self.use(YT)
        resolver.analyze('u')
        data = json.loads(resolver.plan('u', '308', '251'))
        self.assertEqual(fake.calls, 1)
        self.assertTrue(data['ok'])
        self.assertEqual(data['video']['format_id'], '308')
        self.assertEqual(data['audio']['format_id'], '251')
        self.assertEqual((data['video']['width'], data['video']['height']), (2560, 1440))
        self.assertTrue(data['video']['filesize_exact'])
        self.assertEqual(data['video']['filesize'], 473363704)

    def test_plan_for_direct_whole_file(self):
        self.use({'id': 'clip', 'title': 'clip', 'direct': True, 'extractor_key': 'Generic',
                  'formats': [{'format_id': 'mp4', 'url': 'http://10.0.2.2:8766/clip.mp4', 'ext': 'mp4', 'vcodec': None}]})
        cat = json.loads(resolver.analyze('d'))
        self.assertTrue(cat['ok'])
        self.assertEqual([t['kind'] for t in cat['tracks']], ['av'])
        data = json.loads(resolver.plan('d', 'mp4', ''))
        self.assertTrue(data['ok'], data)
        self.assertEqual(data['video']['transport'], 'http')

    def test_signed_url_is_passed_whole(self):
        self.use(YT)
        data = json.loads(resolver.plan('u', '308', '251'))
        self.assertTrue(data['video']['url'].endswith('?itag=308&sig=SECRET_SIGNATURE&expire=1790361825'))
        self.assertNotIn('Sec-Fetch-Mode', data['video']['headers'])

    def test_plan_never_substitutes_a_missing_format(self):
        self.use(YT)
        data = json.loads(resolver.plan('u', '999', '251'))
        self.assertFalse(data['ok'])
        self.assertEqual(data['kind'], 'format-gone')
        self.assertEqual(data['missing'], ['999'])
        self.assertNotIn('video', data)
        self.assertTrue(data['tracks'])  # новый каталог для выбора

    def test_plan_refreshes_when_cache_is_too_old(self):
        fake = self.use(YT)
        now = [5000.0]
        resolver._cache.clock = lambda: now[0]
        resolver.analyze('u')
        now[0] += 120
        resolver.plan('u', '308', '251', 60)
        self.assertEqual(fake.calls, 2)

    def test_segmented_transport_is_refused_not_faked(self):
        self.use(YT)
        data = json.loads(resolver.plan('u', '623', '251'))
        self.assertFalse(data['ok'])
        self.assertEqual(data['kind'], 'unsupported-transport')

    def test_progressive_plan_has_no_audio(self):
        self.use(VK)
        data = json.loads(resolver.plan('u', 'url1440'))
        self.assertTrue(data['ok'])
        self.assertIsNone(data['audio'])
        self.assertEqual(data['video']['height'], 1440)

    def test_forget_drops_cache(self):
        fake = self.use(YT)
        resolver.analyze('u')
        resolver.forget('u')
        resolver.plan('u', '308', '251')
        self.assertEqual(fake.calls, 2)


class ScrubTest(unittest.TestCase):
    def test_scrub_keeps_host_drops_query(self):
        s = nox_catalog.scrub('failed https://a.b/c/d?sig=1&token=2 x')
        self.assertIn('https://a.b/c/d?…', s)
        self.assertNotIn('token', s)


if __name__ == '__main__':
    unittest.main()
