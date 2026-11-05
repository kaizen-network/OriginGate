"""Tests for the Hangar publisher (no network)."""

import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest import mock
import urllib.error

import publish_hangar as ph

# Newest first, the way Hangar lists them.
GROUPS = [
    {'version': '4.0.0', 'subVersions': []},
    {'version': '3.5', 'subVersions': ['3.5.1', '3.5.0', '3.5']},
    {'version': '3.4', 'subVersions': ['3.4.0', '3.4']},
    {'version': '3.3', 'subVersions': []},
]


class PublishHangarTest(unittest.TestCase):
    def test_platform_versions_from_minimum(self):
        self.assertEqual(ph.platform_versions_since(GROUPS, '3.4'), ['3.4', '3.4.0', '3.5', '3.5.0', '3.5.1', '4.0.0'])
        self.assertEqual(ph.platform_versions_since(GROUPS, '4.0.0'), ['4.0.0'])
        with self.assertRaises(ValueError):
            ph.platform_versions_since(GROUPS, '9.9')

    def test_config_matches_known_platforms(self):
        config = json.loads((Path(__file__).parent / 'hangar.json').read_text(encoding='utf-8'))
        self.assertTrue(config['project'])
        for entry in config['files']:
            self.assertIn('{version}', entry['pattern'])
            self.assertIn(entry['platform'], {'PAPER', 'VELOCITY', 'WATERFALL'})
            self.assertTrue(entry['min_version'])

    def test_missing_version_is_none_and_other_errors_stop(self):
        def answer(code):
            return mock.patch.object(ph.urllib.request, 'urlopen', side_effect=urllib.error.HTTPError(
                'https://hangar.test', code, 'error', {}, io.BytesIO(b'{}')))
        with answer(404):
            self.assertIsNone(ph.request('GET', '/projects/Probe/versions/1.0.0', 'jwt', missing_ok=True))
            with self.assertRaises(SystemExit):
                ph.request('GET', '/projects/Probe/versions/1.0.0', 'jwt')
        with answer(500), self.assertRaises(SystemExit):
            ph.request('GET', '/projects/Probe/versions/1.0.0', 'jwt', missing_ok=True)

    def test_version_already_on_hangar_is_looked_up_by_name_and_skipped(self):
        calls = []

        def fake(method, path, jwt, body=None, content_type=None, missing_ok=False):
            calls.append((method, path, missing_ok))
            return {'token': 'jwt'} if path.startswith('/authenticate') else {}

        project = json.loads((Path(__file__).parent / 'hangar.json').read_text(encoding='utf-8'))['project']
        with mock.patch.object(ph, 'request', fake), mock.patch.dict(os.environ, {'HANGAR_TOKEN': 'key'}), \
                mock.patch.object(sys, 'argv', ['publish_hangar.py', '--tag', 'v1.0.0', '--dist', 'unused']), \
                mock.patch('builtins.print'):
            ph.main()
        self.assertIn(('GET', f'/projects/{project}/versions/1.0.0', True), calls)
        self.assertFalse(any(path.endswith('/upload') for _, path, _ in calls))


if __name__ == '__main__':
    unittest.main()
