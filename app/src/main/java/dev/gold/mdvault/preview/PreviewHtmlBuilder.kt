package dev.gold.mdvault.preview

/**
 * 읽기 화면용 HTML shell (순수 함수 — 유닛 테스트 대상).
 * bodyHtml은 MarkdownEngine.toHtml() 산출물(신뢰된 변환 결과)을 그대로 받는다.
 * JS는 WebView 쪽에서 비활성 — 이 shell에는 <script>를 넣지 않는다.
 */
object PreviewHtmlBuilder {

    fun build(bodyHtml: String): String = SHELL_HEAD + bodyHtml + SHELL_TAIL

    private val SHELL_HEAD = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        :root { color-scheme: light dark; }
        body {
            font-family: sans-serif;
            font-size: 17px;
            line-height: 1.7;
            margin: 0 auto;
            padding: 20px 20px 64px;
            max-width: 44em;
            word-break: keep-all;
            overflow-wrap: break-word;
        }
        h1, h2, h3, h4 { line-height: 1.35; margin: 1.3em 0 0.5em; }
        h1 { font-size: 1.6em; } h2 { font-size: 1.35em; } h3 { font-size: 1.15em; }
        img { max-width: 100%; height: auto; }
        table { border-collapse: collapse; margin: 1em 0; display: block; overflow-x: auto; }
        th, td { border: 1px solid #999; padding: 6px 10px; }
        th { background: rgba(127,127,127,0.15); }
        .csv-scroll { max-width: 100%; margin: 1em 0; overflow-x: auto; }
        .csv-table { display: table; width: max-content; min-width: 100%; margin: 0; }
        .csv-table td { min-width: 7em; max-width: 32em; white-space: pre-wrap; vertical-align: top; }
        .csv-row-number {
            position: sticky;
            left: 0;
            z-index: 1;
            min-width: 2.5em;
            text-align: right;
            background: #eeeeee;
        }
        pre { background: rgba(127,127,127,0.12); padding: 12px; border-radius: 8px; overflow-x: auto; }
        code { font-family: monospace; font-size: 0.9em; }
        blockquote { border-left: 4px solid #999; margin: 1em 0; padding: 0.1px 1em; opacity: 0.85; }
        a { color: #2f6fdb; }
        @media (prefers-color-scheme: dark) {
            body { background: #121212; color: #e4e4e4; }
            a { color: #7aa7f0; }
            th, td { border-color: #555; }
            .csv-row-number { background: #2b2b2b; }
        }
        </style>
        </head>
        <body>
    """.trimIndent() + "\n"

    private val SHELL_TAIL = "\n</body>\n</html>\n"
}
