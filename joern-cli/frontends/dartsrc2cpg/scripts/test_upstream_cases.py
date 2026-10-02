"""Reject stale source identities and contradictory upstream classifications."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import verify_upstream_cases as upstream


class UpstreamCaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[4] / "agents")
        cls.root = Path(cls.workspace.name)
        shutil.copyfile(upstream.CONFORMANCE / "source-index.json", cls.root / "source-index.json")
        shutil.copytree(upstream.CONFORMANCE / "upstream", cls.root / "upstream")
        cls.manifest = upstream.read(cls.root / "upstream/manifest.json")

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def verify(self, manifest):
        (self.root / "upstream/manifest.json").write_text(json.dumps(manifest))
        with patch.object(upstream, "CONFORMANCE", self.root):
            return upstream.verify()

    def test_pinned_sources(self):
        self.assertEqual(self.verify(self.manifest), 6)

    def test_rejects_stale_or_unclassified_sources(self):
        changes = [
            lambda m: m.update(revision="stale"),
            lambda m: m["cases"].append(copy.deepcopy(m["cases"][0])),
            lambda m: m["cases"][0].update(file="../source-index.json"),
            lambda m: m["cases"][0].update(upstreamGitBlob="stale"),
            lambda m: m["cases"][0].update(adaptedSha256="stale"),
            lambda m: m["cases"][0].update(classification="runtime-qualified"),
            lambda m: m["cases"][-1].update(expectedDiagnostics=[]),
            lambda m: m["cases"][0].update(expectedDiagnostics=["for_in_of_invalid_type"]),
            lambda m: m["cases"][0].update(adaptation=""),
        ]
        for change in changes:
            manifest = copy.deepcopy(self.manifest)
            change(manifest)
            with self.subTest(change=changes.index(change)), self.assertRaises(ValueError):
                self.verify(manifest)


if __name__ == "__main__":
    unittest.main()
