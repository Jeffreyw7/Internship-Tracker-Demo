"""Fail-closed checks shared by the CI jobs; Python standard library only."""
import argparse
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


POSTGRES_SUITE = "com.campus.tracker.PostgresPrivilegesIT"
POSTGRES_SCENARIOS = {
    "freshDatabaseHasOnlyRequiredRuntimePrivileges",
    "legacyUpgradePreservesRecordsAndMigrationHistory",
    "verifierRejectsEveryMissingRequiredDmlPrivilege",
}


def require_jobs(needs):
    """A skipped, cancelled, missing, or failed dependency cannot satisfy CI."""
    if not isinstance(needs, dict):
        raise ValueError("Expected an object containing the required job results")
    for name in ("verify", "containers"):
        job = needs.get(name)
        if not isinstance(job, dict) or job.get("result") != "success":
            raise ValueError(f"Required job {name} did not finish with success: {job!r}")


def read_report(path):
    try:
        suite = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise ValueError(f"Cannot read JUnit report {path}: {error}") from error
    if suite.tag != "testsuite" or not suite.get("name"):
        raise ValueError(f"Expected a named Maven testsuite in {path}")
    counts = {}
    for attribute in ("tests", "skipped", "failures", "errors"):
        raw = suite.get(attribute, "")
        if not raw.isascii() or not raw.isdecimal():
            raise ValueError(f"Invalid {attribute} count in {path}: {raw!r}")
        counts[attribute] = int(raw)
    if any(counts[key] for key in ("skipped", "failures", "errors")):
        raise ValueError(f"Tests were skipped or unsuccessful in {path}: {counts}")
    cases = suite.findall("testcase")
    if len(cases) != counts["tests"]:
        raise ValueError(f"Test count does not match actual cases in {path}")
    if any(case.find(tag) is not None for case in cases for tag in ("skipped", "failure", "error")):
        raise ValueError(f"An actual test case was skipped or unsuccessful in {path}")
    return suite.get("name"), counts["tests"], {case.get("name") for case in cases}


def require_reports(backend):
    """Require real unskipped unit tests and the named PostgreSQL IT suite."""
    target = Path(backend) / "target"
    unit_paths = sorted((target / "surefire-reports").glob("TEST-*.xml"))
    integration_paths = sorted((target / "failsafe-reports").glob("TEST-*.xml"))
    if not unit_paths or not integration_paths:
        raise ValueError("Both Surefire and Failsafe TEST-*.xml reports are required")
    unit = [read_report(path) for path in unit_paths]
    integration = [read_report(path) for path in integration_paths]
    if sum(count for _, count, _ in unit) <= 0:
        raise ValueError("No backend unit tests were executed")
    postgres = [(count, names) for name, count, names in integration if name == POSTGRES_SUITE]
    postgres_count = sum(count for count, _ in postgres)
    executed = set().union(*(names for _, names in postgres))
    missing = POSTGRES_SCENARIOS - executed
    if missing:
        raise ValueError(f"Required PostgreSQL scenarios did not execute: {sorted(missing)}")
    if postgres_count <= 0:
        raise ValueError(f"No unskipped {POSTGRES_SUITE} tests were executed")
    return sum(count for _, count, _ in unit), postgres_count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="check", required=True)
    subparsers.add_parser("required", help="Read exact-success job results from CI_NEEDS")
    reports = subparsers.add_parser("reports", help="Validate Maven unit and PostgreSQL reports")
    reports.add_argument("backend", type=Path)
    args = parser.parse_args()
    try:
        if args.check == "required":
            require_jobs(json.loads(os.environ.get("CI_NEEDS", "")))
            print("Both required jobs succeeded.")
        else:
            unit_count, postgres_count = require_reports(args.backend)
            print(f"Verified {unit_count} backend tests and {postgres_count} PostgreSQL privilege tests; none skipped.")
    except (ValueError, OSError) as error:
        print(f"CI check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

