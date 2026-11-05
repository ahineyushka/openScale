#!/usr/bin/env python3
"""Prototype seven-segment suppression on real indicator crops from the contact sheets.

This is a calibration experiment, not yet a general-purpose automatic segment detector.
Requires: pillow, numpy, opencv-python-headless, odfpy.
"""
from __future__ import annotations
import argparse
import csv
import json
import tempfile
import zipfile
from pathlib import Path
from io import BytesIO

import cv2
import numpy as np
from PIL import Image
from odf.opendocument import load
from odf.table import Table, TableRow, TableCell
from odf.text import P

LETTERS = "абвгдежзиклмнопр"
PATTERNS = {
    "0": "abcdef", "1": "bc", "2": "abdeg", "3": "abcdg", "4": "bcfg",
    "5": "acdfg", "6": "acdefg", "7": "abc", "8": "abcdefg", "9": "abcdfg",
}

# Coordinates are crop-local xyxy. Rectangles are manually calibrated and
# separated at segment joints to limit accidental removal of adjacent segments.
CONFIGS = {
    "LED": {
        "source_coordinate": "а4", "source_value": "86.65", "digit_index": 0,
        "bbox": (67, 46, 94, 87),
        "segments": {
            "a": (72, 46, 90, 54), "b": (86, 51, 94, 64),
            "c": (86, 68, 94, 82), "d": (72, 79, 90, 87),
            "e": (67, 68, 75, 82), "f": (67, 51, 75, 64),
            "g": (72, 63, 90, 70),
        },
        "threshold": {"kind": "hsv_cyan", "h_min": 80, "h_max": 130, "s_min": 80, "v_min": 150},
        "dilate": 1, "inpaint_radius": 4,
    },
    "тем. ЖК": {
        "table": 2, "source_coordinate": "д4", "source_value": "53.8", "digit_index": 3,
        "bbox": (159, 38, 180, 82),
        "segments": {
            "a": (163, 38, 175, 45), "b": (173, 42, 180, 58),
            "c": (173, 61, 180, 77), "d": (163, 74, 175, 82),
            "e": (159, 61, 165, 77), "f": (159, 42, 165, 58),
            "g": (163, 56, 175, 64),
        },
        "threshold": {"kind": "gray_dark", "max": 150},
        "dilate": 1, "inpaint_radius": 3,
    },
    "Св. ЖК": {
        "source_coordinate": "е11", "source_value": "58.00", "digit_index": 1,
        "bbox": (108, 49, 121, 86),
        "segments": {
            "a": (110, 49, 118, 55), "b": (117, 52, 121, 66),
            "c": (117, 68, 121, 82), "d": (110, 79, 118, 86),
            "e": (108, 68, 112, 82), "f": (108, 52, 112, 66),
            "g": (110, 64, 118, 70),
        },
        "threshold": {"kind": "gray_bright", "min": 160},
        "dilate": 1, "inpaint_radius": 3,
    },
}


def _node_text(node) -> str:
    out = ""
    for child in getattr(node, "childNodes", []):
        if getattr(child, "nodeType", None) == 3:
            out += str(child.data)
        else:
            out += _node_text(child)
    return out


def _cell_text(cell) -> str:
    return "".join(_node_text(p) for p in cell.getElementsByType(P)).strip()


def read_annotations(ods_bytes: bytes) -> list[dict]:
    # odfpy loads from a path; use a unique temporary file and remove it immediately.
    with tempfile.NamedTemporaryFile(suffix=".ods", delete=False) as handle:
        tmp_path = Path(handle.name)
        handle.write(ods_bytes)
    try:
        doc = load(str(tmp_path))
    finally:
        tmp_path.unlink(missing_ok=True)

    sheet = doc.spreadsheet.getElementsByType(Table)[0]
    records = []
    for row_num, row in enumerate(sheet.getElementsByType(TableRow)[3:], start=4):
        cells = row.getElementsByType(TableCell)
        vals = [_cell_text(c) for c in cells[:9]]
        vals += [""] * (9 - len(vals))
        if not any(vals):
            continue
        try:
            table_no = int(float(vals[0]))
        except (ValueError, TypeError):
            continue
        if table_no not in (1, 2, 3) or vals[1] not in LETTERS:
            continue
        try:
            col_no = int(float(vals[2]))
        except (ValueError, TypeError):
            continue
        if not 1 <= col_no <= 12:
            continue
        records.append({
            "row": row_num,
            "table": table_no,
            "coordinate": f"{vals[1]}{col_no}",
            "weight": vals[3],
            "template": vals[5],
            "family": vals[6],
            "frame": vals[7],
            "notes": vals[8],
        })
    return records


def crop_tile(table_img: Image.Image, coord: str) -> np.ndarray:
    row_letter = coord[0]
    col_no = int(coord[1:])
    x = (col_no - 1) * 250
    y = LETTERS.index(row_letter) * 125
    return np.asarray(table_img.crop((x, y, x + 250, y + 125)).convert("RGB"))


def build_mask(rgb: np.ndarray, cfg: dict, target_digit: str):
    off = set(PATTERNS["8"]) - set(PATTERNS[target_digit])
    mask = np.zeros(rgb.shape[:2], dtype=np.uint8)
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    th = cfg["threshold"]

    if th["kind"] == "hsv_cyan":
        foreground = (
            (hsv[:, :, 0] >= th["h_min"]) &
            (hsv[:, :, 0] <= th["h_max"]) &
            (hsv[:, :, 1] > th["s_min"]) &
            (hsv[:, :, 2] > th["v_min"])
        )
    elif th["kind"] == "gray_dark":
        foreground = gray <= th["max"]
    elif th["kind"] == "gray_bright":
        foreground = gray >= th["min"]
    else:
        raise ValueError(f"unknown threshold {th['kind']}")

    for seg in sorted(off):
        x1, y1, x2, y2 = cfg["segments"][seg]
        mask[y1:y2, x1:x2] = np.where(
            foreground[y1:y2, x1:x2], 255, mask[y1:y2, x1:x2]
        )

    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    if cfg["dilate"]:
        mask = cv2.dilate(mask, kernel, iterations=cfg["dilate"])
    return mask, off


def _family_foreground_mask(rgb: np.ndarray, cfg: dict) -> np.ndarray:
    """Classify lit segment pixels; used only to avoid them when sampling background."""
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    th = cfg["threshold"]
    if th["kind"] == "hsv_cyan":
        return (
            (hsv[:, :, 0] >= th["h_min"]) &
            (hsv[:, :, 0] <= th["h_max"]) &
            (hsv[:, :, 1] > th["s_min"]) &
            (hsv[:, :, 2] > th["v_min"])
        )
    if th["kind"] == "gray_dark":
        return gray <= th["max"]
    if th["kind"] == "gray_bright":
        return gray >= th["min"]
    raise ValueError(f"unknown threshold kind: {th['kind']}")


def _sample_background(rgb: np.ndarray, foreground: np.ndarray,
                       rect: tuple[int, int, int, int],
                       fallback: np.ndarray) -> np.ndarray:
    x1, y1, x2, y2 = rect
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(rgb.shape[1], x2), min(rgb.shape[0], y2)
    if x2 <= x1 or y2 <= y1:
        return fallback.copy()
    patch = rgb[y1:y2, x1:x2]
    background_pixels = patch[~foreground[y1:y2, x1:x2]]
    if len(background_pixels) < 6:
        background_pixels = patch.reshape(-1, 3)
    if len(background_pixels) == 0:
        return fallback.copy()
    return np.clip(np.median(background_pixels, axis=0), 0, 255).astype(np.uint8)


def estimate_segment_backgrounds(rgb: np.ndarray, cfg: dict) -> dict[str, np.ndarray]:
    """Estimate flat local background colours from the two holes inside the 8."""
    bx1, by1, bx2, by2 = cfg["bbox"]
    seg = cfg["segments"]
    center_x = (bx1 + bx2) // 2
    half_width = max(1, int((bx2 - bx1) * 0.10))
    sample_x1, sample_x2 = center_x - half_width, center_x + half_width + 1

    # The upper and lower holes are inside the display, away from its outer frame.
    upper_hole = (sample_x1, seg["a"][3] + 1, sample_x2, seg["g"][1] - 1)
    lower_hole = (sample_x1, seg["g"][3] + 1, sample_x2, seg["d"][1] - 1)
    foreground = _family_foreground_mask(rgb, cfg)

    glyph = rgb[by1:by2, bx1:bx2]
    glyph_foreground = foreground[by1:by2, bx1:bx2]
    non_lit = glyph[~glyph_foreground]
    fallback = (
        np.median(non_lit, axis=0).astype(np.uint8)
        if len(non_lit) else np.median(glyph.reshape(-1, 3)).astype(np.uint8)
    )

    top = _sample_background(rgb, foreground, upper_hole, fallback)
    bottom = _sample_background(rgb, foreground, lower_hole, fallback)
    middle = np.clip(
        np.rint((top.astype(np.float32) + bottom.astype(np.float32)) / 2),
        0, 255
    ).astype(np.uint8)
    return {
        "a": top, "b": top, "f": top,
        "g": middle,
        "d": bottom, "c": bottom, "e": bottom,
    }


def apply_flat_background_fill(
    rgb: np.ndarray, cfg: dict, off_segments: set[str]
) -> tuple[np.ndarray, np.ndarray, dict]:
    """Erase complete calibrated segment footprints using solid background colour.

    Each off segment rectangle is filled with a constant RGB value sampled from
    the corresponding empty hole inside the photographed 8. Adjacent off-segment
    intersections are filled with the mean of their local background colours.
    There is deliberately no inpainting, blur, interpolation or pixel smearing.
    """
    colors = estimate_segment_backgrounds(rgb, cfg)
    result = rgb.copy()
    fill_mask = np.zeros(rgb.shape[:2], dtype=np.uint8)

    for name in sorted(off_segments):
        x1, y1, x2, y2 = cfg["segments"][name]
        result[y1:y2, x1:x2] = colors[name]
        fill_mask[y1:y2, x1:x2] = 255

    junctions = (
        ("a", "f"), ("a", "b"), ("g", "f"), ("g", "b"),
        ("g", "e"), ("g", "c"), ("d", "e"), ("d", "c"),
    )
    for first, second in junctions:
        if first not in off_segments or second not in off_segments:
            continue
        r1, r2 = cfg["segments"][first], cfg["segments"][second]
        x1, y1 = max(r1[0], r2[0]), max(r1[1], r2[1])
        x2, y2 = min(r1[2], r2[2]), min(r1[3], r2[3])
        if x2 <= x1 or y2 <= y1:
            continue
        joint_color = np.clip(
            np.rint((colors[first].astype(np.float32) +
                     colors[second].astype(np.float32)) / 2),
            0, 255
        ).astype(np.uint8)
        result[y1:y2, x1:x2] = joint_color
        fill_mask[y1:y2, x1:x2] = 255

    color_metadata = {
        name: [int(channel) for channel in color]
        for name, color in colors.items()
    }
    return result, fill_mask, color_metadata


def foreground_pixels(rgb: np.ndarray, cfg: dict, segment_names: set[str]) -> int:
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    th = cfg["threshold"]
    if th["kind"] == "hsv_cyan":
        foreground = (
            (hsv[:, :, 0] >= th["h_min"]) &
            (hsv[:, :, 0] <= th["h_max"]) &
            (hsv[:, :, 1] > th["s_min"]) &
            (hsv[:, :, 2] > th["v_min"])
        )
    elif th["kind"] == "gray_dark":
        foreground = gray <= th["max"]
    else:
        foreground = gray >= th["min"]

    count = 0
    for seg in segment_names:
        x1, y1, x2, y2 = cfg["segments"][seg]
        count += int(foreground[y1:y2, x1:x2].sum())
    return count


def _threshold_label(cfg: dict) -> str:
    return json.dumps(cfg["threshold"], ensure_ascii=False, sort_keys=True)


def write_report(summary: dict, outdir: Path) -> None:
    lines = [
        "# Three-family segment-removal test", "",
        "This is a manually calibrated prototype, not an automatic general-purpose digit detector.",
        "The full contact sheets and ODS source remain unchanged; each sample was cropped in memory.", "",
        "## Tested source examples", "",
        "| Family | Source | Value | Variants | Off-segment foreground reduction (mean) | Kept-segment change (mean abs.) |",
        "|---|---|---:|---:|---:|---:|",
    ]
    for family, data in summary.items():
        variants = data["variants"]
        off = np.mean([row["off_segment_reduction_pct"] for row in variants])
        kept = np.mean([abs(row["kept_segment_change_pct"]) for row in variants])
        lines.append(
            f"| {family} | {data['source']} | {data['source_value']} | "
            f"{len(variants)} | {off:.1f}% | {kept:.1f}% |"
        )
    lines += [
        "", "## Interpretation", "",
        "- LED uses a cyan HSV foreground mask; its dilation is calibrated for the visible glow.",
        "- Dark LCD uses dark grayscale pixels inside manually calibrated segment zones.",
        "- Light LCD uses bright grayscale pixels inside manually calibrated segment zones.",
        "- Each family has its own manually measured digit and segment coordinates, threshold, dilation and inpainting radius.",
        "- The reduction metric is a simple threshold-pixel count inside the segments intended to be off. It is a screening metric, not a human quality score.",
        "- The kept-segment metric checks for unwanted change to segments that should remain lit; it does not replace visual inspection.",
        "- A single successful glyph per family is insufficient to validate generalization across all images in that family.", ""
    ]
    (outdir / "REPORT.md").write_text("\n".join(lines), encoding="utf-8")


def run(zip_path: Path, outdir: Path, ods_path: Path | None = None) -> None:
    outdir.mkdir(parents=True, exist_ok=True)
    samples_dir = outdir / "samples"
    sources_dir = outdir / "sources"
    samples_dir.mkdir(exist_ok=True)
    sources_dir.mkdir(exist_ok=True)

    with zipfile.ZipFile(zip_path) as source_zip:
        sheet_images = {
            table_no: Image.open(
                BytesIO(source_zip.read(f"192ind-tt{table_no}.jpg"))
            ).convert("RGB")
            for table_no in (1, 2, 3)
        }
        # The corrected archive already contains the paired ODS annotation file.
        ods_bytes = (
            ods_path.read_bytes()
            if ods_path
            else source_zip.read("TestIndicatorsParameters.ods")
        )

    annotations = read_annotations(ods_bytes)
    by_key = {(row["table"], row["coordinate"]): row for row in annotations}
    manifest = []
    family_summary = {}

    for family, cfg in CONFIGS.items():
        table_no = cfg.get("table", 1)
        record = by_key[(table_no, cfg["source_coordinate"])]
        if record["family"] != family or record["weight"] != cfg["source_value"]:
            raise ValueError(f"annotation mismatch for {family}: {record}")
        if record["notes"].strip():
            raise ValueError(f"selected sample has issue note: {family}: {record['notes']}")

        rgb = crop_tile(sheet_images[table_no], cfg["source_coordinate"])
        original = cfg["source_value"]
        digit_index = cfg["digit_index"]
        if original[digit_index] != "8":
            raise ValueError(f"selected character is not 8: {original}")

        source_name = (
            f"{family.replace(' ', '_')}_t{table_no}_"
            f"{cfg['source_coordinate']}_{original.replace('.', '-')}.png"
        )
        Image.fromarray(rgb).save(sources_dir / source_name)
        original_foreground = foreground_pixels(rgb, cfg, set(PATTERNS["8"]))
        family_summary[family] = {
            "source": source_name,
            "source_value": original,
            "variants": [],
            "foreground_pixels_in_all_segment_zones_before": original_foreground,
        }

        for target in "012345679":
            synthetic_value = (
                original[:digit_index] + target + original[digit_index + 1:]
            )
            off_segments = set(PATTERNS["8"]) - set(PATTERNS[target])
            result, fill_mask, fill_colors = apply_flat_background_fill(
                rgb, cfg, off_segments
            )
            masked_pixels = int(np.count_nonzero(fill_mask))
            if masked_pixels < 5:
                raise ValueError(f"empty flat-fill mask for {family} -> {target}")
            output_name = (
                f"{family.replace(' ', '_')}_t{table_no}_"
                f"{cfg['source_coordinate']}_{original.replace('.', '-')}_"
                f"to_{synthetic_value.replace('.', '-')}.png"
            )
            Image.fromarray(result).save(samples_dir / output_name)

            before_off = foreground_pixels(rgb, cfg, off_segments)
            after_off = foreground_pixels(result, cfg, off_segments)
            kept_segments = set(PATTERNS[target])
            before_kept = foreground_pixels(rgb, cfg, kept_segments)
            after_kept = foreground_pixels(result, cfg, kept_segments)

            row = {
                "file": f"samples/{output_name}",
                "family": family,
                "table": table_no,
                "coordinate": cfg["source_coordinate"],
                "source_value": original,
                "synthetic_value": synthetic_value,
                "digit_index": digit_index,
                "from": "8",
                "to": target,
                "mask_pixels": masked_pixels,
                "off_segment_foreground_before": before_off,
                "off_segment_foreground_after": after_off,
                "off_segment_reduction_pct": round(
                    100 * (before_off - after_off) / max(1, before_off), 2
                ),
                "kept_segment_foreground_before": before_kept,
                "kept_segment_foreground_after": after_kept,
                "kept_segment_change_pct": round(
                    100 * (after_kept - before_kept) / max(1, before_kept), 2
                ),
                "threshold": _threshold_label(cfg),
                "fill_method": "flat_local_background",
                "fill_colors_rgb": json.dumps(fill_colors, ensure_ascii=False),
            }
            manifest.append(row)
            family_summary[family]["variants"].append(row)

    with (outdir / "manifest.csv").open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(manifest[0].keys()))
        writer.writeheader()
        writer.writerows(manifest)
    (outdir / "metrics.json").write_text(
        json.dumps(family_summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    write_report(family_summary, outdir)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", type=Path, required=True)
    parser.add_argument(
        "--ods", type=Path, default=None,
        help="optional external ODS override; default is the ODS embedded in the ZIP",
    )
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    run(args.zip, args.out, args.ods)
