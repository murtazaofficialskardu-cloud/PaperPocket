#!/usr/bin/env python3
"""Compile the portable writer checks and inspect PDFs with independent MuPDF rendering.

Requires Java 17+ (with the jdk.compiler module), PyMuPDF, and Pillow.
Usage: python tools/validate_pdf.py [--output-directory /path/to/preserved/results]
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

try:
    import fitz
    from PIL import Image
except ImportError as error:
    raise SystemExit("Install validation dependencies: python -m pip install PyMuPDF Pillow") from error


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/paperpocket/app"
FIXTURES = ROOT / "app/src/test/resources"
PORTRAIT = FIXTURES / "pdf-test-portrait.jpg"
LANDSCAPE = FIXTURES / "pdf-test-landscape.jpg"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def close(actual: float, expected: float, message: str, tolerance: float = .003) -> None:
    require(math.isclose(actual, expected, abs_tol=tolerance), f"{message}: {actual} != {expected}")


def verify_structure(path: Path, expected_pages: int) -> None:
    """Check offsets and exact binary stream lengths without relying on the renderer."""
    data = path.read_bytes()
    require(data.startswith(b"%PDF-1.4\n"), f"{path.name}: PDF header")
    ending = re.search(rb"startxref\s+(\d+)\s+%%EOF\s*$", data)
    require(ending is not None, f"{path.name}: complete trailer")
    xref = int(ending.group(1))
    require(data[xref:xref + 5] == b"xref\n", f"{path.name}: startxref byte offset")
    table, trailer = data[xref:].split(b"trailer\n", 1)
    lines = table.splitlines()
    first, count = map(int, lines[1].split())
    require(first == 0 and count == expected_pages * 3 + 3, f"{path.name}: object count")
    require(len(lines[2:]) == count, f"{path.name}: cross-reference entry count")
    require(lines[2] == b"0000000000 65535 f ", f"{path.name}: free object zero")
    require(re.search(rb"/Size\s+" + str(count).encode() + rb"\b", trailer) is not None,
            f"{path.name}: trailer size")
    offsets = {}
    for identifier, entry in enumerate(lines[3:], start=1):
        require(re.fullmatch(rb"\d{10} 00000 n ", entry) is not None,
                f"{path.name}: xref record {identifier}")
        offset = int(entry[:10])
        require(data[offset:].startswith(f"{identifier} 0 obj\n".encode()),
                f"{path.name}: exact object {identifier} byte offset")
        require(offset < xref, f"{path.name}: object precedes cross-reference table")
        offsets[identifier] = offset
    ordered = sorted(offsets.values()) + [xref]
    limits = dict(zip(ordered, ordered[1:]))
    stream_count = 0
    image_count = 0
    for identifier, offset in offsets.items():
        block = data[offset:limits[offset]]
        require(block.endswith(b"endobj\n"), f"{path.name}: complete object {identifier}")
        if b"\nstream\n" not in block:
            continue
        dictionary, contents = block.split(b"\nstream\n", 1)
        lengths = re.findall(rb"/Length\s+(\d+)\b", dictionary)
        require(len(lengths) == 1, f"{path.name}: one declared stream length")
        length = int(lengths[0])
        require(contents[length:] in (b"endstream\nendobj\n", b"\nendstream\nendobj\n"),
                f"{path.name}: stream {identifier} byte length is exact")
        stream_count += 1
        if b"/Subtype /Image" in dictionary:
            require(contents[:length] in (PORTRAIT.read_bytes(), LANDSCAPE.read_bytes()),
                    f"{path.name}: image {identifier} retains the fixture JPEG bytes")
            image_count += 1
    require(stream_count == expected_pages * 2 and image_count == expected_pages,
            f"{path.name}: content and image stream counts")


def sample(image: Image.Image, x: float, y: float) -> tuple[int, int, int]:
    return image.getpixel((min(image.width - 1, max(0, round(x))),
                           min(image.height - 1, max(0, round(y)))))[:3]


def verify_rendering(path: Path, expectations: list[dict], preserve: bool) -> None:
    require(len(expectations) > 0, "Expected page specifications")
    with fitz.open(path) as document:
        require(not document.is_repaired, f"{path.name}: PDF opens without repair")
        require(document.page_count == len(expectations), f"{path.name}: rendered page count")
        for index, expected in enumerate(expectations):
            page = document[index]
            width, height = expected["page"]
            close(page.rect.width, width, f"Page {index + 1} width")
            close(page.rect.height, height, f"Page {index + 1} height")
            images = page.get_images(full=True)
            require(len(images) == 1, f"Page {index + 1}: one image")
            xref, _, pixel_width, pixel_height, depth, color_space, _, _, codec, *_ = images[0]
            require((pixel_width, pixel_height) == expected["pixels"],
                    f"Page {index + 1}: embedded image resolution")
            require(depth == 8 and color_space == "DeviceRGB" and codec == "DCTDecode",
                    f"Page {index + 1}: RGB JPEG encoding")
            rectangles = page.get_image_rects(xref)
            require(len(rectangles) == 1, f"Page {index + 1}: one image placement")
            rectangle = rectangles[0]
            for actual, wanted in zip(rectangle, expected["rect"]):
                close(actual, wanted, f"Page {index + 1}: image placement")
            # Render at one pixel per PDF point. Sampling both halves catches vertical flips.
            pixmap = page.get_pixmap(matrix=fitz.Matrix(1, 1), alpha=False)
            rendered = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
            center = (rectangle.x0 + rectangle.x1) / 2
            red = sample(rendered, center, rectangle.y0 + rectangle.height / 4)
            blue = sample(rendered, center, rectangle.y0 + 3 * rectangle.height / 4)
            require(red[0] > 190 and red[1] < 90 and red[2] < 90,
                    f"Page {index + 1}: original red top half ({red})")
            require(blue[2] > 190 and blue[0] < 90 and blue[1] < 120,
                    f"Page {index + 1}: original blue bottom half ({blue})")
            if rectangle.y0 >= 4:
                require(min(sample(rendered, center, rectangle.y0 / 2)) >= 250,
                        f"Page {index + 1}: white upper margin")
            if height - rectangle.y1 >= 4:
                require(min(sample(rendered, center, (rectangle.y1 + height) / 2)) >= 250,
                        f"Page {index + 1}: white lower margin")
            if rectangle.x0 >= 4:
                require(min(sample(rendered, rectangle.x0 / 2, height / 2)) >= 250,
                        f"Page {index + 1}: white left margin")
            if preserve and index < 3:
                rendered.save(path.with_name(f"{path.stem}-page-{index + 1}.png"))


def run(output: Path, preserve: bool) -> None:
    java = shutil.which("java")
    require(java is not None, "Java 17+ must be available on PATH")
    for fixture, size in ((PORTRAIT, (16, 24)), (LANDSCAPE, (24, 16))):
        with Image.open(fixture) as image:
            require(image.format == "JPEG" and image.mode == "RGB" and image.size == size,
                    f"Valid real JPEG fixture: {fixture.name}")
            image.verify()
    with tempfile.TemporaryDirectory(prefix="paperpocket-java-") as compiled:
        sources = [SOURCE / "PdfGeometry.java", SOURCE / "StreamingImagePdf.java",
                   ROOT / "tools/PdfValidationHarness.java"]
        subprocess.run([java, "--add-modules", "jdk.compiler", "com.sun.tools.javac.Main",
                        "-source", "17", "-target", "17", "-Xlint:-options", "-d", compiled,
                        *(str(source) for source in sources)], check=True)
        subprocess.run([java, "-Xmx24m", "-cp", compiled,
                        "com.paperpocket.validation.PdfValidationHarness", str(output),
                        str(PORTRAIT), str(LANDSCAPE)], check=True)
    a4 = {"page": (595, 842), "pixels": (16, 24),
          "rect": (32.833333, 24, 562.166667, 818)}
    letter = {"page": (792, 612), "pixels": (24, 16), "rect": (0, 42, 792, 570)}
    automatic = {"page": (561, 842), "pixels": (16, 24), "rect": (0, .25, 561, 841.75)}
    cases = [(output / "validation-three-pages.pdf", [a4, letter, automatic]),
             (output / "validation-100-pages.pdf", [a4] * 100)]
    for pdf, expectations in cases:
        verify_structure(pdf, len(expectations))
        verify_rendering(pdf, expectations, preserve)
    print("Independent PDF validation passed: 103 pages; geometry, image colors/orientation, "
          "white margins, xref offsets, and stream lengths.")
    if preserve:
        print(f"PDFs and render previews saved to {output}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-directory", type=Path,
                        help="Preserve generated PDFs and rendered PNG previews here.")
    arguments = parser.parse_args()
    if arguments.output_directory:
        output = arguments.output_directory.resolve()
        output.mkdir(parents=True, exist_ok=True)
        run(output, True)
    else:
        with tempfile.TemporaryDirectory(prefix="paperpocket-pdf-") as temporary:
            run(Path(temporary), False)


if __name__ == "__main__":
    main()
