#!/usr/bin/env python3
"""Summarize which static archives (.a) were linked into a binary and how
many bytes each contributed, by parsing an lld linker map (`-Wl,-Map=...`).

Usage: report_link_sizes.py <map-file>

The lld map has whitespace-separated columns: VMA LMA Size Align <name>.
Input lines reference their origin file, e.g.:

        0x... 0x...   0x1a0    4   path/to/libFoo.a(bar.cpp.o):(.text.foo)

We attribute each input section's Size (column 3, hex) to its owning archive.
"""
import collections
import re
import sys

ARCHIVE_RE = re.compile(r"(\S+\.a)\(")


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: report_link_sizes.py <map-file>", file=sys.stderr)
        return 2

    per_archive = collections.defaultdict(int)
    per_archive_secs = collections.defaultdict(int)

    with open(sys.argv[1], errors="ignore") as fh:
        for line in fh:
            m = ARCHIVE_RE.search(line)
            if not m:
                continue
            cols = line.split()
            try:
                vma = int(cols[0], 16)   # VMA column, hex
                size = int(cols[2], 16)  # Size column, hex
            except (IndexError, ValueError):
                continue
            # Only count allocated sections (loaded into the image). Non-allocated
            # sections (.debug_*, .comment, ...) sit at VMA 0 and are stripped from
            # the shipped release .so, so excluding them makes the sizes reflect the
            # actual on-device contribution rather than RelWithDebInfo debug bloat.
            if vma == 0:
                continue
            archive = m.group(1)
            per_archive[archive] += size
            per_archive_secs[archive] += 1

    if not per_archive:
        print("No archive contributions found in map (empty or unexpected format).")
        return 0

    total = sum(per_archive.values())
    print("Allocated (loadable) size per static archive linked into the .so")
    print("(non-allocated debug sections excluded; approximates the stripped binary)\n")
    print(f"{'SIZE (KiB)':>12} {'SECTIONS':>9}  ARCHIVE (.a)")
    print(f"{'-' * 12} {'-' * 9}  {'-' * 40}")
    for archive, size in sorted(per_archive.items(), key=lambda kv: -kv[1]):
        name = archive.split("/")[-1]
        print(f"{size / 1024:12.1f} {per_archive_secs[archive]:9d}  {name}")
    print(f"{'-' * 12} {'-' * 9}  {'-' * 40}")
    print(f"{total / 1024:12.1f} {'':>9}  TOTAL linked .a contribution "
          f"({total / (1024 * 1024):.2f} MiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
