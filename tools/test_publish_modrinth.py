"""Tests for the Modrinth publisher's pure parts (no network)."""

import json
from pathlib import Path
import unittest

import publish_modrinth as pm

CHANGELOG = """# Changelog

## 0.2.0 (2026-09-25)

Easier setup. See the [default config](presentation/config.yml) and [docs](https://kaizenmc.id/software).

## 0.1.0 (2026-09-23)

First release.
"""

TAGS = [
    {'version': '26.2', 'version_type': 'release', 'date': '2026-09-01T00:00:00Z'},
    {'version': '26.2-rc1', 'version_type': 'snapshot', 'date': '2026-08-20T00:00:00Z'},
    {'version': '26.1', 'version_type': 'release', 'date': '2026-06-01T00:00:00Z'},
    {'version': '1.21.8', 'version_type': 'release', 'date': '2025-07-17T00:00:00Z'},
    {'version': '1.21.7', 'version_type': 'release', 'date': '2025-06-30T00:00:00Z'},
    {'version': '1.21.6', 'version_type': 'release', 'date': '2025-06-17T00:00:00Z'},
]


class PublishModrinthTest(unittest.TestCase):
    def test_changelog_section(self):
        self.assertTrue(pm.changelog_section(CHANGELOG, '0.2.0').startswith('Easier setup.'))
        self.assertEqual(pm.changelog_section(CHANGELOG, '0.1.0'), 'First release.')
        self.assertIsNone(pm.changelog_section(CHANGELOG, '9.9.9'))

    def test_relative_links_point_at_github(self):
        notes = pm.absolute_links(pm.changelog_section(CHANGELOG, '0.2.0'), 'kaizen-network/ConsentGate', 'v0.2.0')
        self.assertIn('(https://github.com/kaizen-network/ConsentGate/blob/v0.2.0/presentation/config.yml)', notes)
        self.assertIn('(https://kaizenmc.id/software)', notes)

    def test_game_versions_from_minimum_by_date(self):
        self.assertEqual(pm.game_versions_since(TAGS, '1.21.7'), ['1.21.7', '1.21.8', '26.1', '26.2'])
        with self.assertRaises(ValueError):
            pm.game_versions_since(TAGS, '9.9')

    def test_config_matches_known_loaders(self):
        config = json.loads((Path(__file__).parent / 'modrinth.json').read_text(encoding='utf-8'))
        self.assertTrue(config['project'])
        for entry in config['files']:
            self.assertIn('{version}', entry['pattern'])
            self.assertTrue(set(entry['loaders']) <= {'paper', 'purpur', 'folia', 'spigot', 'bukkit', 'velocity', 'bungeecord'})


if __name__ == '__main__':
    unittest.main()
