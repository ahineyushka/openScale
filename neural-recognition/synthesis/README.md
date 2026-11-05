# Three-family segment-removal smoke test

A reproducible, manually calibrated prototype for switching off segments of a photographed seven-segment `8` in real scale-display images.

It tests one annotated source crop per display family:

- LED: table 1, coordinate `а4`, label `86.65`;
- dark LCD (`тем. ЖК`): table 2, coordinate `д4`, label `53.8`;
- light LCD (`Св. ЖК`): table 1, coordinate `е11`, label `58.00`.

Nine variants are produced per source: target digits `0,1,2,3,4,5,6,7,9`.

## Current segment-erasure method

The previous approach used rectangles and/or inpainting. That did not follow the true shape of the seven bars and could leave blurred or glowing remnants.

The current method works with seven individually calibrated polygons whose edges follow the visible separator gaps between adjacent segments.

1. Each polygon is a seed region for one of the seven segments.
2. The family-specific foreground mask detects the lit segment pixels and their one-pixel fringe inside the glyph window.
3. Every candidate pixel outside or at an overlapping polygon joint is assigned to the nearest segment polygon. This creates a disjoint seven-region partition: one pixel belongs to only one segment.
4. The complete region assigned to a segment that is switched off is filled with a constant local background RGB colour sampled from the empty holes inside the source `8`.

No inpainting, blur, interpolation, texture synthesis, or overlapping rectangular fill masks are used. The source contact sheets and ODS file remain unchanged; source crops are extracted in memory.

## Run

Install dependencies from `neural-recognition/requirements-synthesis.txt`, then run:

```bash
python neural-recognition/synthesis/three_family_smoke_test.py \
  --zip /path/to/indicators_corrected.zip \
  --out /path/to/three-family-smoke-test
```

The corrected ZIP contains the matching `TestIndicatorsParameters.ods`. An external `--ods /path/to/file.ods` may be supplied to override it.

The script writes three source crops, 27 synthetic PNG crops, a CSV manifest, JSON metrics and a Markdown report.

## Limitations

- The seven polygons and glyph windows are manually calibrated for these three specific examples.
- Nearest-polygon assignment is an approximation of the visible divider lines; it needs checking on additional examples and unusual segment designs.
- A flat fill cannot reconstruct true display gradients, glare or reflections hidden underneath a lit segment.
- A clean result on one sample per family does not establish generalization to all displays in that family.
- This is a generator test, not a neural-network training run.

Do not use this tiny test set as a full training corpus. First validate the polygons on more `8` samples from each family, then difficult examples with glare, glow, blur and tilt.
