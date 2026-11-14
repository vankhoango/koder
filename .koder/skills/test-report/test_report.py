"""Summarize Gradle's JUnit XML test results: totals, and one line per failure."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    results_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "build/test-results/test")
    files = sorted(results_dir.glob("TEST-*.xml"))
    if not files:
        print(f"No test results found in {results_dir}. Run the tests first.")
        return 1

    total = skipped = 0
    failures = []
    for file in files:
        suite = ET.parse(file).getroot()
        total += int(suite.get("tests", 0))
        skipped += int(suite.get("skipped", 0))
        for case in suite.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                message = (problem.get("message") or "no message").strip().splitlines()[0][:200]
                failures.append(f"- {case.get('classname')} > {case.get('name')}: {message}")

    print(f"Tests: {total}, failed: {len(failures)}, skipped: {skipped}")
    print("\n".join(failures) if failures else "All tests passed.")
    return 0 if not failures else 2


if __name__ == "__main__":
    sys.exit(main())