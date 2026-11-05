# Three-family segment-removal test — polygon partition

**Date:** 2026-11-05  
**Iteration:** polygons along segment separators; no blur/inpainting  
**Scope:** one clean annotated digit `8` from each of the three display families

## Source samples

| Family | Table / coordinate | Source value | Changed position |
|---|---|---:|---|
| LED | 1 / `а4` | `86.65` | first digit |
| Dark LCD (`тем. ЖК`) | 2 / `д4` | `53.8` | last digit |
| Light LCD (`Св. ЖК`) | 1 / `е11` | `58.00` | second digit |

The three original contact sheets and ODS remain untouched. Crops are extracted in memory.

## Erasure method

The preceding flat-fill iteration still used rectangular segment footprints, which did not follow the actual ends and divider cuts of the seven bars. The new iteration uses seven manually calibrated polygons for each display family.

Each polygon marks one bar. Lit pixels and the one-pixel fringe near the polygon boundaries are assigned to the nearest polygon. Shared/touching regions at joints are resolved into seven disjoint regions. Pixels assigned to a segment being switched off are replaced by a constant local background colour. No inpainting, blur, interpolation or texture reconstruction is performed.

## Results

Nine target digits were generated from each source (`0,1,2,3,4,5,6,7,9`): **27 synthetic variants and 3 original crops**.

| Family | Mean thresholded foreground removed from switched-off regions | Mean change in regions that remain lit |
|---|---:|---:|
| LED | 100.0% | 0.0% |
| Dark LCD | 100.0% | 0.0% |
| Light LCD | 100.0% | 0.0% |

Metrics are calculated against the same segment partition built from the original source crop. They confirm that all thresholded pixels in regions assigned to switched-off segments were removed, and no thresholded pixels were lost from regions assigned to lit segments. They are not a substitute for human visual inspection.

## Interpretation and limitations

The polygon-based partition is a better fit for the requested operation than rectangles: the entire segment region is assigned up to the divider geometry, its neighbouring segment has a separate ownership region, and the fill is a flat background colour with no blur.

The current coordinates are still manually calibrated for only one sample per family. Nearest-polygon assignment approximates the visible divider lines and may need recalibration for different digit proportions, perspective, glare or halos. It does not recover hidden background texture or gradients.

## Next validation

Test additional `8` examples across each family, including both horizontal and vertical bars being switched off. Check segment-boundary ownership at enlarged resolution, then validate the same generator on tilted, blurred and reflective examples. Reject samples whose segment geometry cannot be assigned reliably.
