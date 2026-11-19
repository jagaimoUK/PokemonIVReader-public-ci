#!/usr/bin/env python3
import pathlib
import tempfile
import unittest
import zipfile
from public_binary_preflight import inspect_webp, inspect_jar, scan_credentials

def sample(extra=b''):
    data = b'012345678'
    chunk = b'VP8 ' + len(data).to_bytes(4,'little') + data + b'\0'
    body = b'WEBP' + chunk + extra
    return b'RIFF' + len(body).to_bytes(4,'little') + body

class BinaryAuditTest(unittest.TestCase):
    def test_valid_webp(self):
        self.assertEqual([], inspect_webp(sample()))
    def test_metadata_is_rejected(self):
        self.assertTrue(inspect_webp(sample(b'EXIF'+(2).to_bytes(4,'little')+b'aa')))
    def test_trailing_bytes_are_rejected(self):
        self.assertTrue(inspect_webp(sample()+b'extra'))
    def test_truncated_webp_is_rejected(self):
        self.assertTrue(inspect_webp(sample()[:-1]))
    def test_jar_and_unsafe_entry(self):
        with tempfile.TemporaryDirectory() as folder:
            path=pathlib.Path(folder)/'test.jar'
            with zipfile.ZipFile(path,'w') as jar:jar.writestr('A.class',b'class')
            self.assertEqual([],inspect_jar(path))
            with zipfile.ZipFile(path,'w') as jar:jar.writestr('../private.pem',b'key')
            self.assertTrue(inspect_jar(path))
    def test_credentials(self):
        self.assertTrue(scan_credentials(b'-----BEGIN ' + b'PRIVATE KEY-----'))
        self.assertFalse(scan_credentials(b'not a key'))

if __name__ == '__main__':
    unittest.main()
