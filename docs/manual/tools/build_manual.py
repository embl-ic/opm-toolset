"""Build the OPM Toolset manual: one web page and one single-file offline copy per chapter.

    python docs/manual/tools/build_manual.py            build every page
    python docs/manual/tools/build_manual.py --check    change nothing; exit 1 if a page is stale against
                                                        its source, or a local link or fragment in a built
                                                        page leads nowhere

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
stays readable prose. A block left open, an <!--#end--> closing none, or any marker left in a page
stops the build. An offline copy's links to chapters point at the neighbouring offline copies, since
it sits in download/ beside them. A page whose only change would be the date is left as it is.
Standard library only.
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


def check_blocks(text: str, name: str) -> None:
    """Every web-only or offline-only block is closed by its own <!--#end-->, and blocks do not nest."""
    opened = None
    for match in re.finditer(r"<!--#(web-only|offline-only|end)-->", text):
        kind, line = match.group(1), text.count("\n", 0, match.start()) + 1
        if kind == "end":
            if opened is None:
                raise ValueError(f"{name}:{line}: <!--#end--> closes no block")
            opened = None
        elif opened is not None:
            raise ValueError(f"{name}:{line}: <!--#{kind}--> inside the {opened[0]} block opened at line {opened[1]}")
        else:
            opened = (kind, line)
    if opened is not None:
        raise ValueError(f"{name}:{opened[1]}: <!--#{opened[0]}--> is never closed by <!--#end-->")


def includes(text: str) -> str:
    def replace(match: re.Match) -> str:
        name = match.group(1).strip()
        fragment = (SRC / name).read_text(encoding="utf-8")
        check_blocks(fragment, name)
        return includes(fragment)
    return re.sub(r"<!--#include\s+([^>]+?)-->", replace, text)


def variant(text: str, offline: bool) -> str:
    """Keep the blocks meant for this variant, drop the others."""
    keep, drop = ("offline-only", "web-only") if offline else ("web-only", "offline-only")
    text = re.sub(rf"<!--#{keep}-->(.*?)<!--#end-->", lambda m: m.group(1), text, flags=re.S)
    text = re.sub(rf"<!--#{drop}-->.*?<!--#end-->", "", text, flags=re.S)
    return text


def offline_links(body: str, chapters: set[str]) -> str:
    """An offline copy sits in download/ beside the others: a chapter, or its copy, is the neighbouring copy."""
    body = re.sub(r'href="download/(opm-[\w-]+-manual\.html)', r'href="\1', body)

    def chapter(match: re.Match) -> str:
        return f'href="opm-{match.group(1)}-manual.html' if match.group(1) in chapters else match.group(0)

    return re.sub(r'href="([\w-]+)\.html', chapter, body)


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
            raise FileNotFoundError(f"missing manual image: {source}; regenerate the relevant tour stage")
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


DATE = re.compile(r"(generated )\d{4}-\d{2}-\d{2}( ·)")


def undated(page: str) -> str:
    """The page without the date it was generated on, which is the only thing a rebuild may change by itself."""
    return DATE.sub(r"\1(date)\2", page)


def chapters() -> list[Path]:
    pages = sorted(p for p in SRC.glob("*.html") if not p.name.startswith("_"))
    if not pages:
        raise SystemExit(f"no chapter sources in {SRC}")
    return pages


def render(source: Path, offline: bool) -> tuple[Path, str, int]:
    text = source.read_text(encoding="utf-8")
    check_blocks(text, source.name)
    title = (re.search(r"<!--#title:\s*(.*?)-->", text) or [None, None])[1]
    description = (re.search(r"<!--#description:\s*(.*?)-->", text) or [None, None])[1]
    body = re.sub(r"<!--#(title|description):.*?-->", "", text)
    body = variant(includes(body), offline)
    left = re.findall(r"<!--#.*?-->", body, re.S)
    if left:
        raise ValueError(f"{source.name}: build markers left in the {'offline' if offline else 'web'} page: {left}")
    if not title:
        heading = re.search(r"<h1[^>]*>(.*?)</h1>", body, re.S)
        title = re.sub(r"<[^>]+>", "", heading.group(1)).strip() if heading else source.stem
    contents = toc(body)
    inlined = 0
    if offline:
        body = offline_links(body, {p.stem for p in chapters()})
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
    page = "\n".join(line.rstrip() for line in page.split("\n"))
    return out, page, inlined


def build(source: Path, offline: bool) -> Path:
    out, page, inlined = render(source, offline)
    if out.is_file() and undated(out.read_text(encoding="utf-8")) == undated(page):
        print(f"{out.relative_to(REPO)}: unchanged")
        return out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page, encoding="utf-8")
    note = f" ({inlined // 1024} KB of images inlined)" if offline else ""
    print(f"{out.relative_to(REPO)}: {len(page.encode('utf-8')) // 1024} KB{note}")
    return out


def broken_links(page: Path) -> list[str]:
    """Every local link and image of a built page names a file that exists, and an id that file has."""
    problems = []
    ids: dict[Path, set[str]] = {}
    for target in re.findall(r'(?:href|src)="([^"]+)"', page.read_text(encoding="utf-8")):
        if target.startswith(("http:", "https:", "data:", "mailto:")):
            continue
        path, _, fragment = target.partition("#")
        file = (page.parent / path).resolve() if path else page
        if not file.is_file():
            problems.append(f"{page.relative_to(REPO)}: {target} leads to no file")
        elif fragment and file.suffix == ".html":
            if file not in ids:
                ids[file] = set(re.findall(r'\bid="([^"]+)"', file.read_text(encoding="utf-8")))
            if fragment not in ids[file]:
                problems.append(f"{page.relative_to(REPO)}: {target} names no id in {file.name}")
    return problems


def check() -> int:
    """Rebuild in memory and compare: a stale or missing page, a build error or a broken link fails."""
    problems = []
    for source in chapters():
        for offline in (False, True):
            try:
                out, page, _ = render(source, offline)
            except (ValueError, FileNotFoundError) as error:
                problems.append(str(error))
                continue
            if not out.is_file():
                problems.append(f"{out.relative_to(REPO)}: missing; run build_manual.py")
            elif undated(out.read_text(encoding="utf-8")) != undated(page):
                problems.append(f"{out.relative_to(REPO)}: stale against src/{source.name}; run build_manual.py")
            else:
                problems.extend(broken_links(out))
    for problem in problems:
        print(problem)
    print(f"manual check: {'FAILED, ' + str(len(problems)) + ' problem(s)' if problems else 'every page current, every local link resolves'}")
    return 1 if problems else 0


def main() -> None:
    if sys.argv[1:] == ["--check"]:
        raise SystemExit(check())
    if sys.argv[1:]:
        raise SystemExit(__doc__)
    (ROOT / ".nojekyll").write_text("", encoding="utf-8")
    for page in chapters():
        build(page, offline=False)
        build(page, offline=True)


if __name__ == "__main__":
    main()
