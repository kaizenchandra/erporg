"""Bounded local synthetic private-object copy/verification; never prints content or keys."""
import hashlib
import json
from pathlib import Path
import re
import os

SOURCE_ROOT = Path('/private/tmp/atlas-private-documents')


def copy_objects(sql, database, destination):
    rows = json.loads(sql(database, "select coalesce(json_agg(t),'[]'::json)::text from (select distinct object_key,sha256,bytes from atlas_acceptance.employee_document order by object_key)t"))
    source = SOURCE_ROOT
    if source.is_symlink() or source.resolve() != source or not source.is_dir():
        raise RuntimeError('Canonical private source storage required')
    if source.stat().st_mode & 0o077:
        raise RuntimeError('Private storage root permissions are too broad')
    destination.mkdir(mode=0o700, exist_ok=False)
    manifest = []
    for row in rows:
        key = row['object_key']
        if not re.fullmatch(r'[a-f0-9]{64}/[a-f0-9]{64}', key) or not 1 <= row['bytes'] <= 16384:
            raise RuntimeError('Invalid private object metadata')
        original = source / key
        if original.parent.is_symlink() or original.is_symlink() or original.stat().st_mode & 0o077:
            raise RuntimeError('Private source object permissions/path invalid')
        with original.open('rb') as handle:
            content = handle.read(16385)
        digest = hashlib.sha256(content).hexdigest()
        if len(content) != row['bytes'] or digest != row['sha256']:
            raise RuntimeError('Private source object integrity mismatch')
        target = destination / key
        target.parent.mkdir(mode=0o700, exist_ok=True)
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as handle:
            handle.write(content)
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Private copied object integrity mismatch')
        manifest.append([key, digest, len(content)])
    return {'status': 'PASSED', 'objects': len(manifest),
            'manifestSha256': hashlib.sha256(json.dumps(manifest).encode()).hexdigest(),
            'restoredRoot': str(destination),
            'scope': 'independent local synthetic private-object copy; not cloud/encrypted backup or production recovery'}
