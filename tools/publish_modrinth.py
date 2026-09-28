"""Uploads a GitHub release's JARs to Modrinth and syncs the project description.

Run by .github/workflows/modrinth.yml when a release is published (or by hand
with a tag). Settings per project live in tools/modrinth.json:

    {
      "project": "<modrinth slug>",
      "files": [
        {"pattern": "Name-Platform-{version}.jar", "label": "Platform",
         "loaders": ["paper"], "min_game_version": "1.21.7",
         "dependencies": [{"project": "<slug>", "type": "required"}]}
      ]
    }

Each JAR becomes its own Modrinth version, so every platform gets the right
download. A version already on Modrinth (same number and loaders) is skipped,
so re-running is safe. Notes come from the release's CHANGELOG.md section, and
MODRINTH.md replaces the project description.

Standard library only. Needs MODRINTH_TOKEN (scopes: read/write projects,
read/create versions). --dry-run prints what would be sent and uploads nothing.
"""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
API = 'https://api.modrinth.com/v2'
USER_AGENT = 'kaizen-network/modrinth-publish (github@rayhan.ch)'


def changelog_section(markdown, version):
    """The body under "## <version> ...", or None when the version is missing."""
    lines, found = [], False
    for line in markdown.replace('\r\n', '\n').split('\n'):
        heading = re.match(r'^## +v?(\S+)', line)
        if heading:
            if found:
                break
            found = heading.group(1) == version
            continue
        if found:
            lines.append(line)
    return '\n'.join(lines).strip() if found else None


def absolute_links(markdown, repo, ref):
    """Relative links in the changelog point at GitHub, since Modrinth cannot resolve them."""
    def replace(match):
        text, target = match.group(1), match.group(2)
        if re.match(r'^[a-z][a-z0-9+.-]*:|^#|^//', target, re.I):
            return match.group(0)
        return f'[{text}](https://github.com/{repo}/blob/{ref}/{target.lstrip("/")})'

    return re.sub(r'(?<!!)\[([^\]]*)\]\(([^)\s]+)\)', replace, markdown)


def game_versions_since(tags, minimum):
    """Release game versions from `minimum` onward, by release date (works across 1.x and 26.x)."""
    by_name = {tag['version']: tag for tag in tags}
    if minimum not in by_name:
        raise ValueError(f'unknown game version {minimum}')
    start = by_name[minimum]['date']
    releases = [tag for tag in tags if tag['version_type'] == 'release' and tag['date'] >= start]
    return [tag['version'] for tag in sorted(releases, key=lambda tag: tag['date'])]


def request(method, path, token, body=None, content_type=None):
    headers = {'User-Agent': USER_AGENT, **({'Authorization': token} if token else {})}
    if content_type:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(f'{API}{path}', data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            text = response.read().decode('utf-8')
            return json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        detail = error.read().decode('utf-8', 'replace')[:500]
        raise SystemExit(f'Modrinth {method} {path} failed: HTTP {error.code} {detail}') from None


def multipart(data, file_path):
    boundary = uuid.uuid4().hex
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
        f'Content-Type: application/json\r\n\r\n'.encode() + json.dumps(data).encode() + b'\r\n',
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file_path.name}"\r\n'
        f'Content-Type: application/java-archive\r\n\r\n'.encode() + file_path.read_bytes() + b'\r\n',
        f'--{boundary}--\r\n'.encode(),
    ]
    return b''.join(parts), f'multipart/form-data; boundary={boundary}'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--tag', required=True, help='release tag, for example v0.2.0')
    parser.add_argument('--dist', required=True, type=Path, help='folder with the downloaded release JARs')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    token = os.environ.get('MODRINTH_TOKEN', '')
    if not token and not args.dry_run:
        raise SystemExit('MODRINTH_TOKEN is not set')

    config = json.loads((ROOT / 'tools' / 'modrinth.json').read_text(encoding='utf-8'))
    version = args.tag[1:] if args.tag.startswith('v') else args.tag
    repo = os.environ.get('GITHUB_REPOSITORY', '')

    notes = changelog_section((ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'), version)
    if notes is None:
        raise SystemExit(f'CHANGELOG.md has no "## {version}" section')
    if repo:
        notes = absolute_links(notes, repo, args.tag)

    if token:
        project = request('GET', f'/project/{config["project"]}', token)
        existing = request('GET', f'/project/{project["id"]}/version', token)
    else:
        # Dry run without a token: draft projects are not readable anonymously,
        # so check everything except the project lookup.
        title = config['files'][0]['pattern'].split('-')[0]
        project, existing = {'id': f'<{config["project"]} id>', 'title': title}, []
    tags = request('GET', '/tag/game_version', token)

    for entry in config['files']:
        file_path = args.dist / entry['pattern'].format(version=version)
        if not file_path.is_file():
            raise SystemExit(f'release has no {file_path.name}')
        loaders = entry['loaders']
        if any(v['version_number'] == version and set(v['loaders']) & set(loaders) for v in existing):
            print(f'skip {file_path.name}: {version} for {", ".join(loaders)} is already on Modrinth')
            continue

        dependencies = []
        for dep in entry.get('dependencies', []):
            dep_project = request('GET', f'/project/{dep["project"]}', token)
            dependencies.append({'project_id': dep_project['id'], 'dependency_type': dep['type']})

        data = {
            'project_id': project['id'],
            'name': f'{project["title"]} {version} ({entry["label"]})',
            'version_number': version,
            'changelog': notes,
            'dependencies': dependencies,
            'game_versions': game_versions_since(tags, entry['min_game_version']),
            'version_type': 'beta' if '-' in version else 'release',
            'loaders': loaders,
            'featured': True,
            'file_parts': ['file'],
            'primary_file': 'file',
        }
        if args.dry_run:
            shown = {**data, 'game_versions': f'{len(data["game_versions"])} versions, '
                     f'{data["game_versions"][0]} to {data["game_versions"][-1]}'}
            print(f'would upload {file_path.name}: {json.dumps(shown, indent=2)}')
            continue
        body, content_type = multipart(data, file_path)
        created = request('POST', '/version', token, body, content_type)
        print(f'uploaded {file_path.name} as version {created["id"]}')

    description = (ROOT / 'MODRINTH.md').read_text(encoding='utf-8')
    if args.dry_run:
        print(f'would sync MODRINTH.md ({len(description)} characters) to {config["project"]}')
    else:
        request('PATCH', f'/project/{project["id"]}', token,
                json.dumps({'body': description}).encode(), 'application/json')
        print(f'synced MODRINTH.md to {config["project"]}')


if __name__ == '__main__':
    sys.exit(main())
