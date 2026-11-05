# Three-family segment-removal smoke test

This is a reproducible, manually calibrated prototype for turning a photographed seven-segment `8` into other digits by switching off selected segments.

It tests one clean, annotated source crop per indicator family:

- LED: table 1, coordinate `а4`, label `86.65`;
- dark LCD (`тем. ЖК`): table 2, coordinate `д4`, label `53.8`;
- light LCD (`Св. ЖК`): table 1, coordinate `е11`, label `58.00`.

For each source, it generates nine alternatives (`0,1,2,3,4,5,6,7,9`) at the annotated position of the original `8`.

## Erasure method

The former OpenCV inpainting step has been removed. It created soft, blurred remnants in areas where a segment had been switched off.

The current method instead:
1. estimates a flat RGB background colour from the two empty holes inside the photographed `8`;
2. assigns upper, middle and lower background colours to the corresponding segments;
3. replaces the complete calibrated segment rectangle with that constant colour;
4. preserves a shared junction only when the neighbouring segment remains lit; a junction is filled when both adjoining segments are switched off.

There is no blur, inpainting, interpolation or texture synthesis. This intentionally produces a hard, flat fill. The sampled background colour can still differ slightly from local display gradients or reflections.

## Run

Install the dependencies listed in `neural-recognition/requirements-synthesis.txt`, then run:

```bash
python neural-recognition/synthesis/three_family_smoke_test.py \
  --zip /path/to/indicators_corrected.zip \
  --out /path/to/three-family-smoke-test
```

The corrected ZIP already contains the paired `TestIndicatorsParameters.ods`. An external `--ods /path/to/file.ods` can override the embedded annotation file.

The script reads each original 3000×2000 contact sheet as-is and extracts the selected crop in memory. It does not split or rewrite the contact sheets. It writes three source crops, 27 synthetic PNG crops, a CSV manifest, JSON metrics and a Markdown report.

## Limitations

- Glyph and segment coordinates are manually calibrated for these three examples.
- One source sample per family does not prove that the calibration generalizes to the rest of that family.
- The foreground-reduction metric is only a screening statistic. Pixels at shared joints may intentionally remain when they belong to a segment that must stay lit.
- A constant fill cannot recreate real spatial gradients, glare or texture hidden beneath an illuminated segment.
- This test does not train a neural network.

Do not use the generated crops as a full training dataset until the calibration has been checked on more source examples, especially samples with glare, glow, blur and tilt.
