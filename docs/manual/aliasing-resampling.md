# Aliasing Resampling

`Plugins > OPM Toolset > Aliasing Resampling` implements an experimental adaptation
of McFadden, Manton and Fiolka's OPM aliasing reconstruction
([preprint](https://doi.org/10.1101/2024.12.25.630337),
[authors' implementation](https://github.com/kevin-m-dean/OPM-Alias)).

This method exploits the tilted optical spectrum of OPM. It can preserve more
detail than interpolation when acquired planes are spaced farther apart, provided
the shifted optical spectra do not overlap. It does not recover arbitrary missing
planes or establish a safe acquisition step without measurements.

## Commands

| Command | Input and result |
| --- | --- |
| Reconstruct Raw Volume | One sparse **raw**, oblique 8/16/32-bit volume, one channel/time point. Returns a 32-bit dense raw grid, with an optional deskewed view. |
| Create Raw Slice Subset | Keeps every Nth acquisition plane at a selected zero-based offset; preserves physical scan spacing and origin. |
| Test Dense Raw Volume | Tests factors 2 through a selected maximum at **every** offset against the original dense raw reference, with linear interpolation as baseline. Saves no files automatically; export the ImageJ Results table. |
| Inspect Raw Bead PSF Spectrum | An isolated, densely sampled bead ROI. Displays a normalized kX=0 Fourier section (sum over X), and a threshold-dependent support estimate in the Log. This is not a complete 3D OTF measurement. |
| Acquisition Planner | Conditional axial-band spacing limit from measured axial OTF support, plus acquisition timing. Exposure and overhead must be supplied/measured. |

For large TIFFs, first open a representative raw ROI using OPM Data Viewer. The
reconstructor materializes the output; it is not an out-of-core whole-experiment
processor. Split channels and time points explicitly. Source data are unchanged.

## Reconstruction settings

* **Pixel size:** camera pixel size at the specimen, in micrometers.
* **Acquired scan step:** physical step between the input planes, not their
  perpendicular spacing and not the desired Cartesian Z voxel size.
* **Angle:** the same convention used by this toolset's Deskew Image command.
* **Factor:** integer acquired step / reconstructed step. For a 0.795 um input
  acquired from a 0.265 um reference grid, enter factor 3 and acquired step 0.795.
* **Background:** measured camera/background offset, subtracted before the comb
  operation and restored afterwards. Do not normalize each image independently.
* **Padding:** background-valued padding on the fine raw grid; default 32 pixels.
  Fourier filtering still produces boundary effects. Inspect edges and exclude a
  guard region in quantitative analysis; padding is not an edge-validity guarantee.
* **Cosine edge:** 0 reproduces the hard top-hat choice; a nonzero fraction softens
  ringing at the cost of bandwidth. The supplied evaluation uses 0.
* **Backend:** CPU is the default. Experimental CLIJ2-FFT uses batches of up to 16
  sections. It requires the CLIJ, CLIJ2 and CLIJx-Deconvolution dependencies and a
  working OpenCL device. Failures are reported, not silently replaced by CPU.

If the active image retains its original file information, sibling
`ExperimentalParameters.txt` provides the local pixel, angle, step and exposure.
A differing plane count triggers a prominent **unverified step** message. Cropped
or copied images may have no metadata: clearly labeled fallback values are then
0.116 um, 0.265 um, 25 degrees and 100 ms. They are starting values, not calibration.

The output spans the first through last acquired plane: `(N-1)*factor+1` planes.
No extra tail is invented. Its calibration stores reconstructed scan pitch
`acquired_step/factor`; the grid is still oblique. Deskew exactly once at that pitch.
The optional deskewed output is isotropic at the specimen camera-pixel pitch.
Negative ringing is retained in floating-point data. The Info field records
parameters and backend. Escape cancels between processing batches.

## Geometry and Fourier mask

For camera row u and scan index v, this repository maps

```
Y = p*cos(theta)*u + d*v
Z = -p*sin(theta)*u + constant
```

Here d is the fine reconstructed scan pitch. Conjugate frequencies obey
`fu = p*(cos(theta)*kY - sin(theta)*kZ)` and `fv = d*kY`.
The implementation inserts zero planes, transforms, retains

```
abs(fv - d/(p*cos(theta))*fu) < 1/(2*factor)
```

then inverse-transforms and multiplies by the factor to restore amplitude. The
mask is symmetrized at discrete Nyquist rows/columns. Factor 1 is an identity.
This is an analytically pulled-back axial mask; it is not the previous Groovy
prototype's reciprocal-vector-normal mask. The authors' MATLAB code also uses
empirical mask-angle adjustments; this implementation does not fit those angles.

The axial slab limit is `step < cot(theta)/(2*Kz)` when the relevant 3D axial
OTF support is Kz cycles/um. It is sufficient for this mask under the stated
geometry; it is not a universal optimum for every tilted/irregular OTF. Include
camera sampling, noise and a safety margin. A single kX=0 section can miss support
elsewhere; estimate the full OTF from several isolated beads for final settings.

## Acquisition and deconvolution

Digital plane removal preserves the dense scan's exposure blur. It does not test
the extra blur that can occur when a continuously moving galvo scans faster.
For scan speed v and exposure t, the blur length is `L = v*t`, and the transfer
function includes `sinc(pi*kY*L)`. Keep the exposure short enough for the required
bandwidth, or use synchronized illumination/step-and-hold acquisition.

Measure the frame period rather than assuming it equals exposure. Volume time is
approximately `planes*frame_period + flyback`. Enter the measured frame period
in the planner if exposure and readout overlap; zero selects exposure + overhead.
The continuous-scan option reports blur length and the motion OTF's first zero.
The algorithm cannot
determine a universally sufficient dwell time or photon budget.

Estimate the effective PSF with the same sheet, wavelength, depth, camera settings
and scan timing. It combines illumination, detection, pixel integration and motion
blur. Bead diameter and noisy background must be accounted for. Reconstruct first,
then deskew; any later deconvolution must use a PSF processed on the same geometry
and sampling grid. Validate deconvolution independently because Fourier ringing
can contain negative values incompatible with a Poisson Richardson-Lucy model.

## Reproducing the evaluation

The scripts use NumPy, SciPy, tifffile and matplotlib:

```powershell
python scripts/aliasing_evaluation.py --scout
python scripts/aliasing_evaluation.py --objects --output output/aliasing_evaluation/objects
python scripts/aliasing_evaluation.py --real --output output/aliasing_evaluation/real
python scripts/aliasing_ground_truth.py
python scripts/aliasing_psf_analysis.py
mvn -o '-Dtest=AliasingReconstructionTest' test
mvn -o '-Dtest=AliasingReconstructionTest' '-Dopm.test.gpu=true' test
```

The dataset paths and explicit ROI coordinates are in the evaluation script. The
four downloaded objects have no physical calibration: their simulated 0.116 um
pitch and 0.52/0.52/1.10 um Gaussian PSF FWHM are assumptions. Sharp-object controls
and comparisons on the original Cartesian object grid are also included. The
901-plane 2D-ring scan has unverified spacing; its 0.2 um model must not be used
to certify a physical resolution or acquisition limit.
