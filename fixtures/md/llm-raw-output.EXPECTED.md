# Expected conversion behavior

- Detect that the entire document is wrapped in one outer Markdown code fence.
- Import normalization should unwrap the outer fence and parse the inner Markdown.
- A raw renderer without normalization may show it as one code block, but conversion tests should document which mode is used.
