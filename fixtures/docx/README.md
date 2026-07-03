# DOCX Fixture Notes

Generate the DOCX fixtures from the repository root with:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
python3 -m venv fixtures/tools/.venv
fixtures/tools/.venv/bin/python fixtures/tools/generate_docx.py
```

The committed generator is dependency-free and uses the Python standard library
to write a small WordprocessingML package directly. The `python-docx` install
step from the original authoring plan is optional; the script does not require
network access after checkout.

Cases that need explicit XML or package authoring:

- `links.docx`: hyperlink relationships and the external image reference are
  written directly. The image relationship uses `TargetMode="External"`.
- `control-chars.docx`: XML 1.0 does not allow raw U+0008 or U+000B text nodes.
  The generator writes placeholders first, then replaces them with raw
  control-character bytes inside `word/document.xml`.
- `images.docx`: the PNG images are generated with the Python standard library.
  The JPEG entries are minimal fixture JPEG streams with deterministic random
  payload padding so the resulting `.docx` is over 5 MB without adding image
  generation dependencies.

No manual Word step is required for the current fixture set. If a future fixture
must be authored in Microsoft Word, put the manual steps here and keep the
fixture name stable.
