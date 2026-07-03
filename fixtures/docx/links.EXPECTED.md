# Expected conversion behavior

- Preserve normal HTTP/HTTPS links as Markdown links.
- Detect or sanitize the `javascript:` hyperlink instead of emitting an unsafe active link.
- Detect the external image reference and take the warning/fallback path rather than fetching network content.
