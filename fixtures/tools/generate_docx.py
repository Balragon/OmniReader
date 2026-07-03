#!/usr/bin/env python3
"""Generate DOCX fixtures and binary fixture assets for mdvault tests.

This script uses only the Python standard library so the fixture corpus can be
rebuilt in offline CI or sandboxed reviewer sessions. The generated package
layout is intentionally small WordprocessingML that mirrors the cases normally
authored with python-docx, plus explicit XML/package edge cases.
"""

from __future__ import annotations

import random
import string
import zlib
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parents[2]
DOCX_DIR = ROOT / "fixtures" / "docx"
MD_DIR = ROOT / "fixtures" / "md"

CONTROL_BACKSPACE = "__MDVAULT_CONTROL_0008__"
CONTROL_VERTICAL_TAB = "__MDVAULT_CONTROL_000B__"

NS = {
    "w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "wp": "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
    "pic": "http://schemas.openxmlformats.org/drawingml/2006/picture",
}

REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"


DOCX_EXPECTED: dict[str, str] = {
    "simple-korean": """# Expected conversion behavior

- Preserve the three Korean heading levels as `#`, `##`, and `###`.
- Preserve paragraph order and Korean text without mojibake or dropped spacing.
- Do not introduce Android-specific metadata or absolute file paths.
""",
    "formatting": """# Expected conversion behavior

- Convert bold, italic, and bold-italic runs to Markdown emphasis.
- Preserve mixed-run ordering in chains like `word **bold** word`.
- Avoid adding emphasis markers around surrounding plain text.
""",
    "table-basic": """# Expected conversion behavior

- Convert the 4x5 unmerged table to a Markdown table.
- Preserve Korean cell content.
- Preserve or safely trim trailing cell whitespace without merging cells.
""",
    "table-merged": """# Expected conversion behavior

- Detect merged cells and take the warning/fallback path.
- Preserve visible table text in reading order.
- Do not silently emit a normal Markdown table that hides merge information.
""",
    "images": """# Expected conversion behavior

- Detect all five embedded images: three PNG and two JPEG entries.
- Treat the fixture as a large-image import case; `images.docx` must stay over 5 MB.
- Preserve image order and generate stable placeholders, asset records, or warnings according to converter policy.
""",
    "lists": """# Expected conversion behavior

- Convert bullet, numbered, and nested list levels to Markdown list syntax.
- Preserve list item order and nesting depth.
- Avoid flattening nested items into plain paragraphs.
""",
    "links": """# Expected conversion behavior

- Preserve normal HTTP/HTTPS links as Markdown links.
- Detect or sanitize the `javascript:` hyperlink instead of emitting an unsafe active link.
- Detect the external image reference and take the warning/fallback path rather than fetching network content.
""",
    "control-chars": """# Expected conversion behavior

- Handle raw U+0008 and U+000B bytes in `word/document.xml` without crashing the whole import pipeline.
- Remove, replace, or report the invalid control characters according to converter policy.
- Preserve surrounding Korean text when recovery is possible.
""",
    "llm-generated": """# Expected conversion behavior

- Preserve long LLM-style paragraphs as paragraphs.
- Convert the included table to a Markdown table.
- Preserve the code block as fenced code or an equivalent monospaced block.
""",
    "large": """# Expected conversion behavior

- Import a roughly 100-page document without excessive memory use or truncation.
- Preserve page-order text and headings.
- The fixture should remain substantial enough to exercise streaming or chunked conversion paths.
""",
}


class DocxBuilder:
    def __init__(self) -> None:
        self.relationships: list[tuple[str, str, str, str | None]] = [
            ("rId1", f"{OFFICE_REL}/styles", "styles.xml", None),
            ("rId2", f"{OFFICE_REL}/numbering", "numbering.xml", None),
        ]
        self.media: dict[str, bytes] = {}
        self.next_relationship = 3
        self.next_doc_pr = 1

    def add_relationship(self, rel_type: str, target: str, target_mode: str | None = None) -> str:
        relationship_id = f"rId{self.next_relationship}"
        self.next_relationship += 1
        self.relationships.append((relationship_id, rel_type, target, target_mode))
        return relationship_id

    def add_media(self, filename: str, payload: bytes) -> str:
        self.media[f"word/media/{filename}"] = payload
        return self.add_relationship(f"{OFFICE_REL}/image", f"media/{filename}")

    def next_picture_id(self) -> int:
        value = self.next_doc_pr
        self.next_doc_pr += 1
        return value


def xml_text(value: str) -> str:
    return escape(value, {'"': "&quot;"})


def text_element(value: str) -> str:
    preserve = (
        value.startswith((" ", "\t"))
        or value.endswith((" ", "\t"))
        or "  " in value
        or "\t" in value
    )
    space = ' xml:space="preserve"' if preserve else ""
    return f"<w:t{space}>{xml_text(value)}</w:t>"


def run(
    text: str = "",
    *,
    bold: bool = False,
    italic: bool = False,
    font: str | None = None,
    size_half_points: int | None = None,
) -> str:
    properties: list[str] = []
    if bold:
        properties.append("<w:b/>")
    if italic:
        properties.append("<w:i/>")
    if font:
        escaped = xml_text(font)
        properties.append(
            f'<w:rFonts w:ascii="{escaped}" w:hAnsi="{escaped}" w:eastAsia="{escaped}"/>'
        )
    if size_half_points:
        properties.append(f'<w:sz w:val="{size_half_points}"/>')

    parts: list[str] = []
    lines = text.split("\n")
    for index, line in enumerate(lines):
        if index:
            parts.append("<w:br/>")
        if line:
            parts.append(text_element(line))
    if not parts:
        parts.append("<w:t/>")

    rpr = f"<w:rPr>{''.join(properties)}</w:rPr>" if properties else ""
    return f"<w:r>{rpr}{''.join(parts)}</w:r>"


def hyperlink(relationship_id: str, label: str) -> str:
    return (
        f'<w:hyperlink r:id="{relationship_id}">'
        "<w:r><w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr>"
        f"{text_element(label)}</w:r></w:hyperlink>"
    )


def paragraph(
    runs: str | list[str],
    *,
    style: str | None = None,
    num_id: int | None = None,
    ilvl: int = 0,
) -> str:
    run_xml = runs if isinstance(runs, str) else "".join(runs)
    properties: list[str] = []
    if style:
        properties.append(f'<w:pStyle w:val="{style}"/>')
    if num_id is not None:
        properties.append(
            f"<w:numPr><w:ilvl w:val=\"{ilvl}\"/><w:numId w:val=\"{num_id}\"/></w:numPr>"
        )
    ppr = f"<w:pPr>{''.join(properties)}</w:pPr>" if properties else ""
    return f"<w:p>{ppr}{run_xml}</w:p>"


def paragraph_text(text: str, **kwargs) -> str:
    return paragraph(run(text), **kwargs)


def heading(text: str, level: int) -> str:
    return paragraph_text(text, style=f"Heading{level}")


def page_break() -> str:
    return '<w:p><w:r><w:br w:type="page"/></w:r></w:p>'


def cell(text: str, *, grid_span: int | None = None, v_merge: str | None = None) -> str:
    properties = ['<w:tcW w:w="2400" w:type="dxa"/>']
    if grid_span:
        properties.append(f'<w:gridSpan w:val="{grid_span}"/>')
    if v_merge:
        if v_merge == "continue":
            properties.append("<w:vMerge/>")
        else:
            properties.append(f'<w:vMerge w:val="{v_merge}"/>')
    return f"<w:tc><w:tcPr>{''.join(properties)}</w:tcPr>{paragraph_text(text)}</w:tc>"


def table(rows: list[list[str]], *, cols: int) -> str:
    borders = (
        '<w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="auto"/>'
        '<w:left w:val="single" w:sz="4" w:space="0" w:color="auto"/>'
        '<w:bottom w:val="single" w:sz="4" w:space="0" w:color="auto"/>'
        '<w:right w:val="single" w:sz="4" w:space="0" w:color="auto"/>'
        '<w:insideH w:val="single" w:sz="4" w:space="0" w:color="auto"/>'
        '<w:insideV w:val="single" w:sz="4" w:space="0" w:color="auto"/></w:tblBorders>'
    )
    grid = "<w:tblGrid>" + "".join('<w:gridCol w:w="2400"/>' for _ in range(cols)) + "</w:tblGrid>"
    row_xml = "".join(f"<w:tr>{''.join(row)}</w:tr>" for row in rows)
    return f'<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/>{borders}</w:tblPr>{grid}{row_xml}</w:tbl>'


def drawing(builder: DocxBuilder, relationship_id: str, name: str, *, external: bool = False) -> str:
    picture_id = builder.next_picture_id()
    blip_attr = "link" if external else "embed"
    return f"""
<w:p>
  <w:r>
    <w:drawing>
      <wp:inline distT="0" distB="0" distL="0" distR="0">
        <wp:extent cx="2057400" cy="1543050"/>
        <wp:docPr id="{picture_id}" name="{xml_text(name)}"/>
        <a:graphic>
          <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <pic:pic>
              <pic:nvPicPr>
                <pic:cNvPr id="{picture_id}" name="{xml_text(name)}"/>
                <pic:cNvPicPr/>
              </pic:nvPicPr>
              <pic:blipFill>
                <a:blip r:{blip_attr}="{relationship_id}"/>
                <a:stretch><a:fillRect/></a:stretch>
              </pic:blipFill>
              <pic:spPr>
                <a:xfrm><a:off x="0" y="0"/><a:ext cx="2057400" cy="1543050"/></a:xfrm>
                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
              </pic:spPr>
            </pic:pic>
          </a:graphicData>
        </a:graphic>
      </wp:inline>
    </w:drawing>
  </w:r>
</w:p>
"""


def package_relationships() -> bytes:
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="{REL_NS}">
  <Relationship Id="rId1" Type="{OFFICE_REL}/officeDocument" Target="word/document.xml"/>
</Relationships>
""".encode("utf-8")


def document_relationships(builder: DocxBuilder) -> bytes:
    entries = []
    for relationship_id, rel_type, target, target_mode in builder.relationships:
        mode = f' TargetMode="{target_mode}"' if target_mode else ""
        entries.append(
            f'  <Relationship Id="{relationship_id}" Type="{rel_type}" Target="{xml_text(target)}"{mode}/>'
        )
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        f'<Relationships xmlns="{REL_NS}">\n'
        + "\n".join(entries)
        + "\n</Relationships>\n"
    ).encode("utf-8")


def content_types() -> bytes:
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Default Extension="jpg" ContentType="image/jpeg"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
</Types>
""".encode("utf-8")


def styles_xml() -> bytes:
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="{NS['w']}">
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/>
    <w:qFormat/>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:outlineLvl w:val="0"/></w:pPr>
    <w:rPr><w:b/><w:sz w:val="32"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading2">
    <w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:outlineLvl w:val="1"/></w:pPr>
    <w:rPr><w:b/><w:sz w:val="28"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading3">
    <w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
    <w:pPr><w:outlineLvl w:val="2"/></w:pPr>
    <w:rPr><w:b/><w:sz w:val="24"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="ListParagraph">
    <w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:ind w:left="720"/></w:pPr>
  </w:style>
</w:styles>
""".encode("utf-8")


def numbering_xml() -> bytes:
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="{NS['w']}">
  <w:abstractNum w:abstractNumId="0">
    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="&#8226;"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
    <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="o"/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
  </w:abstractNum>
  <w:abstractNum w:abstractNumId="1">
    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
    <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%2."/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
  </w:abstractNum>
  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
  <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
</w:numbering>
""".encode("utf-8")


def document_xml(body: str) -> bytes:
    namespace_attrs = " ".join(f'xmlns:{prefix}="{uri}"' for prefix, uri in NS.items())
    xml = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document {namespace_attrs}>
  <w:body>
    {body}
    <w:sectPr>
      <w:pgSz w:w="12240" w:h="15840"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>
    </w:sectPr>
  </w:body>
</w:document>
"""
    payload = xml.encode("utf-8")
    return (
        payload.replace(CONTROL_BACKSPACE.encode("utf-8"), b"\x08")
        .replace(CONTROL_VERTICAL_TAB.encode("utf-8"), b"\x0B")
    )


def save_docx(filename: str, body: str, builder: DocxBuilder | None = None) -> None:
    builder = builder or DocxBuilder()
    path = DOCX_DIR / filename
    with ZipFile(path, "w", ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types())
        archive.writestr("_rels/.rels", package_relationships())
        archive.writestr("word/document.xml", document_xml(body))
        archive.writestr("word/_rels/document.xml.rels", document_relationships(builder))
        archive.writestr("word/styles.xml", styles_xml())
        archive.writestr("word/numbering.xml", numbering_xml())
        for name, payload in builder.media.items():
            archive.writestr(name, payload)


def marker_segment(marker: int, payload: bytes) -> bytes:
    length = len(payload) + 2
    return bytes([0xFF, marker, length >> 8, length & 0xFF]) + payload


def deterministic_bytes(seed: int, size: int) -> bytes:
    rng = random.Random(seed)
    return bytes(rng.getrandbits(8) for _ in range(size))


def png_bytes(width: int, height: int, seed: int) -> bytes:
    rng = random.Random(seed)
    rows = bytearray()
    for _y in range(height):
        rows.append(0)
        for _x in range(width):
            rows.extend((rng.randrange(256), rng.randrange(256), rng.randrange(256)))

    def chunk(kind: bytes, payload: bytes) -> bytes:
        crc = zlib.crc32(kind + payload) & 0xFFFFFFFF
        return len(payload).to_bytes(4, "big") + kind + payload + crc.to_bytes(4, "big")

    ihdr = (
        width.to_bytes(4, "big")
        + height.to_bytes(4, "big")
        + bytes([8, 2, 0, 0, 0])
    )
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(rows), level=0))
        + chunk(b"IEND", b"")
    )


def jpeg_bytes(width: int, height: int, seed: int, padding_size: int) -> bytes:
    app0 = b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    sof0 = bytes(
        [
            8,
            height >> 8,
            height & 0xFF,
            width >> 8,
            width & 0xFF,
            3,
            1,
            0x11,
            0,
            2,
            0x11,
            0,
            3,
            0x11,
            0,
        ]
    )
    sos = bytes([3, 1, 0, 2, 0, 3, 0, 0, 63, 0])
    return (
        b"\xFF\xD8"
        + marker_segment(0xE0, app0)
        + marker_segment(0xC0, sof0)
        + marker_segment(0xDA, sos)
        + deterministic_bytes(seed, padding_size)
        + b"\xFF\xD9"
    )


def write_expected_files() -> None:
    for slug, content in DOCX_EXPECTED.items():
        (DOCX_DIR / f"{slug}.EXPECTED.md").write_text(content, encoding="utf-8")


def write_markdown_image_asset() -> None:
    image_dir = MD_DIR / "images"
    image_dir.mkdir(parents=True, exist_ok=True)
    (image_dir / "relative-sample.png").write_bytes(png_bytes(96, 64, 302))


def make_simple_korean() -> None:
    body = "".join(
        [
            heading("1단계 제목: 회의 기록", 1),
            paragraph_text("첫 번째 문단은 한국어 본문과 자연스러운 줄 흐름을 확인한다."),
            heading("2단계 제목: 결정 사항", 2),
            paragraph_text("결정 사항은 짧은 문장과 긴 문장을 함께 포함한다. 변환기는 순서를 유지해야 한다."),
            heading("3단계 제목: 후속 작업", 3),
            paragraph_text("후속 작업 담당자, 기한, 메모를 일반 문단으로 기록한다."),
        ]
    )
    save_docx("simple-korean.docx", body)


def make_formatting() -> None:
    body = "".join(
        [
            heading("Formatting runs", 1),
            paragraph([run("word "), run("bold", bold=True), run(" word")]),
            paragraph(
                [
                    run("plain "),
                    run("italic", italic=True),
                    run(" plain "),
                    run("bold italic", bold=True, italic=True),
                    run(" end"),
                ]
            ),
            paragraph(
                [
                    item
                    for index in range(4)
                    for item in (run(f"word{index} "), run(f"bold{index}", bold=True), run(" "))
                ]
            ),
        ]
    )
    save_docx("formatting.docx", body)


def make_table_basic() -> None:
    rows: list[list[str]] = []
    for row_index in range(1, 5):
        row = []
        for col_index in range(1, 6):
            suffix = "   " if (row_index, col_index) in {(2, 5), (4, 3)} else ""
            row.append(cell(f"{row_index}행 {col_index}열 값{suffix}"))
        rows.append(row)
    body = heading("기본 표", 1) + table(rows, cols=5)
    save_docx("table-basic.docx", body)


def make_table_merged() -> None:
    rows = [
        [
            cell("가로 병합 헤더", grid_span=2),
            cell("셀 1-3"),
            cell("셀 1-4"),
        ],
        [
            cell("세로 병합 항목", v_merge="restart"),
            cell("셀 2-2"),
            cell("셀 2-3"),
            cell("셀 2-4"),
        ],
        [
            cell("", v_merge="continue"),
            cell("셀 3-2"),
            cell("2x2 병합 영역", grid_span=2, v_merge="restart"),
        ],
        [
            cell("셀 4-1"),
            cell("셀 4-2"),
            cell("", grid_span=2, v_merge="continue"),
        ],
    ]
    body = heading("병합 셀 표", 1) + table(rows, cols=4)
    save_docx("table-merged.docx", body)


def make_images() -> None:
    builder = DocxBuilder()
    body_parts = [heading("Embedded image fixture", 1), paragraph_text("Three PNG images and two JPEG images follow.")]
    image_specs = [
        ("fixture-1.png", png_bytes(1024, 1024, 100)),
        ("fixture-2.png", png_bytes(1024, 1024, 101)),
        ("fixture-3.png", png_bytes(1024, 1024, 102)),
        ("fixture-1.jpg", jpeg_bytes(640, 480, 200, 900_000)),
        ("fixture-2.jpg", jpeg_bytes(640, 480, 201, 900_000)),
    ]
    for filename, payload in image_specs:
        relationship_id = builder.add_media(filename, payload)
        body_parts.append(paragraph_text(filename))
        body_parts.append(drawing(builder, relationship_id, filename))
    save_docx("images.docx", "".join(body_parts), builder)


def make_lists() -> None:
    body_parts = [heading("목록", 1)]
    for text in ["첫 번째 글머리", "두 번째 글머리", "세 번째 글머리"]:
        body_parts.append(paragraph_text(text, style="ListParagraph", num_id=1, ilvl=0))
    body_parts.append(paragraph_text("중첩 글머리 A", style="ListParagraph", num_id=1, ilvl=1))
    body_parts.append(paragraph_text("중첩 글머리 B", style="ListParagraph", num_id=1, ilvl=1))
    for text in ["첫 번째 번호", "두 번째 번호", "세 번째 번호"]:
        body_parts.append(paragraph_text(text, style="ListParagraph", num_id=2, ilvl=0))
    body_parts.append(paragraph_text("중첩 번호 1", style="ListParagraph", num_id=2, ilvl=1))
    body_parts.append(paragraph_text("중첩 번호 2", style="ListParagraph", num_id=2, ilvl=1))
    save_docx("lists.docx", "".join(body_parts))


def make_links() -> None:
    builder = DocxBuilder()
    openai = builder.add_relationship(f"{OFFICE_REL}/hyperlink", "https://openai.com/", "External")
    example = builder.add_relationship(
        f"{OFFICE_REL}/hyperlink", "http://example.com/path?q=mdvault", "External"
    )
    javascript = builder.add_relationship(
        f"{OFFICE_REL}/hyperlink", "javascript:alert('mdvault')", "External"
    )
    external_image = builder.add_relationship(
        f"{OFFICE_REL}/image", "https://example.com/mdvault/external-image.png", "External"
    )
    body = "".join(
        [
            heading("Links", 1),
            paragraph([run("Normal link: "), hyperlink(openai, "OpenAI"), run(" and "), hyperlink(example, "example")]),
            paragraph([run("Unsafe link: "), hyperlink(javascript, "javascript payload")]),
            paragraph_text("External image reference follows."),
            drawing(builder, external_image, "external-image.png", external=True),
        ]
    )
    save_docx("links.docx", body, builder)


def make_control_chars() -> None:
    body = "".join(
        [
            heading("Control characters", 1),
            paragraph_text(
                f"앞부분 텍스트 {CONTROL_BACKSPACE} 중간 텍스트 {CONTROL_VERTICAL_TAB} 끝부분 텍스트"
            ),
        ]
    )
    save_docx("control-chars.docx", body)


def make_llm_generated() -> None:
    long_sentence = (
        "이 문서는 LLM이 생성한 초안처럼 긴 설명, 반복되는 근거, 단계별 제안, "
        "그리고 다소 장황한 연결 문장을 포함한다. "
    )
    body_parts = [heading("LLM Generated Draft", 1)]
    for index in range(4):
        body_parts.append(paragraph_text(f"{index + 1}. " + long_sentence * 5))

    rows = [
        [cell("항목"), cell("설명"), cell("상태")],
        [cell("요약"), cell("긴 문단을 압축한다"), cell("준비")],
        [cell("검증"), cell("표와 코드 블록을 확인한다"), cell("진행")],
        [cell("내보내기"), cell("Markdown으로 변환한다"), cell("대기")],
    ]
    body_parts.append(table(rows, cols=3))
    body_parts.append(
        paragraph(
            run(
                "fun summarize(input: String): String {\n"
                "    return input.lines().take(3).joinToString(\"\\n\")\n"
                "}\n",
                font="Courier New",
                size_half_points=20,
            )
        )
    )
    save_docx("llm-generated.docx", "".join(body_parts))


def korean_noise(seed: int, length: int) -> str:
    rng = random.Random(seed)
    chunks = []
    for _ in range(length):
        base = rng.choice(["기록", "문서", "변환", "검증", "보관", "검색", "편집", "동기화"])
        suffix = "".join(rng.choice(string.ascii_lowercase) for _ in range(4))
        chunks.append(f"{base}-{suffix}")
    return " ".join(chunks)


def make_large() -> None:
    body_parts = [heading("Large DOCX Fixture", 1)]
    for page in range(1, 101):
        body_parts.append(heading(f"페이지 {page}", 2))
        for paragraph_index in range(5):
            text = korean_noise(page * 100 + paragraph_index, 90)
            body_parts.append(
                paragraph_text(
                    f"{page:03d}-{paragraph_index + 1}: "
                    "대용량 변환 경로를 확인하기 위한 본문이다. "
                    + text
                )
            )
        if page != 100:
            body_parts.append(page_break())
    save_docx("large.docx", "".join(body_parts))


def main() -> None:
    DOCX_DIR.mkdir(parents=True, exist_ok=True)
    write_markdown_image_asset()
    for generator in [
        make_simple_korean,
        make_formatting,
        make_table_basic,
        make_table_merged,
        make_images,
        make_lists,
        make_links,
        make_control_chars,
        make_llm_generated,
        make_large,
    ]:
        generator()
    write_expected_files()


if __name__ == "__main__":
    main()
