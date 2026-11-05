"""Uploads a GitHub release's JARs to Hangar and syncs the project page.

Run by .github/workflows/hangar.yml when a release is published (or by hand
with a tag). Settings per project live in tools/hangar.json:

    {
      "project": "<hangar slug>",
      "files": [
        {"pattern": "Name-Platform-{version}.jar", "platform": "PAPER",
         "min_version": "1.21.7",
         "dependencies": [{"name": "Plugin", "url": "https://...", "required": true}]}
      ]
    }

All JARs go into one Hangar version, one file per platform. Hangar accepts files
up to 10 MB each and less than 15 MB in one upload. A JAR over the per-file limit,
and then the largest of the rest until the others fit the total, is added as a
link to its GitHub release download instead (needs GITHUB_REPOSITORY, which
Actions sets). min_version is a
platform version from Hangar's list (Minecraft versions for PAPER, proxy
versions for VELOCITY). A dependency without "url" is a Hangar project name.
A version already on Hangar is skipped, so re-running is safe. Notes come from
the release's CHANGELOG.md section, and MODRINTH.md replaces the main page.

Versions with a "-" go to the Snapshot channel, others to Release. The API
cannot create channels, so both must exist on the Hangar project.

Standard library only. Needs HANGAR_TOKEN (an API key with view_public_info,
create_version, and edit_page). --dry-run prints what would be sent and uploads
nothing.
"""

import argparse
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

from publish_modrinth import absolute_links, changelog_section

ROOT = Path(__file__).resolve().parent.parent
API = 'https://hangar.papermc.io/api/v1'
# Hangar's max-file-size (backend application.yml) for each file, and its
# maxTotalFilesSize (VersionControllerConfig, "files have to be less than 15MB
# total") for all files in one upload. JARs that do not fit are linked to the
# GitHub release download instead of uploaded.
MAX_UPLOAD_BYTES = 10_000_000
MAX_TOTAL_UPLOAD_BYTES = 15_000_000
USER_AGENT = 'kaizen-network/hangar-publish (github@rayhan.ch)'


def files_to_link(sizes):
    """Indexes of the files to link instead of upload: every file over the
    per-file limit, then the largest of the rest until the uploaded ones total
    less than the per-upload limit."""
    linked = {i for i, size in enumerate(sizes) if size > MAX_UPLOAD_BYTES}
    uploaded = sorted((i for i in range(len(sizes)) if i not in linked), key=lambda i: sizes[i], reverse=True)
    total = sum(sizes[i] for i in uploaded)
    for i in uploaded:
        if total < MAX_TOTAL_UPLOAD_BYTES:
            break
        linked.add(i)
        total -= sizes[i]
    return linked


def platform_versions_since(groups, minimum):
    """Hangar's versions for a platform from `minimum` up, oldest first."""
    # ponytail: relies on Hangar listing platform versions newest first.
    versions = [v for group in groups for v in (group['subVersions'] or [group['version']])]
    if minimum not in versions:
        raise ValueError(f'unknown platform version {minimum}')
    return versions[:versions.index(minimum) + 1][::-1]


def request(method, path, jwt, body=None, content_type=None, missing_ok=False):
    """Parsed JSON, or None for a 404 when missing_ok is set."""
    headers = {'User-Agent': USER_AGENT, **({'Authorization': f'HangarAuth {jwt}'} if jwt else {})}
    if content_type:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(f'{API}{path}', data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            text = response.read().decode('utf-8')
            return json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        if missing_ok and error.code == 404:
            return None
        detail = error.read().decode('utf-8', 'replace')[:500]
        shown = path.split('?')[0]  # keeps the API key out of the log
        raise SystemExit(f'Hangar {method} {shown} failed: HTTP {error.code} {detail}') from None


def multipart(data, file_paths):
    boundary = uuid.uuid4().hex
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="versionUpload"\r\n'
        f'Content-Type: application/json\r\n\r\n'.encode() + json.dumps(data).encode() + b'\r\n',
    ]
    for file_path in file_paths:
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{file_path.name}"\r\n'
            f'Content-Type: application/java-archive\r\n\r\n'.encode() + file_path.read_bytes() + b'\r\n')
    parts.append(f'--{boundary}--\r\n'.encode())
    return b''.join(parts), f'multipart/form-data; boundary={boundary}'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--tag', required=True, help='release tag, for example v0.2.0')
    parser.add_argument('--dist', required=True, type=Path, help='folder with the downloaded release JARs')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    token = os.environ.get('HANGAR_TOKEN', '')
    if not token and not args.dry_run:
        raise SystemExit('HANGAR_TOKEN is not set')

    config = json.loads((ROOT / 'tools' / 'hangar.json').read_text(encoding='utf-8'))
    project = config['project']
    version = args.tag[1:] if args.tag.startswith('v') else args.tag
    repo = os.environ.get('GITHUB_REPOSITORY', '')

    notes = changelog_section((ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'), version)
    if notes is None:
        raise SystemExit(f'CHANGELOG.md has no "## {version}" section')
    if repo:
        notes = absolute_links(notes, repo, args.tag)

    # Dry run without a token: draft projects are not readable anonymously,
    # so check everything except the project lookup.
    jwt = request('POST', f'/authenticate?apiKey={urllib.parse.quote(token)}', None)['token'] if token else None
    if jwt:
        # Stops on a wrong slug or a key that cannot see the project, which the
        # version lookup below would mistake for a version not uploaded yet.
        request('GET', f'/projects/{project}', jwt)
    # Hangar answers 404 for a version that is not uploaded yet.
    existing = request('GET', f'/projects/{project}/versions/{urllib.parse.quote(version, safe="")}', jwt,
                       missing_ok=True) if jwt else None

    if existing is not None:
        print(f'skip {version}: already on Hangar')
    else:
        file_paths, files, platform_deps, plugin_deps = [], [], {}, {}
        release_files = [args.dist / entry['pattern'].format(version=version) for entry in config['files']]
        for file_path in release_files:
            if not file_path.is_file():
                raise SystemExit(f'release has no {file_path.name}')
        linked = files_to_link([file_path.stat().st_size for file_path in release_files])
        for index, (entry, file_path) in enumerate(zip(config['files'], release_files)):
            platform = entry['platform']
            groups = request('GET', f'/platforms/{platform}/versions', None)
            if index in linked:
                if not repo:
                    raise SystemExit(f'{file_path.name} does not fit Hangar\'s upload limits and needs GITHUB_REPOSITORY for a link')
                files.append({'platforms': [platform],
                              'externalUrl': f'https://github.com/{repo}/releases/download/{args.tag}/{file_path.name}'})
            else:
                file_paths.append(file_path)
                files.append({'platforms': [platform]})
            platform_deps[platform] = platform_versions_since(groups, entry['min_version'])
            plugin_deps[platform] = [
                {'name': dep['name'], 'required': dep['required'],
                 **({'externalUrl': dep['url']} if 'url' in dep else {})}
                for dep in entry.get('dependencies', [])]

        data = {
            'version': version,
            'channel': 'Snapshot' if '-' in version else 'Release',
            'description': notes,
            'files': files,
            'platformDependencies': platform_deps,
            'pluginDependencies': plugin_deps,
        }
        if args.dry_run:
            print(f'would upload {", ".join(p.name for p in file_paths) or "no files"}: {json.dumps(data, indent=2)}')
        else:
            body, content_type = multipart(data, file_paths)
            created = request('POST', f'/projects/{project}/upload', jwt, body, content_type)
            print(f'uploaded {version}: {created["url"]}')

    description = (ROOT / 'MODRINTH.md').read_text(encoding='utf-8')
    if args.dry_run:
        print(f'would sync MODRINTH.md ({len(description)} characters) to {project}')
    else:
        request('PATCH', f'/pages/editmain/{project}', jwt,
                json.dumps({'content': description}).encode(), 'application/json')
        print(f'synced MODRINTH.md to {project}')


if __name__ == '__main__':
    sys.exit(main())
