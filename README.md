# OPM Toolset

Interactive and automated Fiji/ImageJ tools for OPM data processing.

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
  halves; the default order produces C1/C2/C3/C4 from the first two acquisition
  files and skips the rest.
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

Deskew Batch and Live Processing can write the acquisition as OME-Zarr
alongside, or instead of, TIFF. The canonical format stores the deskewed left
and right camera halves as separate channels and records the flip and the 2D XY
rigid alignment as metadata rather than baking them into the pixels, so an
alignment can be recomputed later without reprocessing a single voxel. Both
halves of every `_ChannelNNNN` file are stored, so the channel selectors above
shape only the TIFF result, never the Zarr.

`Plugins > OPM Toolset > OME-Zarr Viewer` opens such a dataset as ordinary Fiji
windows — projection movies, single time points, or virtual and materialised 5D
hyperstacks — applying the flip and alignment as you look. It needs neither BDV
nor MoBIE.

Aligned `maxZ`/`meanZ` are exact: an XY transform commutes with a projection
along Z. Aligned `maxX`/`maxY`/`meanX`/`meanY` are labelled rough overlays,
because those projections have already collapsed the axis the rotation mixes
in; they are good for judging channel overlay and not for measurement, and each
one logs the displacement bound it carries.

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
