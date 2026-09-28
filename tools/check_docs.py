"""Checks the docs that kaizenmc.id/software renders from this repo.

- Every public page (docs/**/*.md outside docs/contributing/) has front matter
  with a title, a description, and a whole-number order.
- Relative links and images in README.md, CHANGELOG.md, and docs/ point at files
  that exist, and #anchors match a heading (GitHub's heading ids).
- CHANGELOG.md versions are "## <x.y.z> (<YYYY-MM-DD>)" or "(unreleased)".

Standard library only. Exits 1 and lists every problem when anything fails.
"""

from pathlib import Path, PurePosixPath
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
REQUIRED_FIELDS = ('title', 'description', 'order')
LINK = re.compile(r'!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)')
FENCE = re.compile(r'^(```|~~~)')
CHANGELOG_HEADING = re.compile(r'^\d+\.\d+\.\d+[\w.+-]* \((\d{4}-\d{2}-\d{2}|unreleased)\)$')


def strip_code(markdown):
    """Drops fenced blocks and inline code, so examples are not read as links."""
    lines, in_fence = [], False
    for line in markdown.splitlines():
        if FENCE.match(line.strip()):
            in_fence = not in_fence
            lines.append('')
            continue
        lines.append('' if in_fence else re.sub(r'`[^`]*`', '', line))
    return lines


def slugify(text):
    """GitHub's heading id: lowercase, punctuation dropped, spaces to hyphens."""
    text = re.sub(r'!?\[([^\]]*)\]\([^)]*\)', r'\1', text)  # links keep their text
    text = re.sub(r'[`*_~]', '', text).strip().lower()
    text = re.sub(r'[^\w\- ]', '', text)
    return text.replace(' ', '-')


def anchors(markdown):
    """Heading ids in one file, with GitHub's -1, -2 suffixes for repeats."""
    seen, result = {}, set()
    in_fence = False
    for line in markdown.splitlines():
        if FENCE.match(line.strip()):
            in_fence = not in_fence
            continue
        heading = None if in_fence else re.match(r'^#{1,6}\s+(.+?)\s*#*\s*$', line)
        if not heading:
            continue
        base = slugify(heading.group(1))
        count = seen.get(base, 0)
        seen[base] = count + 1
        result.add(base if count == 0 else f'{base}-{count}')
    return result


def front_matter(markdown):
    """The key: value pairs between leading --- lines, or None when absent."""
    lines = markdown.splitlines()
    if not lines or lines[0].strip() != '---':
        return None
    fields = {}
    for line in lines[1:]:
        if line.strip() == '---':
            return fields
        key, sep, value = line.partition(':')
        if sep:
            fields[key.strip()] = value.split(' #')[0].strip().strip('"\'')
    return None


def is_public_page(path):
    return path.parts[0] == 'docs' and path.suffix == '.md' and path.parts[:2] != ('docs', 'contributing')


def markdown_files(root):
    files = [root / name for name in ('README.md', 'CHANGELOG.md') if (root / name).is_file()]
    files += sorted((root / 'docs').rglob('*.md'))
    return files


def check(root=ROOT):
    problems = []
    texts = {path: path.read_text(encoding='utf-8') for path in markdown_files(root)}
    anchor_cache = {}

    def anchors_of(path):
        if path not in anchor_cache:
            text = texts.get(path) or path.read_text(encoding='utf-8')
            anchor_cache[path] = anchors(text)
        return anchor_cache[path]

    for path, text in texts.items():
        rel = PurePosixPath(path.relative_to(root).as_posix())

        if is_public_page(rel):
            fields = front_matter(text)
            if fields is None:
                problems.append(f'{rel}: missing front matter (title, description, order)')
            else:
                for field in REQUIRED_FIELDS:
                    if not fields.get(field):
                        problems.append(f'{rel}: front matter has no {field}')
                if fields.get('order') and not re.fullmatch(r'-?\d+', fields['order']):
                    problems.append(f'{rel}: front matter order "{fields["order"]}" is not a whole number')

        for number, line in enumerate(strip_code(text), start=1):
            for target in LINK.findall(line):
                if re.match(r'^[a-z][a-z0-9+.-]*:', target, re.I) or target.startswith('//'):
                    continue
                file_part, _, anchor = target.partition('#')
                file_part = file_part.split('?')[0]
                if file_part:
                    joined = PurePosixPath(file_part.lstrip('/')) if file_part.startswith('/') else rel.parent / file_part
                    parts = []
                    for part in joined.parts:
                        if part == '..':
                            if not parts:
                                parts = None
                                break
                            parts.pop()
                        elif part != '.':
                            parts.append(part)
                    if parts is None:
                        problems.append(f'{rel}:{number}: link leaves the repo: {target}')
                        continue
                    resolved = root.joinpath(*parts)
                    if not resolved.exists():
                        problems.append(f'{rel}:{number}: broken link: {target}')
                        continue
                else:
                    resolved = path
                if anchor and resolved.suffix == '.md' and anchor not in anchors_of(resolved):
                    problems.append(f'{rel}:{number}: no heading for #{anchor} in {target or rel.name}')

    changelog = texts.get(root / 'CHANGELOG.md')
    if changelog is not None:
        for number, line in enumerate(changelog.splitlines(), start=1):
            heading = re.match(r'^## +(.+?)\s*$', line)
            if heading and not CHANGELOG_HEADING.match(heading.group(1)):
                problems.append(
                    f'CHANGELOG.md:{number}: version heading should look like '
                    f'"## 1.2.3 (2026-01-31)" or "## 1.2.3 (unreleased)": {heading.group(1)}'
                )

    return problems, len(texts)


def main():
    problems, count = check()
    if problems:
        print('\n'.join(problems))
        print(f'{len(problems)} docs problem(s) found')
        return 1
    print(f'docs ok: {count} files checked')
    return 0


if __name__ == '__main__':
    sys.exit(main())
