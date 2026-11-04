# Neural scale-display recognition pilot — implementation plan

**Status:** planning  
**Scope:** isolated research prototype; no changes to the openScale Android application  
**Base branch:** `feat/camera-scale-recognition`  
**Pilot branch:** `feat/neural-scale-recognition-pilot`

## 1. Goals

Build and evaluate a small neural network that reads weight values from photographs of scale displays. The first milestone is a reproducible offline experiment using the existing corrected indicator photo tables and their ODS annotations.

The pilot must answer four questions:

1. Can real photographs of seven-segment displays be converted into useful synthetic training examples by switching off selected segments of a photographed digit `8`?
2. Can a compact model learn to read the complete displayed value, including the decimal point?
3. Can model training run on GitHub-hosted infrastructure so that a powerful local computer is not required?
4. Does the resulting model generalize beyond the training crops sufficiently to justify a later Android integration investigation?

## 2. Repository isolation and data handling

- Keep all pilot code under `neural-recognition/`.
- Do not change Android application code, dependencies, existing camera-recognition code, or production build configuration during this pilot.
- Keep the original photo tables and ODS annotation file unchanged.
- Treat the three contact sheets as the canonical source images. Do **not** split them into hundreds of committed image files.
- Read annotations from the ODS file and extract individual display crops by coordinates at runtime, in memory.
- Do not commit the original photo tables, generated image collections, Dropbox credentials, or other dataset payloads to the repository.
- Store dataset access configuration in GitHub Actions secrets when remote training is enabled. Use a read-only, narrowly scoped Dropbox credential if the data is fetched from Dropbox.
- Record source file versions/checksums and annotation/parser versions in each experiment report so a run can be reproduced.

## 3. Synthetic-data strategy

### 3.1 Source of synthetic examples

Use photographs that contain a clearly lit digit `8`. A seven-segment `8` contains all seven active segments, so selected segments can be switched off to create the other decimal digits.

The generator should:
1. Read the source contact sheet and annotation row.
2. Extract the relevant display crop in memory using its coordinate.
3. Locate the character cell(s) containing an `8`.
4. Apply a segment mask for a target digit.
5. Restore the underlying display background where a segment is switched off.
6. Update the target text label consistently.
7. Return the transformed crop and label directly to the training pipeline.

The initial trial archive demonstrates the method on one blue LED example. It is a proof of concept, not yet a general segment-removal algorithm.

### 3.2 Display families

Do not assume one mask or one background-restoration method works for every display. Evaluate separately:
- LED;
- dark LCD;
- light LCD.

The generator must account for differences in segment appearance, background texture, glow/halos, reflections, brightness, and blur. A sample should be skipped rather than corrupted when its segment geometry or background cannot be handled reliably.

### 3.3 Generate on the fly, not as a large image archive

**Preferred approach:** generate synthetic variants in memory during training.

Benefits:
- no large directory of generated images;
- no unnecessary Git or artifact storage consumption;
- new target digits and controlled variations can be sampled across epochs;
- the same source photos remain the compact canonical dataset.

Use deterministic random seeds for repeatable runs. Keep only a small, explicitly selected debug/validation set when saved outputs are needed to inspect the generator. Do not persist every generated sample.

### 3.4 Prevent data leakage

Split the original source examples into training, validation, and test groups **before** synthetic generation. All synthetic derivatives of one original sample must remain in the same split. Near-duplicate photographs or crops from the same source/contact-sheet capture must not cross split boundaries.

Keep a real-photo test set that is never augmented or used for training. Report results on that set separately from synthetic validation results.

## 4. Model and training experiment

Start with a small CNN-based sequence recognizer (CNN + CTC) that accepts a display crop and predicts the complete character sequence, including the decimal point.

Keep the model deliberately small and CPU-friendly. Avoid object-detection models and heavy computer-vision dependencies in the first milestone. The initial experiment can operate on pre-cropped display regions; locating a display within a full photograph is a separate later problem.

Training should combine:
- original real training crops with their verified labels;
- on-the-fly synthetic variants derived from eligible real `8` samples;
- restrained augmentations that reflect plausible camera/display differences.

Do not treat the synthetic data as a replacement for real photographs. Evaluate whether synthetic augmentation improves recognition on untouched real photographs.

Track at least:
- exact full-string accuracy;
- character error rate;
- decimal-point errors;
- results by display family (LED, dark LCD, light LCD);
- results by annotated image difficulty where available;
- model size and CPU inference time;
- cases where the recognizer should abstain rather than return an unsafe value.

## 5. GitHub-hosted training

### 5.1 Workflow design

Add a manually triggered GitHub Actions workflow only after the local training entry point is reproducible. The workflow should:
1. check out the pilot branch;
2. install pinned Python dependencies;
3. retrieve the source tables and ODS from the configured private data location;
4. validate checksums and the annotation manifest;
5. run a short data/generator sanity check;
6. train on a standard Linux CPU runner;
7. evaluate the model;
8. upload only compact experiment outputs as workflow artifacts.

Do not upload the original dataset or a full synthetic image dump as workflow artifacts.

### 5.2 Start with CPU; measure before considering GPU

Use a standard hosted CPU runner for the first benchmark. Measure data-loading time, generator throughput, epoch duration, peak memory, and total runtime on a small fixed run.

Do not make GPU availability a prerequisite. Investigate paid/GPU runners or external compute only if measured CPU training time makes iteration impractical. Before relying on any runner size, cost, or quota, verify the current GitHub plan and runner limits; do not hard-code assumptions based on an earlier estimate.

### 5.3 Reproducibility and artifacts

Each run should record:
- source revision and experiment configuration;
- Python/framework/dependency versions;
- dataset checksums and split manifest version;
- random seed;
- training and validation metrics;
- model file and export metadata.

Retain only the best checkpoint, final compact model, metrics, and concise logs. Avoid committing trained weights or generated data to Git unless a later project decision explicitly requires it.

## 6. Milestones and exit criteria

### Milestone 1 — repository and data loader
- Add this plan and pilot directory.
- Implement an ODS/contact-sheet loader that preserves the original table layout.
- Validate coordinate-to-label mapping and report excluded/ambiguous records.
- Produce a split manifest grouped by original sample.

**Exit:** every included crop has a traceable source coordinate and verified label; no image table has been modified.

### Milestone 2 — multi-family synthetic generator
- Generalize segment masks beyond the single LED proof of concept.
- Implement background restoration and quality checks for LED, dark LCD, and light LCD.
- Generate examples on demand with deterministic seeds.
- Save only a small inspection set when needed.

**Exit:** automated sanity checks pass and a human-reviewed sample from each display family shows no systematic synthetic artifacts. Exclude unsupported cases transparently.

### Milestone 3 — baseline recognizer
- Implement a small CNN-CTC model and a reproducible CPU training command.
- Train a baseline on real data, then compare it with real-plus-synthetic training.
- Evaluate on the untouched real-photo test set.

**Exit:** a report compares the baseline and synthetic-augmented model; no claim of generalization is made without held-out real-photo results.

### Milestone 4 — GitHub Actions training
- Add a manual workflow.
- Verify private data retrieval without exposing credentials.
- Run a short CPU benchmark, then a complete pilot training run if practical.
- Publish compact artifacts and metrics.

**Exit:** a run can be started from GitHub and completed without using the user's local computer for training.

### Milestone 5 — deployment feasibility
- Export the selected model to LiteRT/TensorFlow Lite.
- Measure model size, CPU inference latency, and accuracy after any quantization.
- Document Android integration requirements without integrating into openScale yet.

**Exit:** evidence is sufficient to decide whether a separate Android integration proposal is justified.

## 7. Non-goals for this pilot

- No modification of production openScale code.
- No Android dependency additions.
- No cloud inference service.
- No requirement to detect the display in an uncropped full-scale photograph in the first model experiment.
- No mass generation or storage of individual image files.
- No claim that artificial segment removal alone creates realistic data for every display type.

## 8. Current known risks

- Segment geometry and background restoration may differ substantially between LED and LCD families.
- Some annotated `8` characters may be too small, blurred, reflected, clipped, or otherwise unsuitable for reliable segment editing.
- Synthetic transformations may introduce artifacts that teach the model unrealistic patterns.
- The current sample trial covers only one LED display; it must not be mistaken for a validated general-purpose generator.
- A held-out test split that is not grouped by original sample can produce misleadingly high accuracy.
- Hosted CPU performance and workflow quotas must be measured on the actual GitHub plan before committing to long training runs.

## 9. Immediate next tasks

1. Add the pilot directory structure and a dataset-format specification.
2. Implement read-only loading of the three contact sheets and the corrected ODS annotations.
3. Produce a text-only inventory and split manifest; do not emit images into chat or commit them to Git.
4. Generalize and validate the segment-removal generator by display family.
5. Only then implement the baseline CNN-CTC training script and GitHub Actions workflow.
