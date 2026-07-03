export const meta = {
  name: 'mdvault-codex-spikes',
  description: 'mdvault Phase 1 Codex spikes: S0 fixtures, then S2 flexmark + S4 SAF in parallel',
  phases: [
    { title: 'S0 Fixtures', detail: 'generate DOCX/MD test fixtures' },
    { title: 'S2+S4 Spikes', detail: 'flexmark engine and SAF repository in parallel' },
  ],
}

const RESULT_SCHEMA = {
  type: 'object',
  required: ['summary', 'files', 'verification', 'issues'],
  properties: {
    summary: { type: 'string', description: 'What was accomplished, 3-6 sentences' },
    files: { type: 'array', items: { type: 'string' }, description: 'Repo-relative paths created or modified' },
    verification: { type: 'string', description: 'Exact commands run to verify and their outcomes' },
    issues: { type: 'array', items: { type: 'string' }, description: 'Anything incomplete, deferred, or needing human attention' },
  },
}

const COMMON = `
## Repository context
You are working in the "mdvault" Android repo (current working directory).
Read CLAUDE.md at the repo root first and obey all its rules, especially:
- markdown/ and docx/ packages must be pure JVM: NO android.* / androidx.* imports
  (enforced by app/src/test/java/dev/gold/mdvault/ConversionPurityTest.kt).
- Dependency allowlist: flexmark (5 modules), mammoth, jsoup only. All already
  declared in gradle/libs.versions.toml and app/build.gradle.kts — do not add dependencies.
- Kotlin package root: dev.gold.mdvault
To run Gradle: export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
then ./gradlew <task>. Do NOT run any git commands (no commit/push) — the reviewer commits.
Do NOT touch files outside your assigned scope.
`

const S0_PROMPT = `${COMMON}
## Task: create test fixtures (fixtures/ directory ONLY — no app code)

### DOCX fixtures (fixtures/docx/), 10 files
Script what python-docx can generate in fixtures/tools/generate_docx.py
(create a venv at fixtures/tools/.venv, pip install python-docx, run the script;
add ".venv/" to the root .gitignore). Document anything python-docx cannot
produce as a manual procedure in fixtures/docx/README.md.
1. simple-korean.docx — Korean headings (3 levels) + paragraphs
2. formatting.docx — bold/italic/mixed runs, chains of "word **bold** word"
3. table-basic.docx — 4x5 unmerged table, Korean cells, include cells ending with whitespace
4. table-merged.docx — table with merged cells (for warning-path testing)
5. images.docx — 3 PNG + 2 JPEG embedded, total size over 5MB (generate images programmatically)
6. lists.docx — bullet/numbered/nested lists
7. links.docx — normal links + a javascript: href + an external image reference
8. control-chars.docx — text containing control characters (\\u0008, \\u000B); if
   python-docx refuses them, inject by post-processing the XML inside the zip
9. llm-generated.docx — realistic LLM-output style (long paragraphs + table + code block)
10. large.docx — ~100 pages of text

### Markdown fixtures (fixtures/md/), 10 files
One each: headings, lists, table, tasklist, YAML front matter, code fences,
image with relative path, Korean text, emoji, and an LLM raw output where the
entire document is wrapped in an outer code fence.

For every fixture (docx and md), write an EXPECTED.md alongside describing the
expected conversion behavior/result.

### Verification
Run the generator script end-to-end, verify all 20 fixtures exist with plausible
sizes (images.docx > 5MB, large.docx substantial), and list them.
`

const S2_PROMPT = `${COMMON}
## Task S2: FlexmarkMarkdownEngine (markdown/ package, pure JVM)

1. In app/src/main/java/dev/gold/mdvault/document/ create ConversionWarning.kt:
   a sealed interface ConversionWarning with data classes
   UnsafeLinkDropped(val href: String) and UnsupportedFeature(val feature: String).
   (docx/ will add usages later — keep it dependency-free, pure Kotlin.)
2. In app/src/main/java/dev/gold/mdvault/markdown/ implement:
   - MarkdownEngine interface: toHtml(markdown: String): String,
     fromHtml(html: String): String, parseToAst(markdown: String) (flexmark Node),
     extractFrontMatter(markdown: String): Map<String, List<String>>
   - FlexmarkMarkdownEngine using flexmark core + html2md-converter +
     ext-tables + ext-gfm-tasklist + ext-yaml-front-matter (all already declared)
   - JsoupHtmlCleaner: based on Safelist.relaxed(); strip javascript: hrefs and
     on* event-handler attributes; collect each removal as
     ConversionWarning.UnsafeLinkDropped; return cleaned html + warnings.
3. Pure JUnit tests in app/src/test/java/dev/gold/mdvault/markdown/:
   round-trip MD -> HTML -> MD over every file in fixtures/md/ (tests run with
   app/ as working directory, so the fixture path is "../fixtures/md").
   Assert headings/lists/tables/links/image paths are preserved at the AST level
   (compare normalized ASTs or structural extracts, not raw strings).
   Also unit-test JsoupHtmlCleaner: javascript: href removal produces a warning.
4. Verify: ./gradlew test must pass (this also runs ConversionPurityTest —
   your code must not import android.*/androidx.*).
`

const S4_PROMPT = `${COMMON}
## Task S4: SAF repository (storage/ package — Android code allowed here)

1. In app/src/main/java/dev/gold/mdvault/storage/ implement:
   - SafDocumentRepository: DocumentFile is FORBIDDEN. Use
     DocumentsContract.buildChildDocumentsUriUsingTree() +
     ContentResolver.query() directly, projection limited to needed columns
     (document id, display name, mime type, last modified, size).
     Include FLAG_VIRTUAL_DOCUMENT check and openTypedAssetFileDescriptor
     branch for virtual documents. Expose list/read/write/create operations
     as suspend functions over InputStream/OutputStream.
   - VaultRepository: persist exactly ONE vault tree URI
     (takePersistableUriPermission); store recent documents as vault-relative
     paths in DataStore Preferences (dependency already declared).
2. Instrumentation test in app/src/androidTest/java/dev/gold/mdvault/storage/:
   create 200 files in app-local storage exposed via a test DocumentsProvider
   (or the simplest correct harness), measure list-query time, assert < 500ms.
   NOTE: no device is attached in this environment — the test must COMPILE but
   will be executed later on a real device. Do not try to run connectedAndroidTest.
3. Verify: ./gradlew assembleDebug assembleDebugAndroidTest test must pass.
`

phase('S0 Fixtures')
const s0 = await agent(S0_PROMPT, {
  label: 's0:fixtures',
  sandbox: 'workspace-write',
  schema: RESULT_SCHEMA,
  key: 's0-fixtures',
})
log(`S0 done: ${s0 ? s0.summary : 'skipped'}`)

phase('S2+S4 Spikes')
const [s2, s4] = await parallel([
  () => agent(S2_PROMPT, {
    label: 's2:flexmark',
    sandbox: 'workspace-write',
    effort: 'high',
    schema: RESULT_SCHEMA,
    key: 's2-flexmark',
  }),
  () => agent(S4_PROMPT, {
    label: 's4:saf',
    sandbox: 'workspace-write',
    effort: 'high',
    schema: RESULT_SCHEMA,
    key: 's4-saf',
  }),
])

return { s0, s2, s4 }
