"""Regression checks for stale or overstated qualification evidence."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import verify_qualification_matrix as qualification


class QualificationMatrixTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[4] / "agents")
        cls.root = Path(cls.workspace.name)
        cls.conformance = cls.root / "conformance"
        cls.conformance.mkdir()
        for name in ["inventory.json", "source-index.json", "qualification-matrix.json"]:
            shutil.copyfile(qualification.CONFORMANCE / name, cls.conformance / name)
        cls.original = qualification.read(cls.conformance / "qualification-matrix.json")
        for item in cls.original["evidence"].values():
            target = cls.root / item["file"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(qualification.CONFORMANCE.parent / item["file"], target)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def verify(self, matrix):
        (self.conformance / "qualification-matrix.json").write_text(json.dumps(matrix))
        with patch.object(qualification, "CONFORMANCE", self.conformance):
            return qualification.verify()

    def test_current_declared_evidence(self):
        self.assertEqual(self.verify(self.original), (175, 195, 30, 90))

    def test_rejects_stale_or_missing_test_evidence(self):
        for field, value in [("sha256", "0" * 64), ("selector", "missing test title"),
                             ("file", "../outside.dart")]:
            with self.subTest(field=field):
                matrix = copy.deepcopy(self.original)
                matrix["evidence"]["records-graph"][field] = value
                with self.assertRaises(ValueError):
                    self.verify(matrix)

    def test_rejects_missing_rows_and_stages(self):
        for collection in ["visitors", "rules", "features"]:
            with self.subTest(collection=collection):
                matrix = copy.deepcopy(self.original)
                matrix[collection].pop()
                with self.assertRaises(ValueError):
                    self.verify(matrix)
        matrix = copy.deepcopy(self.original)
        del matrix["profiles"]["records"]["cfg"]
        with self.assertRaises(ValueError):
            self.verify(matrix)

    def test_rejects_unsubstantiated_stage_claims(self):
        for change in ["missing-evidence", "wrong-role", "complete-rule", "outside-scope"]:
            with self.subTest(change=change):
                matrix = copy.deepcopy(self.original)
                if change == "missing-evidence":
                    matrix["profiles"]["records"]["cfg"]["evidence"] = []
                elif change == "wrong-role":
                    matrix["profiles"]["records"]["cfg"]["evidence"] = ["records-runtime"]
                elif change == "complete-rule":
                    matrix["profiles"]["rule-obligations"]["cfg"].update(
                        status="tested-conservative", evidence=["records-graph"])
                else:
                    matrix["profiles"]["outside"]["lowering"].update(
                        status="tested-conservative", evidence=["records-graph"])
                with self.assertRaises(ValueError):
                    self.verify(matrix)

    def test_rejects_mixed_source_pins(self):
        for field in ["sdkRevision", "inventorySha256", "sourceIndexSha256"]:
            with self.subTest(field=field):
                matrix = copy.deepcopy(self.original)
                matrix[field] = "0" * 64
                with self.assertRaises(ValueError):
                    self.verify(matrix)

    def test_rejects_unlinked_partial_contracts(self):
        for field, value in [("constructs", ["UnknownLoop"]), ("sections", ["unrelated-rule"])]:
            with self.subTest(field=field):
                matrix = copy.deepcopy(self.original)
                matrix["cases"][0][field] = value
                with self.assertRaises(ValueError):
                    self.verify(matrix)


if __name__ == "__main__":
    unittest.main()
