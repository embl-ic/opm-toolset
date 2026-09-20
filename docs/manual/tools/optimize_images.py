"""Shrink the manual's PNG screenshots by converting them to an adaptive 256-colour palette.

Dialog screenshots are flat colour plus anti-aliased text, which a palette keeps visually
identical at a third of the size. A file is replaced only when the result is smaller.
Requires Pillow. Usage: python optimize_images.py docs/manual/img
"""
import io
import sys
from pathlib import Path

from PIL import Image


def optimise(path: Path) -> tuple[int, int]:
    before = path.stat().st_size
    with Image.open(path) as image:
        if image.mode == "P":
            return before, before
        rgb = image.convert("RGB")
    palette = rgb.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    buffer = io.BytesIO()
    palette.save(buffer, format="PNG", optimize=True)
    if buffer.tell() < before:
        path.write_bytes(buffer.getvalue())
        return before, buffer.tell()
    return before, before


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "img")
    total_before = total_after = 0
    for png in sorted(root.rglob("*.png")):
        before, after = optimise(png)
        total_before += before
        total_after += after
        print(f"{png.relative_to(root)}: {before // 1024} KB -> {after // 1024} KB")
    print(f"total {total_before // 1024} KB -> {total_after // 1024} KB")


if __name__ == "__main__":
    main()
