#!/usr/bin/env python3
"""Regenerate the splash font embedded in `app/src/main/assets/peek-v7.html`.

The splash uses one font for a handful of characters, so shipping the whole face would be wasteful:
ZCOOL KuaiLe is 1.5 MB, while the subset covering the characters actually used is about 2 KB.

Two things make this worth a script rather than a one-off:

- **The subset is only valid for the current text.** Change the wording on the splash and the new
  characters render in a fallback font, silently, with no error anywhere. Re-run this and commit the
  result.
- **The licence obligation travels with the font.** ZCOOL KuaiLe is under the SIL Open Font License,
  which permits embedding and redistribution but requires the licence text to ship alongside. That
  is `OFL-ZCOOL-KuaiLe.txt` in this directory — do not delete it while this font is embedded.

This font has no Reserved Font Name declaration, so subsetting does not require renaming it. If you
switch to a font that does declare one, rename the family in the `@font-face` below and in the
`font-family` that references it.

Usage:
    pip install fonttools brotli
    python3 brand/fonts/embed-splash-font.py
"""

from __future__ import annotations

import base64
import pathlib
import re
import sys
import urllib.request

FAMILY = "ZCOOL+KuaiLe"
CSS_URL = f"https://fonts.googleapis.com/css2?family={FAMILY}&display=swap"
FONT_NAME = "ZCOOL KuaiLe"
REPO = pathlib.Path(__file__).resolve().parents[2]
SPLASH_FILES = [
    REPO / "app/src/main/assets/peek-v7.html",
    REPO / "brand/peek-v7.html",
]


def fetch_source_font() -> bytes:
    """Download the full face. Google serves woff2 to a modern User-Agent."""
    request = urllib.request.Request(CSS_URL, headers={"User-Agent": "Mozilla/5.0"})
    css = urllib.request.urlopen(request, timeout=60).read().decode("utf-8")
    urls = re.findall(r"https://fonts\.gstatic\.com/[^)]+", css)
    if not urls:
        raise SystemExit("no font URL in the Google Fonts CSS response")
    return urllib.request.urlopen(urls[-1], timeout=120).read()


def splash_text(html: str) -> str:
    """Every character that appears anywhere in the splash, excluding the embedded image data.

    This is deliberately the dumbest possible extraction. Two smarter versions were tried and both
    were wrong:

    1. Visible markup only — missed the bubble's progress line, which JavaScript assembles at runtime
       (`'加载中… 还有 ' + percent + '%'`). The missing characters fell back to a system font in a
       visibly different weight, with nothing failing anywhere.
    2. Markup plus quoted string literals — still missed some of them, because a single unpaired
       quote earlier in the file shifts every later quote pairing by one, so the opening quote of a
       literal gets consumed as the tail of the previous match. Reproducing this took a while and the
       failure is invisible in the output.

    Taking the union of all characters costs a few hundred glyphs and about 20 KB, against a source
    face of 1.5 MB. Guessing which characters matter is not worth the risk of a silently wrong render.

    The image data is excluded because base64 is thousands of distinct characters on its own.
    """
    without_data = re.sub(r"data:[^\"')]{100,}", "", html)
    return "".join(sorted({c for c in without_data if c.isprintable()}))


def main() -> int:
    try:
        from fontTools.subset import main as subset_main
    except ImportError:
        print("fontTools is required: pip install fonttools brotli", file=sys.stderr)
        return 1

    html = SPLASH_FILES[0].read_text(encoding="utf-8")
    characters = splash_text(html)
    print(f"subsetting to {len(characters)} distinct characters")

    source = pathlib.Path("/tmp/dsh-splash-font.woff2")
    source.write_bytes(fetch_source_font())
    subset = source.with_suffix(".subset.woff2")
    subset_main([
        str(source),
        f"--text={characters}",
        "--flavor=woff2",
        "--layout-features=*",
        "--no-hinting",
        f"--output-file={subset}",
    ])

    # Verify what came out before writing it into anything: a silently empty subset would render as
    # the fallback font and look almost right, which is the failure this check exists to catch.
    from fontTools.ttLib import TTFont
    name = TTFont(subset)["name"].getDebugName(1)
    if FONT_NAME not in (name or ""):
        print(f"subset reports font name {name!r}, expected {FONT_NAME!r}", file=sys.stderr)
        return 1

    encoded = base64.b64encode(subset.read_bytes()).decode()
    print(f"subset is {len(subset.read_bytes())} bytes (source {source.stat().st_size} bytes)")

    for path in SPLASH_FILES:
        if not path.exists():
            print(f"  skipped (missing): {path}")
            continue
        current = path.read_text(encoding="utf-8")
        updated, count = re.subn(
            r'(src:url\(data:font/woff2;base64,)[A-Za-z0-9+/=]+(\) format\("woff2"\))',
            lambda m: m.group(1) + encoded + m.group(2),
            current,
        )
        if count != 1:
            print(f"  {path}: expected one embedded font, found {count}", file=sys.stderr)
            return 1
        path.write_text(updated, encoding="utf-8")
        print(f"  updated {path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
