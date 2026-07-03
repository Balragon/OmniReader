# Expected conversion behavior

- Handle raw U+0008 and U+000B bytes in `word/document.xml` without crashing the whole import pipeline.
- Remove, replace, or report the invalid control characters according to converter policy.
- Preserve surrounding Korean text when recovery is possible.
