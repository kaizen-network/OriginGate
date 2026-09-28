"""Tests for the docs checker, on small throwaway repos."""

from pathlib import Path
import tempfile
import unittest

import check_docs

GOOD_PAGE = '---\ntitle: Setup\ndescription: How to set it up.\norder: 2\n---\n\n# Setup\n\n## First steps\n\nText.\n'


class CheckDocsTest(unittest.TestCase):
    def repo(self, files):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name)
        for name, text in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
        return root

    def problems(self, files):
        return check_docs.check(self.repo(files))[0]

    def test_clean_repo_passes(self):
        self.assertEqual(
            self.problems(
                {
                    'README.md': 'See [setup](docs/setup.md#first-steps) and ![shot](docs/images/a.png).\n',
                    'CHANGELOG.md': '# Changelog\n\n## 0.2.0 (2026-09-25)\n\n## 0.1.0 (unreleased)\n',
                    'docs/setup.md': GOOD_PAGE + 'Back to the [README](../README.md) or [top](#setup).\n',
                    'docs/images/a.png': 'png',
                    'docs/contributing/notes.md': '# Notes\n\nNo front matter needed here.\n',
                }
            ),
            [],
        )

    def test_missing_front_matter_fields(self):
        problems = self.problems(
            {
                'docs/a.md': '# A\n',
                'docs/b.md': '---\ntitle: B\norder: first\n---\n# B\n',
            }
        )
        self.assertIn('docs/a.md: missing front matter (title, description, order)', problems)
        self.assertIn('docs/b.md: front matter has no description', problems)
        self.assertIn('docs/b.md: front matter order "first" is not a whole number', problems)

    def test_broken_links_images_and_anchors(self):
        problems = self.problems(
            {
                'docs/setup.md': GOOD_PAGE
                + '[gone](missing.md) ![img](images/none.png) [bad](setup.md#nope) [out](../../x.md)\n',
            }
        )
        self.assertTrue(any('broken link: missing.md' in p for p in problems))
        self.assertTrue(any('broken link: images/none.png' in p for p in problems))
        self.assertTrue(any('no heading for #nope' in p for p in problems))
        self.assertTrue(any('link leaves the repo: ../../x.md' in p for p in problems))

    def test_ignores_code_and_external_links(self):
        self.assertEqual(
            self.problems(
                {
                    'docs/setup.md': GOOD_PAGE
                    + '`[not](a-link.md)`\n\n```\n[also](not-a-link.md)\n```\n\n[web](https://example.com/x.md)\n',
                }
            ),
            [],
        )

    def test_changelog_heading_format(self):
        problems = self.problems({'CHANGELOG.md': '## 0.2.0\n\n## v1 released\n'})
        self.assertEqual(len(problems), 2)

    def test_github_heading_ids(self):
        self.assertEqual(check_docs.slugify('MySQL and MariaDB'), 'mysql-and-mariadb')
        self.assertEqual(check_docs.slugify('Permission timing (LuckPerms)'), 'permission-timing-luckperms')
        self.assertEqual(check_docs.slugify('`config.yml`'), 'configyml')
        self.assertEqual(check_docs.anchors('# A\n## A\n## A\n'), {'a', 'a-1', 'a-2'})


if __name__ == '__main__':
    unittest.main()
