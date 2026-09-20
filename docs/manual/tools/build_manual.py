"""Build the OPM Toolset manual: one web page and one single-file offline copy per chapter.

    python docs/manual/tools/build_manual.py

Sources live in docs/manual/src:

    live-deskew.html    a chapter; the <main> content only, no <html> or <head>
    _*.html             a shared fragment, pulled in with <!--#include _name.html-->

Markers understood in a source file:

    <!--#title: ...-->          the browser title (defaults to the first <h1>)
    <!--#description: ...-->    the meta description
    <!--#include file.html-->   insert a fragment from src/
    <!--#web-only--> ... <!--#end-->        kept in the web page only
    <!--#offline-only--> ... <!--#end-->    kept in the offline copy only

Outputs:

    docs/manual/<chapter>.html                       links assets/manual.css and img/...
    docs/manual/download/opm-<chapter>-manual.html   stylesheet and images inlined, opens
                                                     from a USB stick with no network

The table of contents, the page shell and the footer are generated here, so a chapter source
stays readable prose. Standard library only.
"""
from __future__ import annotations

import base64
import datetime as dt
import mimetypes
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]          # docs/manual
REPO = ROOT.parents[1]
SRC = ROOT / "src"
CSS = ROOT / "assets" / "manual.css"
FAVICON = ("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'>"
           "<text y='13' font-size='14'>\U0001F52C</text></svg>")


def version() -> str:
    pom = (REPO / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<artifactId>OPM_Toolset</artifactId>\s*<version>([^<]+)</version>", pom)
    return match.group(1) if match else "unknown"


def includes(text: str) -> str:
    def replace(match: re.Match) -> str:
        name = match.group(1).strip()
        return includes((SRC / name).read_text(encoding="utf-8"))
    return re.sub(r"<!--#include\s+([^>]+?)-->", replace, text)


def variant(text: str, offline: bool) -> str:
    """Keep the blocks meant for this variant, drop the others."""
    keep, drop = ("offline-only", "web-only") if offline else ("web-only", "offline-only")
    text = re.sub(rf"<!--#{keep}-->(.*?)<!--#end-->", lambda m: m.group(1), text, flags=re.S)
    text = re.sub(rf"<!--#{drop}-->.*?<!--#end-->", "", text, flags=re.S)
    return text


def toc(body: str) -> str:
    rows = []
    for level, anchor, label in re.findall(r"<h([23])\s+id=\"([^\"]+)\"[^>]*>(.*?)</h\1>", body, re.S):
        text = re.sub(r"<[^>]+>", "", label).strip()
        text = re.sub(r"\s+", " ", text).replace(" · ", " · ")
        css_class = ' class="sub"' if level == "3" else ""
        rows.append("<li" + css_class + '><a href="#' + anchor + '">' + text + "</a></li>")
    return ("<nav class=\"toc\"><p>On this page</p><ol>" + "".join(rows) + "</ol></nav>") if rows else ""


def data_uri(path: Path) -> str:
    kind = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    return f"data:{kind};base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def inline_images(body: str) -> tuple[str, int]:
    total = 0

    def replace(match: re.Match) -> str:
        nonlocal total
        source = match.group(2)
        if source.startswith(("data:", "http:", "https:")):
            return match.group(0)
        path = ROOT / source
        if not path.is_file():
            print(f"  missing image: {source}", file=sys.stderr)
            return match.group(0)
        total += path.stat().st_size
        return f'{match.group(1)}="{data_uri(path)}"'

    return re.sub(r"(src|href)=\"(img/[^\"]+)\"", replace, body), total


SHELL = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<link rel="icon" href="{favicon}">
{style}
</head>
<body>
<div class="page">
<main>
{body}
<p class="meta">OPM Toolset {version} · {kind} generated {date} ·
screenshots produced by <code>docs/manual/tools/live_deskew_tour.groovy</code> against the
example acquisition, so they are the real dialogs.</p>
</main>
{toc}
</div>
<script>
/* Mark the section the reader is in. No dependencies, and harmless if it does not run. */
(function () {{
    var links = [].slice.call(document.querySelectorAll('nav.toc a'));
    if (!links.length || !('IntersectionObserver' in window)) return;
    var byId = {{}};
    links.forEach(function (a) {{ byId[a.getAttribute('href').slice(1)] = a; }});
    var seen = new IntersectionObserver(function (entries) {{
        entries.forEach(function (entry) {{
            var link = byId[entry.target.id];
            if (!link) return;
            if (entry.isIntersecting) {{
                links.forEach(function (a) {{ a.classList.remove('active'); }});
                link.classList.add('active');
            }}
        }});
    }}, {{ rootMargin: '-10% 0px -80% 0px' }});
    Object.keys(byId).forEach(function (id) {{
        var target = document.getElementById(id);
        if (target) seen.observe(target);
    }});
}})();
</script>
</body>
</html>
"""


def build(source: Path, offline: bool) -> Path:
    text = source.read_text(encoding="utf-8")
    title = (re.search(r"<!--#title:\s*(.*?)-->", text) or [None, None])[1]
    description = (re.search(r"<!--#description:\s*(.*?)-->", text) or [None, None])[1]
    body = re.sub(r"<!--#(title|description):.*?-->", "", text)
    body = variant(includes(body), offline)
    if not title:
        heading = re.search(r"<h1[^>]*>(.*?)</h1>", body, re.S)
        title = re.sub(r"<[^>]+>", "", heading.group(1)).strip() if heading else source.stem
    contents = toc(body)
    inlined = 0
    if offline:
        body, inlined = inline_images(body)
        style = "<style>\n" + CSS.read_text(encoding="utf-8") + "\n</style>"
        out = ROOT / "download" / f"opm-{source.stem}-manual.html"
    else:
        style = '<link rel="stylesheet" href="assets/manual.css">'
        out = ROOT / source.name
    page = SHELL.format(title=title, description=(description or title).replace('"', "&quot;"),
                        favicon=FAVICON, style=style, body=body.strip(), toc=contents,
                        version=version(), date=dt.date.today().isoformat(),
                        kind="offline copy" if offline else "web version")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page, encoding="utf-8")
    note = f" ({inlined // 1024} KB of images inlined)" if offline else ""
    print(f"{out.relative_to(REPO)}: {len(page.encode('utf-8')) // 1024} KB{note}")
    return out


def main() -> None:
    pages = sorted(p for p in SRC.glob("*.html") if not p.name.startswith("_"))
    if not pages:
        raise SystemExit(f"no chapter sources in {SRC}")
    (ROOT / ".nojekyll").write_text("", encoding="utf-8")
    for page in pages:
        build(page, offline=False)
        build(page, offline=True)


if __name__ == "__main__":
    main()
