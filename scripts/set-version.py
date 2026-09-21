#!/usr/bin/env python3
"""Set every Maven and OSGi project version used by a release."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
MODULE_POMS = (
    "plugins/dev.astronauta.dbeaverMCP/pom.xml",
    "features/dev.astronauta.dbeaverMCP.feature/pom.xml",
    "repository/dev.astronauta.dbeaverMCP.repository/pom.xml",
    "tests/pom.xml",
)


def replace(path: str, pattern: str, replacement: str) -> None:
    file_path = ROOT / path
    original = file_path.read_text()
    updated, count = re.subn(pattern, replacement, original, count=1, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"Expected one version field matching {pattern!r} in {path}")
    file_path.write_text(updated)


def main() -> int:
    if len(sys.argv) != 2 or not VERSION_PATTERN.fullmatch(sys.argv[1]):
        print(f"Usage: {Path(sys.argv[0]).name} X.Y.Z", file=sys.stderr)
        return 2

    version = sys.argv[1]
    maven_version = f"{version}-SNAPSHOT"
    replace(
        "pom.xml",
        r"(<artifactId>dbeaver-mcp-parent</artifactId>\s*<version>)[^<]+",
        rf"\g<1>{maven_version}",
    )
    replace("pom.xml", r"(^\s*<release\.version>)[^<]+", rf"\g<1>{version}")

    for pom in MODULE_POMS:
        replace(
            pom,
            r"(<artifactId>dbeaver-mcp-parent</artifactId>\s*<version>)[^<]+",
            rf"\g<1>{maven_version}",
        )

    replace(
        "features/dev.astronauta.dbeaverMCP.feature/feature.xml",
        r'(\s*version=")[^"]+("\s*\n\s*provider-name=)',
        rf"\g<1>{version}.qualifier\g<2>",
    )
    replace(
        "plugins/dev.astronauta.dbeaverMCP/META-INF/MANIFEST.MF",
        r"^Bundle-Version:\s*\S+",
        f"Bundle-Version: {version}.qualifier",
    )

    print(f"Set project version to {version}; create tag v{version} from the release commit")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
