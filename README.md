# OPM Toolset

Interactive and automated Fiji/ImageJ tools for OPM data processing.

## User manual

An illustrated manual, with a worked example on a real acquisition, is published from
`docs/manual` at
[embl-ic.github.io/opm-toolset/manual/](https://embl-ic.github.io/opm-toolset/manual/):

- [Live Deskew](https://embl-ic.github.io/opm-toolset/manual/live-deskew.html) - deskewing an
  acquisition while it is being written, bead calibration of the camera halves, the live
  preview and the viewer. It also carries the shared background sections: why deskewing is
  needed, the geometry, and the file formats.
- Each chapter has a single-file offline copy under `docs/manual/download/`.

The pages are generated from `docs/manual/src` by `docs/manual/tools/build_manual.py`, and the
screenshots by `docs/manual/tools/run_tour.ps1` (see `docs/manual/tools/README.md`).

## Batch Processing

The `Plugins > OPM Toolset > Batch Processing` menu separates the workflow into
independent operations:

- **Deskew** keeps the established `Deskew Batch` implementation unchanged.
- **Generate Projection Image** accepts raw OPM or deskewed volumes and creates
  maximum/mean X, Y, and Z projections plus optional time-lapse stacks.
- **Channel Operation** splits mirrored left/right camera halves, flips and
  aligns every right-hand channel with a 2 x 3 SIFT CSV matrix, and combines
  matching `_Channel0001`, `_Channel0002`, ... files. Eight output selectors
  let the user reorder or skip the left/right sources from `_Channel0001`
  through `_Channel0004`, so a four-file acquisition reaches all eight camera
  halves; the reset default exposes only the left/right pair from
  `_Channel0001`, leaves the remaining slots skipped, and does not combine files.
- The **flip and align** selector can either keep the left half unchanged and
  align the flipped right half to it, or keep the right half unchanged and
  align the flipped left half using the automatically mirrored matrix.
- **Deconvolution** batch-deconvolves volumes with a measured PSF or generates
  an averaged PSF from bead volumes.

Channel Operation also accepts existing ImageJ channel hyperstacks. In that
case C2, C4, ... are treated as the flipped right-hand optical channels and are
aligned with the same matrix when `flip right half` is selected. With `flip
left half`, C2/C4 remain unchanged and the inverse alignment is applied to
C1/C3. Z stacks and time-lapse dimensions are preserved.

The optimized TIFF reader selects all available logical processors
automatically; there is no thread-count field in the batch/live dialogs.

## OME-Zarr

Deskew Batch and **Deskew Live** can write the acquisition as OME-Zarr
alongside, or instead of, TIFF. The canonical format stores the deskewed left
and right camera halves as separate channels and records the flip and the 2D XY
rigid alignment as metadata rather than baking them into the pixels, so an
alignment can be recomputed later without reprocessing a single voxel. Both
halves of every `_ChannelNNNN` file are stored, so the channel selectors above
shape only the TIFF result, never the Zarr.

The generated deskewed grid uses the camera XY pitch on all three axes. For
example, an acquisition with 0.116 µm camera pixels is stored with voxel size
`[0.116, 0.116, 0.116]` µm; the angle and stage step determine its transformed
extent, not a second output sampling interval.

`Plugins > OPM Toolset > OPM Data Viewer` opens such a dataset as ordinary
Fiji windows — a whole time-lapse, a single time point, or a projection movie,
virtual or materialised — applying the flip and alignment as you look. It needs
neither BDV nor MoBIE.

TIFF-only acquisitions can be reinterpreted the same way, without changing their
files. The `_ChannelNNNN` series of one acquisition are listed as **one** dataset,
and **Channels / side** and **Runtime view** mean for them what they mean for an
OME-Zarr: side by side is the width exactly as written, and the other views split
each series into its camera halves, mirror the chosen side, and apply an optional
legacy or tagged alignment CSV — all while planes are read, so nothing on disk is
touched. **Channel setup** holds what those two cannot say: the order behind
`configured channel order`, which side the flip mirrors, the sampling, and the CSV.
Time points are paired by their `_TimeNNNNNN` number, so a live overlay waits for
every selected source rather than shifting channels, and a second acquisition
channel that starts arriving mid-run joins the dataset as it appears. Viewer
windows keep independent setups, so two representations of the same files can be
open at once. Deskewed volumes and Z projections carry this exact runtime
transform; X and Y projections have collapsed the axis the halves lie along and
open as written.

Tick **Live update** to follow a dataset that is still being written: the
viewer polls its commit marker and extends every virtual view it opened as time
points are committed, without rebuilding the windows. Only committed time points
are ever read, so a half-written volume cannot be shown.

**Region** restricts what is opened to a box. Draw a rectangle on any open view
and press *update region with active ROI* rather than typing coordinates. Only
the chunks the box covers are read, so browsing a small region of a large
acquisition costs the region and not the volume. The region describes the next
view opened; windows already on screen keep the one they were opened with.

Writing pixels back out lives with the other conversions, at
`Plugins > OPM Toolset > Utilities > Export OME-Zarr region to TIFF`.

Aligned `maxZ`/`meanZ` are exact: an XY transform commutes with a projection
along Z. Aligned `maxX`/`maxY`/`meanX`/`meanY` are labelled rough overlays,
because those projections have already collapsed the axis the rotation mixes
in; they are good for judging channel overlay and not for measurement, and each
one logs the displacement bound it carries.

## Channel alignment

`Plugins > OPM Toolset > Utilities > Channel Alignment` (window *Align Channel of OPM Data*;
each section folds under its heading, and the sections scroll when they do not fit the screen)
accepts one bead TIFF or a folder. Its scrollable list lets you include only the relevant TIFFs and optionally
`ExperimentalParameters.txt`; each selected TIFF contributes `left`, `right`, and
`whole` source choices. With acquisition metadata selected, the command streams each
large TIFF directly into a deskewed max-Z projection instead of materialising the bead
volume in memory.

Right halves are mirrored first. The first chosen optical source is the fixed reference,
and each later source receives an independent rigid transform into that reference. A
classic `_Channel0001-left`/`_Channel0001-right` result remains a plain 2 x 3 CSV for
backward compatibility. Larger selections are saved as one tagged CSV containing the
reference and every source-specific matrix; Deskew Batch, Deskew Live, and OPM Data Viewer
all understand that form. The detector uses bead-scale DoG/LoG centres with sub-pixel
refinement and robust rigid fitting, while SIFT or correlation supplies the coarse estimate.
The dialog separates Display, Interest point detection, and Alignment. Display accepts raw
volumes (deskew + max Z), already deskewed volumes (max Z only), and existing 2-D projections;
**generate/update** opens every auto-contrasted channel in an independently movable window and
a separate multichannel overlay. Blank channels remain visible and borrow the display range of
the other half from the same file when possible. Every channel has a Fiji-style Red, Green,
Blue, Cyan, Magenta, Yellow, or Gray LUT; its Display checkbox affects only the overlay, while
flip affects both views.

Interest points are drawn as translucent open circles, so the bead stays visible and overlapping
channels blend. Each channel row shows or hides its points in that channel's own window and sets
their color and whether they are numbered (**label**, in both views); a separate **display interest
points in overlay** row decides which channels' points the overlay shows.
**auto detection** restores the automatic labelled correspondences, and **modify** turns the
points into an active Fiji multipoint ROI in that channel window so points can be added, removed,
dragged, or handled with normal ROI operations. The overlay drawing mode also accepts labelled,
draggable points on color or grayscale channels; the mouse wheel changes the active channel.
Alignment is recomputed from three or more matching labels per channel. Manual X/Y/rotation
controls use one-decimal spinners (0.1 per button, 0.5 per wheel step). The overlay follows them
as they change, moving image pixels without changing any interest points, and **Reset** zeros the
row and returns the channel to its transform before manual adjustment (the interest-point fit, or
none after **Remove all alignment transform**). Unticking manual adjustment sets the rows aside
without losing them; the saved matrix is always what the overlay shows. The first row is also
adjustable: because the saved format
keeps channel 1 as the identity reference, its correction is represented by the inverse change
to every other channel (for example, channel 1 right by 1 px stores the others left by 1 px).
**Remove all alignment transform** makes every matrix identity so the overlay shows only the
original/display-flipped halves. Positive rotation is counter-clockwise about the fixed
geometric image centre.

## Building

Requires a Java 8 toolchain and Maven:

```
mvn clean package
```

The result is `target/OPM_Toolset-<version>.jar`; drop it into the Fiji
`plugins` folder. GPU acceleration needs a working CLIJ2/OpenCL device, and the
OME-Zarr viewer needs `n5-zarr` 2.0.1 or newer in the Fiji installation.

## Licence

MIT — see [LICENSE](LICENSE).

EMBL ICLM service team — <Ziqiang.Huang@embl.de>
