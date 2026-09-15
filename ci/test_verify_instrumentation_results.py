from pathlib import Path
import shutil
import unittest

from ci.verify_instrumentation_results import EXPECTED_CLASSES, verify


class VerifyInstrumentationResultsTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path("ci/.verify-test-work")
        shutil.rmtree(self.directory, ignore_errors=True)
        self.directory.mkdir()

    def tearDown(self):
        shutil.rmtree(self.directory, ignore_errors=True)

    def write(self, body: str) -> Path:
        path = self.directory / "results.xml"
        path.write_text(body, encoding="utf-8")
        return path

    def complete_xml(self) -> str:
        cases = "".join(
            f'<testcase classname="{class_name}" name="required"/>'
            for class_name in EXPECTED_CLASSES
        )
        return f"<testsuite>{cases}</testsuite>"

    def test_accepts_complete_clean_results(self):
        tests, classes = verify(self.write(self.complete_xml()))
        self.assertEqual(len(EXPECTED_CLASSES), tests)
        self.assertEqual(EXPECTED_CLASSES, classes)

    def test_rejects_zero_tests(self):
        with self.assertRaisesRegex(ValueError, "zero"):
            verify(self.write("<testsuite/>"))

    def test_rejects_missing_suite(self):
        with self.assertRaisesRegex(ValueError, "missing required"):
            verify(self.write('<testsuite><testcase classname="other" name="x"/></testsuite>'))

    def test_rejects_skip(self):
        xml = self.complete_xml().replace("/>", "><skipped/></testcase>", 1)
        with self.assertRaisesRegex(ValueError, "skipped=1"):
            verify(self.write(xml))

    def test_rejects_failure(self):
        xml = self.complete_xml().replace("/>", "><failure/></testcase>", 1)
        with self.assertRaisesRegex(ValueError, "failures=1"):
            verify(self.write(xml))

    def test_rejects_malformed_xml(self):
        with self.assertRaisesRegex(ValueError, "malformed"):
            verify(self.write("<testsuite>"))


if __name__ == "__main__":
    unittest.main()
