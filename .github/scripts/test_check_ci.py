"""Exercise real CI policies against controlled job results and JUnit reports."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from check_ci import require_jobs, require_reports


class RequiredJobsTests(unittest.TestCase):
    def test_accepts_only_both_successful_jobs(self):
        require_jobs({"verify": {"result": "success"}, "containers": {"result": "success"}})

    def test_rejects_each_non_success_result_for_either_job(self):
        for job in ("verify", "containers"):
            for result in ("failure", "cancelled", "skipped", "", None, "Success"):
                needs = {"verify": {"result": "success"}, "containers": {"result": "success"}}
                needs[job]["result"] = result
                with self.subTest(job=job, result=result), self.assertRaises(ValueError):
                    require_jobs(needs)

    def test_rejects_absent_jobs_or_results(self):
        for needs in ({}, {"verify": {"result": "success"}},
                      {"containers": {"result": "success"}},
                      {"verify": {}, "containers": {"result": "success"}},
                      {"verify": {"result": "success"}, "containers": None}, [], None):
            with self.subTest(needs=needs), self.assertRaises(ValueError):
                require_jobs(needs)


class MavenReportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.backend = Path(self.temp.name)
        self.unit = self.backend / "target/surefire-reports/TEST-com.campus.tracker.ApiTest.xml"
        self.postgres = self.backend / "target/failsafe-reports/TEST-com.campus.tracker.PostgresPrivilegesIT.xml"
        self.write(self.unit, '<testsuite name="com.campus.tracker.ApiTest" tests="1" skipped="0" failures="0" errors="0"><testcase name="api"/></testsuite>')
        self.write(self.postgres, '<testsuite name="com.campus.tracker.PostgresPrivilegesIT" tests="3" skipped="0" failures="0" errors="0"><testcase name="freshDatabaseHasOnlyRequiredRuntimePrivileges"/><testcase name="legacyUpgradePreservesRecordsAndMigrationHistory"/><testcase name="verifierRejectsEveryMissingRequiredDmlPrivilege"/></testsuite>')

    @staticmethod
    def write(path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def test_accepts_executed_unit_and_required_postgres_tests(self):
        require_reports(self.backend)

    def test_rejects_missing_unit_or_postgres_reports(self):
        for path in (self.unit, self.postgres):
            original = path.read_text(encoding="utf-8")
            path.unlink()
            with self.subTest(report=path.name), self.assertRaises(ValueError):
                require_reports(self.backend)
            self.write(path, original)

    def test_rejects_unrelated_integration_suite_in_place_of_required_suite(self):
        self.write(self.postgres, '<testsuite name="com.campus.tracker.UnrelatedIT" tests="1" skipped="0" failures="0" errors="0"><testcase name="unrelated"/></testsuite>')
        with self.assertRaises(ValueError):
            require_reports(self.backend)

    def test_rejects_zero_tests_in_either_required_category(self):
        for path in (self.unit, self.postgres):
            original = path.read_text(encoding="utf-8")
            name = "com.campus.tracker.PostgresPrivilegesIT" if path == self.postgres else "com.campus.tracker.ApiTest"
            self.write(path, f'<testsuite name="{name}" tests="0" skipped="0" failures="0" errors="0"/>')
            with self.subTest(report=path.name), self.assertRaises(ValueError):
                require_reports(self.backend)
            self.write(path, original)

    def test_rejects_skipped_failed_or_errored_reports(self):
        for attribute, child in (("skipped", "skipped"), ("failures", "failure"), ("errors", "error")):
            for path in (self.unit, self.postgres):
                original = path.read_text(encoding="utf-8")
                name = "com.campus.tracker.PostgresPrivilegesIT" if path == self.postgres else "com.campus.tracker.ApiTest"
                counts = {"tests": 2, "skipped": 0, "failures": 0, "errors": 0, attribute: 1}
                attributes = " ".join(f'{key}="{value}"' for key, value in counts.items())
                self.write(path, f'<testsuite name="{name}" {attributes}><testcase name="passed"/><testcase name="bad"><{child}/></testcase></testsuite>')
                with self.subTest(attribute=attribute, report=path.name), self.assertRaises(ValueError):
                    require_reports(self.backend)
                self.write(path, original)

    def test_rejects_skipped_case_even_if_aggregate_counters_claim_success(self):
        self.write(self.postgres, '<testsuite name="com.campus.tracker.PostgresPrivilegesIT" tests="1" skipped="0" failures="0" errors="0"><testcase name="skipped"><skipped/></testcase></testsuite>')
        with self.assertRaises(ValueError):
            require_reports(self.backend)

    def test_rejects_missing_actual_cases_even_if_counter_claims_tests_ran(self):
        self.write(self.postgres, '<testsuite name="com.campus.tracker.PostgresPrivilegesIT" tests="1" skipped="0" failures="0" errors="0"/>')
        with self.assertRaises(ValueError):
            require_reports(self.backend)

    def test_rejects_malformed_xml_and_counts(self):
        for xml in ('not xml', '<testsuites/>', '<testsuite tests="1"/>',
                    '<testsuite tests="-1" skipped="0" failures="0" errors="0"/>',
                    '<testsuite tests="many" skipped="0" failures="0" errors="0"/>'):
            self.write(self.postgres, xml)
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                require_reports(self.backend)


    def test_rejects_each_missing_postgres_scenario_even_when_other_tests_pass(self):
        original = self.postgres.read_text(encoding="utf-8")
        for name in ("freshDatabaseHasOnlyRequiredRuntimePrivileges",
                     "legacyUpgradePreservesRecordsAndMigrationHistory",
                     "verifierRejectsEveryMissingRequiredDmlPrivilege"):
            xml = original.replace(f'<testcase name="{name}"/>', '').replace('tests="3"', 'tests="2"')
            self.write(self.postgres, xml)
            with self.subTest(missing=name), self.assertRaises(ValueError):
                require_reports(self.backend)

    def test_report_cli_preserves_success_and_failure_exit_codes(self):
        script = Path(__file__).with_name("check_ci.py")
        result = subprocess.run([sys.executable, str(script), "reports", str(self.backend)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.postgres.unlink()
        result = subprocess.run([sys.executable, str(script), "reports", str(self.backend)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1, result.stdout)


class RequiredCliTests(unittest.TestCase):
    def run_check(self, needs):
        env = dict(os.environ)
        env.pop("CI_NEEDS", None)
        if needs is not None:
            env["CI_NEEDS"] = needs
        return subprocess.run([sys.executable, str(Path(__file__).with_name("check_ci.py")), "required"],
                              env=env, capture_output=True, text=True)

    def test_cli_succeeds_for_both_successful_jobs(self):
        result = self.run_check(json.dumps({"verify": {"result": "success"}, "containers": {"result": "success"}}))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_cli_fails_closed_for_invalid_or_missing_json(self):
        for needs in (None, "", "not json", "null", "{}"):
            with self.subTest(needs=needs):
                result = self.run_check(needs)
                self.assertEqual(result.returncode, 1, result.stdout)

    def test_cli_fails_for_failed_cancelled_or_skipped_jobs(self):
        for outcome in ("failure", "cancelled", "skipped"):
            with self.subTest(outcome=outcome):
                result = self.run_check(json.dumps({"verify": {"result": outcome}, "containers": {"result": "success"}}))
                self.assertEqual(result.returncode, 1, result.stdout)

if __name__ == "__main__":
    unittest.main()

