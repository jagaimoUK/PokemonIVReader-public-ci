#!/usr/bin/env python3
"""Synthetic export tests; no game assets or live credentials."""
import importlib.util
import pathlib
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location('source_exporter', HERE / 'export_public_snapshot.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ExportTest(unittest.TestCase):
    def repo(self, root):
        root.mkdir()
        def git(*a):
            subprocess.run(['git', '-C', str(root), *a], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        git('init', '-b', module.CANDIDATE)
        git('config', 'user.name', 'Synthetic Test')
        git('config', 'user.email', 'synthetic@example.invalid')
        for name in module.MANDATORY:
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('test content only', encoding='utf8')
        git('add', '.')
        git('commit', '-m', 'fixtures')
        return git

    def test_history_free_zip_and_manifest(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td) / 'repo'
            self.repo(root)
            zip_file = pathlib.Path(td) / 'export.zip'
            manifest = module.export(root, zip_file)
            self.assertEqual(manifest['file_count'], len(module.MANDATORY))
            with ZipFile(zip_file) as z:
                self.assertEqual(set(z.namelist()), module.MANDATORY)
                self.assertFalse(any('.git' in n for n in z.namelist()))
            self.assertTrue(zip_file.with_suffix('.manifest.json').exists())
            with self.assertRaises(FileExistsError):
                module.export(root, zip_file)

    def test_rejects_forbidden_asset(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td) / 'repo'
            git = self.repo(root)
            asset = root / 'app/src/main/assets/go_buttons/search.json'
            asset.parent.mkdir(parents=True)
            asset.write_text('{"pixels": []}')
            git('add', '.')
            git('commit', '-m', 'accidental asset')
            with self.assertRaisesRegex(ValueError, 'Unsafe'):
                module.export(root, pathlib.Path(td) / 'export.zip')

    def test_rejects_wrong_branch(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td) / 'repo'
            git = self.repo(root)
            git('switch', '-c', 'main')
            with self.assertRaisesRegex(ValueError, 'branch'):
                module.export(root, pathlib.Path(td) / 'export.zip')

    def test_rejects_symlink_in_git_tree(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td) / 'repo'
            git = self.repo(root)
            (root / 'link.txt').symlink_to('README.md')
            git('add', '.')
            git('commit', '-m', 'symlink')
            with self.assertRaisesRegex(ValueError, 'symlink'):
                module.export(root, pathlib.Path(td) / 'export.zip')

    def test_rejects_simulated_private_key(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td) / 'repo'
            git = self.repo(root)
            (root / 'bad.txt').write_bytes(b'-----BEGIN ' + b'PRIVATE KEY-----')
            git('add', '.')
            git('commit', '-m', 'danger')
            with self.assertRaisesRegex(ValueError, 'Potential credential'):
                module.export(root, pathlib.Path(td) / 'export.zip')

if __name__ == '__main__':
    unittest.main()
