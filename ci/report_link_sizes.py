#!/usr/bin/env python3
"""Summarize which static archives (.a) were linked into a binary and how many
*allocated* bytes each contributed, by parsing an lld linker map
(`-Wl,-Map=...`).

Usage: report_link_sizes.py <map-file>

lld input-section lines look like:

    <VMA> <LMA> <Size> <Align>   path/to/libFoo.a(bar.cpp.o):(.text._foo)

We identify the owning archive and the section name from the `:(.section)`
suffix, and only count *allocated* sections (code/data that actually ends up in
the shipped, stripped .so). Non-allocated sections such as `.debug_*`,
`.comment`, `.note`, relocation and symbol tables are excluded because they are
stripped from the release library and would otherwise massively inflate the
numbers (RelWithDebInfo debug info dwarfs the real code size).

The column layout is not relied upon for non-allocated lines (lld leaves the
address blank there, shifting the columns); allocated lines always have a real
VMA so `Size` is column index 2.
"""
import collections
import re
import sys

ARCHIVE_RE = re.compile(r"(\S+\.a)\(")
SECTION_RE = re.compile(r":\((\.[^)]+)\)")

# Prefixes of sections that occupy space in the loaded/stripped image.
ALLOCATED_PREFIXES = (
    ".text",
    ".rodata",
    ".data",
    ".bss",
    ".eh_frame",
    ".gcc_except_table",
    ".init_array",
    ".fini_array",
    ".preinit_array",
    ".tdata",
    ".tbss",
    ".ARM.extab",
    ".ARM.exidx",
)


def is_allocated(section: str) -> bool:
    return section.startswith(ALLOCATED_PREFIXES)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: report_link_sizes.py <map-file>", file=sys.stderr)
        return 2

    per_archive = collections.defaultdict(int)
    per_archive_secs = collections.defaultdict(int)

    with open(sys.argv[1], errors="ignore") as fh:
        for line in fh:
            am = ARCHIVE_RE.search(line)
            if not am:
                continue
            sm = SECTION_RE.search(line)
            if not sm or not is_allocated(sm.group(1)):
                continue
            cols = line.split()
            try:
                size = int(cols[2], 16)  # Size column (allocated lines only)
            except (IndexError, ValueError):
                continue
            archive = am.group(1)
            per_archive[archive] += size
            per_archive_secs[archive] += 1

    if not per_archive:
        print("No allocated archive contributions found in map "
              "(empty or unexpected format).")
        return 0

    total = sum(per_archive.values())
    print("Allocated size per static archive (.a) linked into "
          "libBabylonNativeEmbedding.so")
    print("Only loadable code/data sections are counted; debug/reloc/symbol "
          "sections (stripped from the release .so) are excluded.\n")
    print(f"{'SIZE (KiB)':>12} {'SECTIONS':>9}  ARCHIVE (.a)")
    print(f"{'-' * 12} {'-' * 9}  {'-' * 40}")
    for archive, size in sorted(per_archive.items(), key=lambda kv: -kv[1]):
        name = archive.split("/")[-1]
        print(f"{size / 1024:12.1f} {per_archive_secs[archive]:9d}  {name}")
    print(f"{'-' * 12} {'-' * 9}  {'-' * 40}")
    print(f"{total / 1024:12.1f} {'':>9}  TOTAL allocated .a contribution "
          f"({total / (1024 * 1024):.2f} MiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
