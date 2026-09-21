#!/usr/bin/env python3
"""Fail when a release tag and the Maven/OSGi versions disagree."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
TAG_PATTERN = re.compile(r"v(?P<version>[0-9]+\.[0-9]+\.[0-9]+)")


def xml_text(path: Path, expression: str) -> str:
    value = ET.parse(path).getroot().findtext(expression, namespaces=MAVEN_NS)
    if not value:
        raise ValueError(f"Missing {expression!r} in {path.relative_to(ROOT)}")
    return value.strip()


def osgi_base(version: str) -> str:
    return version.removesuffix(".qualifier")


def maven_base(version: str) -> str:
    return version.removesuffix("-SNAPSHOT")


def main() -> int:
    if len(sys.argv) != 2 or not (match := TAG_PATTERN.fullmatch(sys.argv[1])):
        print("Release tag must have the form vX.Y.Z", file=sys.stderr)
        return 1

    expected = match.group("version")
    root_pom = ROOT / "pom.xml"
    actual = {
        "release.version": xml_text(root_pom, "m:properties/m:release.version"),
        "Maven project version": maven_base(xml_text(root_pom, "m:version")),
        "feature version": osgi_base(
            ET.parse(ROOT / "features/dev.astronauta.dbeaverMCP.feature/feature.xml")
            .getroot()
            .attrib["version"]
        ),
    }

    manifest = (ROOT / "plugins/dev.astronauta.dbeaverMCP/META-INF/MANIFEST.MF").read_text()
    bundle_match = re.search(r"^Bundle-Version:\s*(\S+)\s*$", manifest, re.MULTILINE)
    if not bundle_match:
        raise ValueError("Bundle-Version is missing from META-INF/MANIFEST.MF")
    actual["bundle version"] = osgi_base(bundle_match.group(1))

    root_xml = ET.parse(root_pom).getroot()
    module_names = [element.text.strip() for element in root_xml.findall("m:modules/m:module", MAVEN_NS)]
    for module_name in module_names:
        pom = ROOT / module_name / "pom.xml"
        parent_version = ET.parse(pom).getroot().findtext("m:parent/m:version", namespaces=MAVEN_NS)
        if parent_version:
            actual[f"{pom.relative_to(ROOT)} parent version"] = maven_base(parent_version.strip())

    mismatches = [f"{name}: {value}" for name, value in actual.items() if value != expected]
    if mismatches:
        print(f"Tag {sys.argv[1]} does not match release metadata ({expected} expected):", file=sys.stderr)
        print("\n".join(f"  - {item}" for item in mismatches), file=sys.stderr)
        return 1

    print(f"Release metadata matches {sys.argv[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
