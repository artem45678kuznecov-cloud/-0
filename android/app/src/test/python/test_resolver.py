# coding: utf-8
"""
Проверки resolver.py без yt-dlp и без сети: лестница качества, отбор
combined-форматов, белый список заголовков, форма ответа.

Запуск: python3 -m unittest discover -s android/app/src/test/python
"""
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'main', 'python'))

import resolver  # noqa: E402


def vk(fid, height=None):
    return {'format_id': fid, 'url': 'https://vkvd.example/%s.mp4' % fid,
            'ext': 'mp4', 'protocol': 'https',
            'height': height, 'http_headers': {'User-Agent': 'vk', 'Sec-Fetch-Mode': 'no-cors'}}


class QualityLadder(unittest.TestCase):
    def info(self, *formats):
        return {'title': 'Повар-боец Сома', 'id': 'v1', 'formats': list(formats)}

    def test_480_prefers_url480(self):
        info = self.info(vk('url144'), vk('url360'), vk('url480'), vk('url720'))
        self.assertEqual(resolver.pick_direct_format(info, '480')['format_id'], 'url480')

    def test_480_falls_down_the_ladder(self):
        info = self.info(vk('url144'), vk('url360'), vk('url720'))
        self.assertEqual(resolver.pick_direct_format(info, '480')['format_id'], 'url360')

    def test_360_never_takes_higher(self):
        info = self.info(vk('url480'), vk('url720'))
        self.assertIsNone(resolver.pick_direct_format(info, '360'))

    def test_720_and_max(self):
        info = self.info(vk('url360'), vk('url720'), vk('url1080'))
        self.assertEqual(resolver.pick_direct_format(info, '720')['format_id'], 'url720')
        self.assertEqual(resolver.pick_direct_format(info, 'MAX')['format_id'], 'url1080')

    def test_unknown_quality_is_480(self):
        info = self.info(vk('url360'), vk('url480'), vk('url720'))
        self.assertEqual(resolver.pick_direct_format(info, 'whatever')['format_id'], 'url480')

    def test_segmented_vk_is_skipped(self):
        seg = vk('url480')
        seg['protocol'] = 'm3u8_native'
        info = self.info(seg, vk('url360'))
        self.assertEqual(resolver.pick_direct_format(info, '480')['format_id'], 'url360')

    def test_combined_fallback_respects_height_cap(self):
        formats = [
            {'format_id': '22', 'url': 'https://a/22', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 720, 'tbr': 900},
            {'format_id': '18', 'url': 'https://a/18', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 360, 'tbr': 500},
            {'format_id': '137', 'url': 'https://a/137', 'vcodec': 'avc1', 'acodec': 'none', 'height': 1080},
            {'format_id': 'hls-480', 'url': 'https://a/h', 'vcodec': 'avc1', 'acodec': 'mp4a', 'height': 480,
             'protocol': 'm3u8_native'},
        ]
        info = {'title': 't', 'id': 'i', 'formats': formats}
        self.assertEqual(resolver.pick_direct_format(info, '480')['format_id'], '18')
        self.assertEqual(resolver.pick_direct_format(info, 'MAX')['format_id'], '22')
        # video-only никогда
        self.assertNotEqual(resolver.pick_direct_format(info, 'MAX')['format_id'], '137')

    def test_no_combined_means_none(self):
        info = {'title': 't', 'id': 'i', 'formats': [
            {'format_id': '137', 'url': 'https://a/137', 'vcodec': 'avc1', 'acodec': 'none', 'height': 1080},
            {'format_id': '140', 'url': 'https://a/140', 'vcodec': 'none', 'acodec': 'mp4a'},
        ]}
        self.assertIsNone(resolver.pick_direct_format(info, 'MAX'))

    def test_playlist_entry_is_unwrapped(self):
        info = {'entries': [self.info(vk('url480'))]}
        self.assertEqual(resolver.pick_direct_format(info, '480')['format_id'], 'url480')


class Headers(unittest.TestCase):
    def test_whitelist_matches_iphone_native_policy(self):
        self.assertEqual(sorted(resolver.HEADER_KEYS),
                         sorted(['User-Agent', 'Referer', 'Origin', 'Cookie', 'Accept', 'Accept-Language']))

    def test_filtering_is_case_insensitive_and_drops_browser_noise(self):
        raw = {'user-agent': 'vk', 'REFERER': 'https://vk.com/', 'Cookie': 'a=b',
               'Accept': '*/*', 'accept-language': 'ru', 'Sec-Fetch-Mode': 'no-cors',
               'Sec-Fetch-Site': 'x', 'Connection': 'keep-alive', 'Host': 'h',
               'Content-Length': '0', 'Transfer-Encoding': 'chunked'}
        out = resolver.safe_headers(raw)
        self.assertEqual(sorted(out), ['Accept', 'Accept-Language', 'Cookie', 'Referer', 'User-Agent'])
        self.assertEqual(out['User-Agent'], 'vk')
        self.assertEqual(resolver.safe_headers(None), {})


class Describe(unittest.TestCase):
    def test_answer_shape(self):
        fmt = vk('url480', height=480)
        fmt['filesize'] = 12345
        info = {'title': 'Сома', 'id': 'v1', 'duration': 1500.7, 'thumbnail': 'https://img/1.jpg',
                'formats': [fmt], 'extractor': 'vk'}
        d = resolver.describe(info, fmt, '480')
        self.assertTrue(d['ok'])
        self.assertEqual(d['title'], 'Сома')
        self.assertEqual(d['direct_url'], fmt['url'])
        self.assertEqual(d['height'], 480)
        self.assertEqual(d['filesize'], 12345)
        self.assertEqual(d['duration'], 1500)
        self.assertEqual(d['headers'], {'User-Agent': 'vk'})
        json.dumps(d)                          # сериализуемо

    def test_resolve_never_raises(self):
        # yt-dlp здесь нет — ответ обязан быть JSON с ok=False, а не исключением.
        out = json.loads(resolver.resolve('https://invalid.invalid/no-such-video', '480'))
        self.assertFalse(out['ok'])
        self.assertIn('error', out)


if __name__ == '__main__':
    unittest.main()
