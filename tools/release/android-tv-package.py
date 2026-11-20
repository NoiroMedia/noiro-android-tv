#!/usr/bin/env python3
"""Record the signed standalone TV APK identity and checksum, without publishing a launcher feed."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

apk = Path(sys.argv[1]).resolve()
identity = subprocess.check_output([sys.argv[2], 'dump', 'badging', str(apk)], text=True)
match = re.search(r"package: name='(tv\.noiro\.androidtv)' versionCode='(\d+)' versionName='(\d+\.\d+\.\d+)'", identity)
if not match:
    raise SystemExit('Unexpected Android TV package identity')
digest = hashlib.sha256()
with apk.open('rb') as stream:
    for chunk in iter(lambda: stream.read(65536), b''):
        digest.update(chunk)
release = dict(package=match[1], version=match[3], build=int(match[2]),
               size=apk.stat().st_size, sha256=digest.hexdigest())
(apk.parent / 'android-tv-release.json').write_text(json.dumps(release, indent=2) + '\n')
(apk.parent / (apk.name + '.sha256')).write_text(release['sha256'] + '  ' + apk.name + '\n')
print(json.dumps(release))
