# Three-family segment-removal smoke test

This is a reproducible, manually calibrated prototype for testing the idea of turning a photographed seven-segment \`8\` into other digits by switching off selected segments.

It deliberately tests one clean, annotated source crop per indicator family:

- LED: table 1, coordinate \`а4\`, label \`86.65\`;
- dark LCD (\`тем. ЖК\`): table 2, coordinate \`д4\`, label \`53.8\`;
- light LCD (\`Св. ЖК\`): table 1, coordinate \`е11\`, label \`58.00\`.

For each source, it generates the nine alternatives \`0,1,2,3,4,5,6,7,9\` at the annotated position of the original \`8\`.

## Run

Install the dependencies listed in \`neural-recognition/requirements-synthesis.txt\`, then run:

\`\`\`bash
python neural-recognition/synthesis/three_family_smoke_test.py \\
  --zip /path/to/indicators_corrected.zip \\
  --out /path/to/three-family-smoke-test
\`\`\`

The corrected ZIP already contains the matching \`TestIndicatorsParameters.ods\`. An external \`--ods /path/to/file.ods\` can be supplied to override the embedded annotation file.

The script crops each sample from the original 3000×2000 contact sheet in memory. It does not split or rewrite the contact sheets. It writes only the three source crops, 27 synthetic PNG crops, a CSV manifest, JSON metrics and a Markdown report into the selected output directory.

## Current limitations

- Glyph and seven-segment coordinates are manually calibrated for these three examples.
- The family-specific foreground thresholds and inpainting settings are prototype values, not learned parameters.
- The numerical foreground-reduction metric is a rough screening statistic. It counts thresholded pixels in segment zones and is not a perceptual or human-quality score.
- Success on one crop per family does not establish that the method generalizes to every display in that family.
- The test does not train a neural network.

Do not use these generated crops as a full training dataset until the calibration is checked on more source samples and held-out photographs.
