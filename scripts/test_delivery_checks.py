import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("audit", Path(__file__).with_name("audit_dependencies.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

restore_spec = importlib.util.spec_from_file_location("restore", Path(__file__).with_name("restore_check.py"))
restore = importlib.util.module_from_spec(restore_spec)
restore_spec.loader.exec_module(restore)


class DeliveryChecks(unittest.TestCase):
    def test_restore_mismatch_fails_without_replacing_existing_database(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp) / "restore.json"
            with patch.object(restore, "manifest", side_effect=[{"a": 1}, {"a": 2}]), patch.object(restore, "command", return_value=b"synthetic dump") as command:
                self.assertEqual(1, restore.run(evidence))
                report = json.loads(evidence.read_text())
                self.assertEqual("FAILED", report["status"])
                self.assertTrue(report["targetDatabase"].startswith("atlas_restore_"))
                calls = [call.args[0] for call in command.call_args_list]
                self.assertEqual(["pg_dump", "createdb", "pg_restore"], [args[0] for args in calls])
                self.assertTrue(all("--clean" not in args for args in calls))

    def test_restore_backup_failure_never_creates_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp) / "restore.json"
            with patch.object(restore, "manifest", return_value={}), patch.object(restore, "command", side_effect=RuntimeError("Backup failed")) as command:
                self.assertEqual(1, restore.run(evidence))
                self.assertEqual(1, command.call_count)
                self.assertFalse(json.loads(evidence.read_text())["targetRetained"])

    def result(self, high=0):
        return subprocess.CompletedProcess([], int(high > 0), json.dumps({"metadata": {"vulnerabilities": {
            "info": 0, "low": 0, "moderate": 0, "high": high, "critical": 0, "total": high
        }}}), "")

    def test_findings_block_but_both_reports_are_saved(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports = iter([self.result(5), self.result()])
            self.assertEqual(1, audit.audit(Path(tmp), lambda *args, **kwargs: next(reports)))
            self.assertTrue((Path(tmp) / "npm-runtime.json").exists())
            self.assertEqual("BLOCKED", json.loads((Path(tmp) / "dependency-gate.json").read_text())["gate"])

    def test_invalid_response_fails_closed_and_removes_stale_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "npm-all.json").write_text('{}')
            self.assertEqual(2, audit.audit(path, lambda *args, **kwargs: subprocess.CompletedProcess([], 0, '{}', '')))
            self.assertFalse((path / "npm-all.json").exists())

    def test_clean_reports_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(0, audit.audit(Path(tmp), lambda *args, **kwargs: self.result()))

    def test_traceability_regeneration_preserves_reviewed_evidence(self):
        root = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / 'docs'
            directory.mkdir()
            original = json.loads((root / 'docs/traceability.json').read_text())
            (directory / 'traceability.json').write_text(json.dumps(original))
            subprocess.run(['python3', str(root / 'scripts/traceability.py')], cwd=tmp, check=True, capture_output=True)
            updated = json.loads((directory / 'traceability.json').read_text())
            self.assertEqual(45, len(updated))
            for before, after in zip(original, updated):
                for key in before:
                    if key.endswith('Evidence'):
                        self.assertEqual(before[key], after[key])
                self.assertEqual(before['frontend'], after['frontend'])
                self.assertEqual(before['backend'], after['backend'])
                self.assertEqual(before['criteria'], after['criteria'])


class PrivateObjectChecks(unittest.TestCase):
    def test_private_object_copy_is_independent(self):
        import hashlib
        from scripts import document_objects
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / 'source'
            root.mkdir(mode=0o700)
            key = 'a'*64 + '/' + hashlib.sha256(b'synthetic').hexdigest()
            path = root / key
            path.parent.mkdir(mode=0o700)
            path.write_bytes(b'synthetic')
            path.chmod(0o600)
            rows = json.dumps([{'object_key':key,'sha256':hashlib.sha256(b'synthetic').hexdigest(),'bytes':9}])
            target = Path(tmp).resolve() / 'copy'
            with patch.object(document_objects,'SOURCE_ROOT',root):
                result = document_objects.copy_objects(lambda *args: rows,'fixture',target)
            self.assertEqual(1,result['objects'])
            path.write_bytes(b'changed')
            self.assertEqual(b'synthetic',(target/key).read_bytes())
            self.assertEqual(0,(target/key).stat().st_mode & 0o077)

    def test_private_object_copy_rejects_corruption(self):
        from scripts import document_objects
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / 'source'
            root.mkdir(mode=0o700)
            key = 'a'*64 + '/' + 'b'*64
            path = root / key
            path.parent.mkdir(mode=0o700)
            path.write_bytes(b'synthetic')
            path.chmod(0o600)
            rows = json.dumps([{'object_key':key,'sha256':'b'*64,'bytes':9}])
            with patch.object(document_objects,'SOURCE_ROOT',root):
                with self.assertRaisesRegex(RuntimeError,'integrity mismatch'):
                    document_objects.copy_objects(lambda *args: rows,'fixture',Path(tmp).resolve()/'copy')

if __name__ == '__main__':
    unittest.main()
