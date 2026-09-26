# Splash font

`app/src/main/assets/peek-v7.html` embeds one font for the handful of characters on the loading
screen. Two things about it matter:

## It is subsetted

The whole face is about 1.5 MB; the subset covering the characters actually used is **2 KB**. That
is why the font is inlined as base64 rather than shipped as a file — there is nothing to gain from a
separate request.

The subset is valid **only for the current wording**. Change the text on the splash and the new
characters fall back to a system font without any error. After editing the splash, re-run:

```bash
pip install fonttools brotli
python3 brand/fonts/embed-splash-font.py
```

The script reads the splash, works out which characters it needs, downloads the source face, subsets
it, checks that the subset really contains the right font, and rewrites the `@font-face` in both
copies of the splash.

## It is under the SIL Open Font License

**ZCOOL KuaiLe** (站酷快乐体) by the ZCOOL KuaiLe Project Authors, licensed under the
[SIL Open Font License 1.1](https://openfontlicense.org). The full text is in
`OFL-ZCOOL-KuaiLe.txt` in this directory.

OFL permits commercial use, embedding in applications, modification, and redistribution. Two
obligations come with it, and both are met here:

- **The licence text ships with the font.** That is what `OFL-ZCOOL-KuaiLe.txt` is. Keep it as long
  as this font is embedded.
- **The font may not be sold on its own.** Not a concern for this project.

This font declares no Reserved Font Name, so the subset keeps the original name. A font that *does*
declare one must be renamed after modification — if you swap fonts, check that first.

### Why not the font that was here before

The original splash font was 上首软糖体, whose terms distinguish personal non-commercial downloads
from commercial licensing. Redistributing it inside a public repository and inside an APK needs a
grant that was never verified, and the repository's own MIT licence cannot supply one: MIT covers the
author's code, not third-party assets bundled with it. ZCOOL KuaiLe is a close visual match and its
licence settles the question outright.
