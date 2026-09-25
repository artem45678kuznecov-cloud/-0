# coding: utf-8
"""Кэш разобранного плеера YouTube (nox_jsc.PlayerCache): ограничен и не мешает остальному кэшу yt-dlp."""

import os
import shutil
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'main', 'python'))

import nox_jsc  # noqa: E402


class Inner(object):
    def __init__(self):
        self.calls = []
        self.enabled = False

    def load(self, section, key, *a, **k):
        self.calls.append(('load', section, key))
        return None

    def store(self, section, key, data, *a, **k):
        self.calls.append(('store', section, key))


class PlayerCacheTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        nox_jsc._MEMORY.clear()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)
        nox_jsc._MEMORY.clear()

    def test_player_is_cached_in_memory_and_on_disk(self):
        c = nox_jsc.PlayerCache(Inner(), self.dir)
        c.store('challenge-solver', 'player:https://y/p1.js', 'PRE1')
        self.assertEqual(c.load('challenge-solver', 'player:https://y/p1.js'), 'PRE1')
        nox_jsc._MEMORY.clear()
        self.assertEqual(nox_jsc.PlayerCache(Inner(), self.dir).load('challenge-solver', 'player:https://y/p1.js'), 'PRE1')

    def test_other_cache_keys_go_to_yt_dlp_cache(self):
        inner = Inner()
        c = nox_jsc.PlayerCache(inner, self.dir)
        self.assertIsNone(c.load('challenge-solver', 'lib'))
        c.store('youtube-sigfuncs', 'x', {'a': 1})
        self.assertEqual(inner.calls, [('load', 'challenge-solver', 'lib'), ('store', 'youtube-sigfuncs', 'x')])
        self.assertFalse(c.enabled)                      # остальные атрибуты — от исходного кэша

    def test_disk_and_memory_are_bounded(self):
        c = nox_jsc.PlayerCache(Inner(), self.dir)
        for i in range(6):
            c.store('challenge-solver', 'player:u%d' % i, 'P%d' % i)
            past = time.time() - 100 + i
            for n in os.listdir(self.dir):
                if n.endswith('.js') and os.path.getmtime(os.path.join(self.dir, n)) > past + 50:
                    os.utime(os.path.join(self.dir, n), (past, past))
        self.assertLessEqual(len([n for n in os.listdir(self.dir) if n.endswith('.js')]), nox_jsc.PlayerCache.DISK_MAX)
        self.assertLessEqual(len(nox_jsc._MEMORY), nox_jsc.PlayerCache.MEMORY_MAX)

    def test_missing_entry_is_none(self):
        self.assertIsNone(nox_jsc.PlayerCache(Inner(), self.dir).load('challenge-solver', 'player:nope'))


if __name__ == '__main__':
    unittest.main()
