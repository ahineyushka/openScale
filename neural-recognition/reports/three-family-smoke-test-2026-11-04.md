# Three-family flat segment-fill test

**Date:** 2026-11-05  
**Status:** flat-fill prototype replaces the earlier blurred inpainting method  
**Scope:** one clean annotated digit `8` from each of the three display families

## Source selection

The three contact sheets in `indicators_corrected.zip` were read directly. Their layout was preserved: 3000×2000 pixels, 12 columns × 16 rows, each tile 250×125 pixels. Crops were extracted in memory using the ODS annotations. No source contact sheet or ODS file was modified.

| Family | Table / coordinate | Original value | Changed character |
|---|---|---:|---|
| LED | 1 / `а4` | `86.65` | first digit |
| Dark LCD (`тем. ЖК`) | 2 / `д4` | `53.8` | last digit |
| Light LCD (`Св. ЖК`) | 1 / `е11` | `58.00` | second digit |

The ODS embedded in the ZIP and `TestIndicatorsParameters_corrected.ods` were byte-identical (29,323 bytes).

## Corrected fill method

The earlier version used OpenCV Telea inpainting. That reconstructed pixels by interpolation and left visible softness/blur, especially at the upper and lower horizontal segments of the light LCD.

The new method does not use inpainting at all:

1. Sample background RGB from the empty upper and lower holes inside the photographed `8`.
2. Use the upper sample for `a/b/f`, the lower sample for `d/c/e`, and the mean of both for `g`.
3. Replace the calibrated rectangle of each switched-off segment with its constant local background RGB.
4. Preserve shared pixels at a junction when the neighbouring segment remains lit. If both adjoining segments are switched off, fill their intersection as well.

No blur, interpolation or texture synthesis is applied. The result is a hard, flat fill. The source image's true hidden background cannot be recovered exactly, so the constant colour may be slightly different from local gradients or reflections.

### Sampled background colours

RGB values from the selected real source crops:

| Family | Upper | Middle | Lower |
|---|---:|---:|---:|
| LED | `34, 49, 116` | `30, 47, 118` | `25, 45, 121` |
| Dark LCD | `145, 170, 149` | `160, 183, 164` | `174, 196, 180` |
| Light LCD | `26, 67, 172` | `28, 66, 180` | `30, 65, 189` |

## Results

Nine variants were generated per source crop: target digits `0,1,2,3,4,5,6,7,9`. Total: **27 synthetic variants plus 3 untouched source crops**.

| Family | Output labels | Mean reduction of thresholded foreground in off-segment zones | Mean change in kept-segment zones |
|---|---|---:|---:|
| LED | `06.65` … `96.65` | 83.2% | 0.0% |
| Dark LCD | `53.0` … `53.9` | 85.5% | 0.0% |
| Light LCD | `50.00` … `59.00` | 86.3% | 0.0% |

The reduction values are not visual accuracy percentages. Some thresholded pixels are intentionally retained at shared joints when those pixels also belong to a neighbouring segment that must remain lit. The kept-segment metric confirms that the current joint-protection logic did not remove thresholded pixels from the kept segment zones in these three selected samples.

## Conclusion and limits

The revised method removes the blurred inpainting step and replaces switched-off segment footprints with constant background colour. It has been exercised on one LED, one dark LCD and one light LCD sample.

This is still a manually calibrated proof of concept. It does not yet establish that the method works on arbitrary samples in each family. The next validation should test several more `8` examples per family, with special attention to the light LCD upper/lower horizontal segments and to blur, glare, reflections and tilted displays. The generator should reject samples when segment geometry or clean background sampling cannot be established reliably.
