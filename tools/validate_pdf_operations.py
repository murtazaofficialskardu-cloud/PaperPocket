#!/usr/bin/env python3
"""Run lossless merge/extract checks and independently inspect JPEGs and rendering."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

import fitz
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/paperpocket/app"
FIXTURES = ROOT / "app/src/test/resources"


def validate(folder: Path) -> None:
    classes = folder / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    files = [SOURCE / f"{name}.java" for name in (
        "PdfGeometry", "StreamingImagePdf", "ImagePdfReader", "LosslessPdfOperations", "PdfPageSelection")]
    subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(classes),
                    *map(str, files), str(ROOT / "tools/PdfOperationsValidationHarness.java")], check=True)
    subprocess.run(["java", "-Xmx24m", "-cp", str(classes),
                    "com.paperpocket.app.PdfOperationsValidationHarness", str(folder),
                    str(FIXTURES / "pdf-test-portrait.jpg"),
                    str(FIXTURES / "pdf-test-landscape.jpg")], check=True)
    specifications = {
        "merged.pdf": [
            ("source-second.pdf", 0, "portrait"), ("source-first.pdf", 0, "portrait"),
            ("source-first.pdf", 1, "landscape"), ("source-third.pdf", 0, "portrait")],
        "extracted.pdf": [("source-first.pdf", 1, "landscape"), ("source-second.pdf", 0, "portrait")],
    }
    pages = 0
    for name, expected in specifications.items():
        with fitz.open(folder / name) as output:
            assert not output.is_repaired and output.page_count == len(expected), name
            for index, (source_name, source_index, fixture) in enumerate(expected):
                page = output[index]
                images = page.get_images(full=True)
                assert len(images) == 1, (name, index, "one JPEG per page")
                embedded = output.extract_image(images[0][0])
                expected_bytes = (FIXTURES / f"pdf-test-{fixture}.jpg").read_bytes()
                assert embedded["ext"] == "jpeg"
                assert hashlib.sha256(embedded["image"]).digest() == hashlib.sha256(expected_bytes).digest(), (name, index, "exact JPEG payload")
                with fitz.open(folder / source_name) as original:
                    source_page = original[source_index]
                    assert page.rect == source_page.rect, (name, index, "original physical size")
                    actual = page.get_pixmap(matrix=fitz.Matrix(1.5, 1.5), alpha=False)
                    source = source_page.get_pixmap(matrix=fitz.Matrix(1.5, 1.5), alpha=False)
                    actual_image = Image.frombytes("RGB", (actual.width, actual.height), actual.samples)
                    source_image = Image.frombytes("RGB", (source.width, source.height), source.samples)
                    assert all(high <= 2 for low, high in ImageChops.difference(actual_image, source_image).getextrema()), (name, index, "appearance unchanged")
                pages += 1
    with fitz.open(folder / "source-500.pdf") as boundary:
        assert not boundary.is_repaired and boundary.page_count == 500
    print(f"Independent MuPDF validation passed: {pages} reordered pages; exact JPEG hashes, physical dimensions, and unchanged rendering; 500-page boundary.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-directory", type=Path)
    arguments = parser.parse_args()
    if arguments.output_directory:
        validate(arguments.output_directory)
    else:
        with tempfile.TemporaryDirectory(prefix="paperpocket-pdf-operations-") as path:
            validate(Path(path))


if __name__ == "__main__":
    main()
