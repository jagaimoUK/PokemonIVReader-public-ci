#!/usr/bin/env python3
"""Small, secret-free safety tests for the manual signed APK workflow."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("signed_inputs", ROOT / "tools/verify_signed_release_inputs.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

IDENTITY = ("applicationId=com.example.pokemonivreader\n"
            "certificateSha256=" + "a" * 64 + "\n"
            "baselineVersionCode=1\n")
GRADLE = 'versionCode = 99\nversionName = "1.5.74"\n'

class ReleasePolicyTests(unittest.TestCase):
    def test_valid_update(self):
        module.validate("v1.5.74", "99", "98", GRADLE, IDENTITY)

    def test_bad_version_or_identity_rejected(self):
        cases = [
            ("v1.5.73", "99", "98", GRADLE, IDENTITY),
            ("v1.5.74", "98", "98", GRADLE, IDENTITY),
            ("v1.5.74", "99", "99", GRADLE, IDENTITY),
            ("v1.5.74", "99", "98", GRADLE, IDENTITY.replace("a" * 64, "bad")),
            ("v1.5.74", "99", "98", GRADLE, IDENTITY.replace("com.example.pokemonivreader", "other")),
            ("v1.5.74", "99", "98", GRADLE.replace("99", "100"), IDENTITY),
            ("v1.5.74", "99", "98", GRADLE, IDENTITY.replace("baselineVersionCode=1", "baselineVersionCode=999")),
        ]
        for args in cases:
            with self.subTest(args=args[:3]):
                with self.assertRaises(ValueError):
                    module.validate(*args)

    def test_workflow_manual_main_owner_environment_only(self):
        content = (ROOT / ".github/workflows/android-signed-release.yml").read_text()
        self.assertIn("workflow_dispatch:", content)
        self.assertNotIn("  push:", content)
        self.assertNotIn("  pull_request:", content)
        self.assertIn("refs/heads/main", content)
        self.assertIn("github.actor == 'jagaimoUK'", content)
        self.assertIn("environment: android-signed-release", content)
        self.assertIn("tools/assert_canonical_repo.sh", content)
        self.assertIn("verify_signed_release_inputs.py", content)
        self.assertIn("ACTUAL_CERT", content)
        self.assertIn("EXPECTED_CERT", content)
        self.assertIn("gh release create", content)
        self.assertNotIn("upload-artifact@", content)

if __name__ == "__main__":
    unittest.main()
