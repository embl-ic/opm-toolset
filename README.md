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
  matching `_Channel0001`, `_Channel0002`, ... files. Six output selectors let
  the user reorder or skip the left/right sources from `_Channel0001` through
  `_Channel0003`; the default order produces C1/C2/C3/C4 from the first two
  acquisition files and skips output channels 5 and 6.
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

EMBL ICLM service team — <Ziqiang.Huang@embl.de>
