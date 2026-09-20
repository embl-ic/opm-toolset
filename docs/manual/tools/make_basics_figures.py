"""Figures for the "why deskew" and "geometry" sections of the manual.

These come straight from a raw acquisition, so they show what the camera delivers rather than
a drawing of it. Nothing here needs Fiji or the plugin: the deskew is the same 2-D shear the
plugin applies in 3-D (Transform.deskew), evaluated on the side view, which is exact because
the shear leaves camera X untouched.

    python make_basics_figures.py [E:/OPM/3_timelapse_0] [docs/manual/img/data]

Written images:
    raw-camera-frame.jpg      one raw frame, the two camera halves marked
    raw-side-vs-deskewed.jpg  the same volume seen along X, before and after the deskew
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import tifffile
from PIL import Image, ImageDraw, ImageFont

FONT = "C:/Windows/Fonts/segoeuib.ttf"


def parameters(folder: Path) -> dict[str, float]:
    """xy pixel size, scan step and OPM angle, read as the plugin reads them."""
    values: dict[str, float] = {}
    for line in (folder / "ExperimentalParameters.txt").read_text().splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        try:
            values[key.strip()] = float(value.strip())
        except ValueError:
            pass
    step = values["um per galvo DU"] * values["Galvo DUs per step"]
    return {"xy": values["Pixel size at object /um"], "step": step, "angle": values["Tilt angle"]}


def stretch(plane: np.ndarray, low: float = 1.0, high: float = 99.9) -> np.ndarray:
    plane = plane.astype(np.float32)
    lo, hi = np.percentile(plane, low), np.percentile(plane, high)
    return np.clip((plane - lo) / max(hi - lo, 1e-6), 0, 1)


def to_image(plane: np.ndarray) -> Image.Image:
    return Image.fromarray((plane * 255).astype(np.uint8)).convert("RGB")


def font(size: int) -> ImageFont.ImageFont:
    try:
        return ImageFont.truetype(FONT, size)
    except OSError:
        return ImageFont.load_default()


def deskew_side(side: np.ndarray, xy: float, step: float, angle_degrees: float) -> np.ndarray:
    """The plugin's deskew, restricted to the plane it actually shears.

    A camera row y of frame f lands at lateral y' = y cos(theta) + f step / xy and height
    z' = (h - y) sin(theta), in units of camera pixels; sampling the inverse map keeps the
    output grid isotropic, which is what makes the deskewed volume isotropic at xy.
    """
    frames, height = side.shape
    theta = np.radians(angle_degrees)
    cos, sin = np.cos(theta), np.sin(theta)
    shear = step / xy
    out_h = int(round(height * sin))
    out_w = int(round(height * cos + frames * shear))
    zs = np.arange(out_h)[:, None]
    ys = np.arange(out_w)[None, :]
    row = height - zs / sin                      # camera row that reaches this height
    frame = (ys - row * cos) / shear             # frame that reaches this lateral position
    inside = (row >= 0) & (row <= height - 1) & (frame >= 0) & (frame <= frames - 1)
    r0 = np.clip(np.floor(row), 0, height - 2).astype(np.int32)
    f0 = np.clip(np.floor(frame), 0, frames - 2).astype(np.int32)
    dr = np.clip(row - r0, 0, 1)
    df = np.clip(frame - f0, 0, 1)
    r0 = np.broadcast_to(r0, inside.shape)
    dr = np.broadcast_to(dr, inside.shape)
    f0 = np.broadcast_to(f0, inside.shape)
    df = np.broadcast_to(df, inside.shape)
    source = side.astype(np.float32)
    value = (source[f0, r0] * (1 - df) * (1 - dr) + source[f0 + 1, r0] * df * (1 - dr)
             + source[f0, r0 + 1] * (1 - df) * dr + source[f0 + 1, r0 + 1] * df * dr)
    return np.where(inside, value, 0.0)


def scale_bar(image: Image.Image, um_per_pixel: float, um: int) -> None:
    draw = ImageDraw.Draw(image)
    length = int(round(um / um_per_pixel))
    thickness = max(3, image.width // 200)
    x, y = image.width - length - 14, image.height - 14 - thickness
    draw.rectangle([x, y, x + length, y + thickness], fill=(255, 255, 255))
    text = f"{um} \u00b5m"
    glyphs = font(14)
    width = draw.textlength(text, font=glyphs)
    draw.text((x + (length - width) / 2, y - 20), text, fill=(255, 255, 255), font=glyphs)


def caption(image: Image.Image, text: str, at: tuple[int, int] = (10, 8)) -> None:
    draw = ImageDraw.Draw(image)
    draw.text(at, text, fill=(255, 255, 255), font=font(15), stroke_width=2, stroke_fill=(0, 0, 0))


def titled(panel: Image.Image, text: str, width: int | None = None) -> Image.Image:
    """The panel with its caption above it, on white, so nothing covers the data."""
    strip = 24
    canvas = Image.new("RGB", (width or panel.width, panel.height + strip), (255, 255, 255))
    draw = ImageDraw.Draw(canvas)
    draw.text((2, 3), text, fill=(30, 30, 30), font=font(14))
    canvas.paste(panel, (0, strip))
    return canvas


def raw_frame_figure(volume: np.ndarray, out: Path) -> None:
    """The two camera halves, shown as the maximum along the scan.

    One frame of a 451 frame scan is a single oblique slice and mostly empty; the maximum over
    the scan shows what each half carries. The halves are stretched separately because they see
    different emission bands, and this acquisition's are orders of magnitude apart.
    """
    frames, height, width = volume.shape
    scan_max = volume.max(axis=0)
    middle = width // 2
    plane = np.concatenate([stretch(scan_max[:, :middle], 1, 99.9), stretch(scan_max[:, middle:], 1, 99.9)], axis=1)
    image = to_image(plane).resize((width // 2, height // 2), Image.LANCZOS)
    draw = ImageDraw.Draw(image)
    draw.line([(image.width // 2, 0), (image.width // 2, image.height)], fill=(214, 40, 40), width=2)
    caption(image, "left half of the camera", (12, 8))
    caption(image, "right half of the camera", (image.width // 2 + 12, 8))
    figure = titled(image, f"one raw file: {width} x {height} px x {frames} frames, maximum along the scan;"
                           " each half has its own display range")
    figure.save(out / "raw-camera-frame.jpg", quality=88)
    print("raw-camera-frame.jpg", figure.size)


def side_figure(volume: np.ndarray, geometry: dict[str, float], out: Path) -> None:
    """The same volume seen along the camera's X axis, before and after the deskew.

    Both panels share one display range and one scale, so the difference in the figure is the
    geometry and nothing else.
    """
    frames, height, width = volume.shape
    side = volume[:, :, :width // 2].max(axis=2)        # max along camera X of the left half
    shear = geometry["step"] / geometry["xy"]
    low, high = np.percentile(side, 1), np.percentile(side, 99.9)

    def normalise(plane: np.ndarray) -> np.ndarray:
        return np.clip((plane.astype(np.float32) - low) / max(high - low, 1e-6), 0, 1)

    raw = to_image(normalise(side.T))                   # rows = camera Y, columns = frame
    raw = raw.resize((int(round(raw.width * shear)), raw.height), Image.LANCZOS)
    warped = deskew_side(side, geometry["xy"], geometry["step"], geometry["angle"])
    deskewed = to_image(normalise(warped))

    half = 0.5
    raw = raw.resize((int(raw.width * half), int(raw.height * half)), Image.LANCZOS)
    deskewed = deskewed.resize((int(deskewed.width * half), int(deskewed.height * half)), Image.LANCZOS)
    scale_bar(deskewed, geometry["xy"] / half, 20)
    figure_width = max(raw.width, deskewed.width)
    top = titled(raw, "raw: frames stacked as acquired, one oblique plane each - the sample is sheared", figure_width)
    bottom = titled(deskewed, f"deskewed at {geometry['angle']:.1f}\u00b0: sample coordinates,"
                              f" isotropic {geometry['xy']:.3f} \u00b5m voxels", figure_width)

    gap = 6
    canvas = Image.new("RGB", (figure_width, top.height + gap + bottom.height), (255, 255, 255))
    canvas.paste(top, (0, 0))
    canvas.paste(bottom, (0, top.height + gap))
    canvas.save(out / "raw-side-vs-deskewed.jpg", quality=88)
    print("raw-side-vs-deskewed.jpg", canvas.size)


def main() -> None:
    folder = Path(sys.argv[1] if len(sys.argv) > 1 else "E:/OPM/3_timelapse_0")
    out = Path(sys.argv[2] if len(sys.argv) > 2 else Path(__file__).resolve().parents[1] / "img/data")
    out.mkdir(parents=True, exist_ok=True)
    geometry = parameters(folder)
    print("geometry", geometry)
    raw = sorted(folder.glob("*_Time000001_Channel0001_*.tif*"))[0]
    with tifffile.TiffFile(raw) as handle:
        volume = handle.series[0].asarray()
    volume = volume.reshape(-1, volume.shape[-2], volume.shape[-1])
    print("raw volume", volume.shape, raw.name)
    raw_frame_figure(volume, out)
    side_figure(volume, geometry, out)


if __name__ == "__main__":
    main()
