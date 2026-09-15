#!/usr/bin/env python3
"""Fail unless Android instrumentation XML contains the complete bridge suite."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

EXPECTED_CLASSES = {
    "com.babylonjs.embedding.tests.RuntimeSmokeTest",
    "com.babylonjs.embedding.tests.GlobalPropertiesTest",
    "com.babylonjs.embedding.tests.ObjectPropertiesTest",
    "com.babylonjs.embedding.tests.InvocationTest",
    "com.babylonjs.embedding.tests.BridgeErrorsTest",
    "com.babylonjs.embedding.tests.BridgeLifecycleTest",
    "com.babylonjs.embedding.tests.BridgeHandleSafetyTest",
}


def verify(path: Path) -> tuple[int, set[str]]:
    files = [path] if path.is_file() else sorted(path.rglob("*.xml"))
    if not files:
        raise ValueError(f"no instrumentation XML files found under {path}")

    tests = 0
    failures = 0
    errors = 0
    skipped = 0
    classes: set[str] = set()
    parsed = 0
    for file in files:
        try:
            root = ET.parse(file).getroot()
        except ET.ParseError as exc:
            raise ValueError(f"malformed XML {file}: {exc}") from exc
        parsed += 1
        cases = root.findall(".//testcase")
        if root.tag == "testcase":
            cases = [root]
        for case in cases:
            tests += 1
            class_name = case.get("classname") or case.get("class")
            if class_name:
                classes.add(class_name)
            failures += len(case.findall("failure"))
            errors += len(case.findall("error"))
            skipped += len(case.findall("skipped"))

    if parsed == 0 or tests == 0:
        raise ValueError("instrumentation produced zero test cases")
    if failures or errors or skipped:
        raise ValueError(
            f"instrumentation was not clean: tests={tests}, failures={failures}, "
            f"errors={errors}, skipped={skipped}"
        )
    missing = EXPECTED_CLASSES - classes
    if missing:
        raise ValueError("missing required suites: " + ", ".join(sorted(missing)))
    return tests, classes


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    try:
        tests, classes = verify(args.results)
    except ValueError as exc:
        print(f"instrumentation result verification failed: {exc}", file=sys.stderr)
        return 1
    print(f"verified {tests} tests across {len(classes)} required suites")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
