# Manual tooling

Three steps, each re-runnable on its own. Nothing here ships in the jar.

```powershell
# 1. screenshots and result figures: drives the real dialogs in a separate Fiji
./docs/manual/tools/run_tour.ps1

# 2. the "why deskew" figures, straight from a raw acquisition
python docs/manual/tools/make_basics_figures.py E:\OPM\3_timelapse_0

# 3. the pages: docs/manual/*.html and the single-file copies in docs/manual/download/
python docs/manual/tools/build_manual.py
```

## What each file does

| File | Purpose |
| --- | --- |
| `run_tour.ps1` | Compiles `Help.java` on its own (so the help screenshot shows the source tree's text, not the installed jar's), backs up the persisted OPM preferences, starts Fiji on the tour, restores the preferences, then compresses the images. |
| `tour_boot.groovy` | Loads the tour inside a try/catch. Fiji shows a script's compile error in a window and waits for it, which an unattended run never sees; this way the error lands in the report. |
| `live_deskew_tour.groovy` | The tour: prepares the example data, opens the dialogs, runs the beads and the time lapse over TCP/IP, captures every window and writes the result figures. |
| `make_basics_figures.py` | Raw camera frame, and the same volume before/after deskew, computed from the raw file with numpy. Needs `numpy`, `tifffile`, `pillow`. |
| `build_manual.py` | Assembles `src/*.html` (+ `_*.html` fragments) into the web pages and the offline copies. Standard library only. |
| `optimize_images.py` | Palette-compresses the screenshots; keeps a file only where it gets smaller. Needs `pillow`. |

## How the screenshots are made

Windows are **painted into an image** (`Component.printAll`) rather than grabbed from the
screen. That looks exactly as Fiji draws it, needs no free or unlocked desktop, and is
reproducible; the OS title bar is not part of it, so the tour draws one neutral title strip on
every capture. Highlights are placed from the **live component geometry**, so they follow the
controls when a dialog's layout changes - no pixel coordinates anywhere.

## Stages

`-Stages prepare,help,beads,align,sample` (the default), any subset, in this order:

| Stage | What it does | Roughly |
| --- | --- | --- |
| `prepare` | Hard-links the example acquisitions into `-TutorialRoot` (no extra disk space) and clears previous results. | seconds |
| `help` | The Live Deskew help box with the manual link. | seconds |
| `beads` | A full Live Deskew run of the bead acquisition, replayed over TCP/IP, with its preview. | ~1 min |
| `align` | `Utilities > Channel Alignment` on the beads; writes `beads-alignment.csv` and the before/after figure. | ~1 min |
| `sample` | The time lapse: setup screenshots, the run, the status panel, previews, viewer, dialogs, result figures. | ~6 min for 6 time points |

Re-run `prepare,align,sample` to refresh anything that comes from the run itself - the status
panel, the previews, the viewer and the result figures. Running `sample` alone over results
that already exist is **not** enough: every file is recognised from the manifest and never
queued, so nothing is processed and no live preview is raised, and the tour only refreshes the
setup screenshots. It says so in the report and keeps the previous shots rather than writing
empty ones.

## Requirements

- Fiji with the toolset installed, at `-Fiji` (default `D:\Fiji\fiji-windows-x64.exe`).
- The example acquisitions under `-DataRoot`: `3_timelapse_0` and `4_beads_for_overlay_0`.
- A TCP port (`-Port`, default 5020) that nothing else is listening on.
- Roughly 5 GB free on the disk holding `-TutorialRoot` for six time points of results.

The tour halts its own Fiji when it finishes, deliberately: none of that session's window
positions or recent commands should overwrite the user's own preferences.
