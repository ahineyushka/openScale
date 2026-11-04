# Three-family synthetic segment-removal smoke test

**Date:** 2026-11-04  
**Status:** successful initial proof of concept; not yet an automatic generator  
**Scope:** one clean annotated digit \`8\` from each of the three display families

## Source selection

The three contact sheets in \`indicators_corrected.zip\` were read directly. Their layout was preserved: 3000×2000 pixels, 12 columns × 16 rows, each tile 250×125 pixels. Crops were made in memory using coordinates from the ODS annotations. No contact sheet or ODS source was modified.

| Family | Table / coordinate | Original value | Changed character | Source notes |
|---|---|---:|---:|---|
| LED | 1 / \`а4\` | \`86.65\` | first digit | none |
| Dark LCD (\`тем. ЖК\`) | 2 / \`д4\` | \`53.8\` | last digit | none |
| Light LCD (\`Св. ЖК\`) | 1 / \`е11\` | \`58.00\` | second digit | none |

The ODS embedded in the ZIP and \`TestIndicatorsParameters_corrected.ods\` were byte-identical (29,323 bytes; SHA-256 \`e062d71520676767306a07dcb736fd9a49032d61780f10ad44504725f157612d\`).

## Generation method

For each source crop, the segment pattern of \`8\` is treated as the starting point. The generator determines which of the seven segments must be switched off for each target digit, builds a family-specific foreground mask only inside the corresponding calibrated segment zones, and uses OpenCV Telea inpainting to reconstruct the surrounding display surface.

| Family | Foreground mask | Dilation | Inpainting radius |
|---|---|---:|---:|
| LED | cyan HSV range | 1 | 4 px |
| Dark LCD | grayscale ≤150 | 1 | 3 px |
| Light LCD | grayscale ≥160 | 1 | 3 px |

These parameters and the glyph/segment coordinates are manually calibrated for the selected sample images.

## Result

Nine variants were generated per source crop: target digits \`0,1,2,3,4,5,6,7,9\`. Total: **27 synthetic variants plus 3 untouched source crops**.

| Family | Output labels | Mean reduction of thresholded foreground in segments intended to be off | Mean absolute change in thresholded foreground in segments intended to remain on |
|---|---|---:|---:|
| LED | \`06.65\` … \`96.65\` | 77.0% | 2.0% |
| Dark LCD | \`53.0\` … \`53.9\` | 59.7% | 1.6% |
| Light LCD | \`50.00\` … \`59.00\` | 80.5% | 2.2% |

Manual inspection of the generated contact sheets and enlarged glyph crops found:

- **LED:** the changed digits are legible. A small amount of cyan glow can remain around some segment junctions; the larger glow mask used in the earlier single-sample trial was reduced here to limit damage to segments that must remain lit.
- **Dark LCD:** the digits are legible and the inactive segment is generally reconstructed cleanly. Fine remnants can remain at adjoining segment corners.
- **Light LCD:** all nine variants are legible. The strongest remaining imperfections are small halos/soft transitions at segment junctions, rather than a completely retained segment.

The foreground-reduction statistic is only a screening metric: it counts thresholded pixels in complete rectangular segment zones, including some background pixels, so it should not be read as a visual accuracy percentage. The kept-segment statistic is a useful check against accidental removal of neighboring lit segments, but also is not a substitute for visual review.

## Conclusion

The proposed technique works on at least one real photographed example from **each of the three indicator families**. This is enough to justify continuing the pilot and generalizing the generator, but not enough to use generated samples for model training at scale.

## Next validation step

Before mass generation, calibrate and inspect several additional clean \`8\` examples per family, then difficult examples with glare, glow, blur and tilt. The next implementation should separate glyph localization from segment masking, reject samples whose seven-segment geometry cannot be calibrated reliably, and keep train/validation/test splits grouped by original source crop.
