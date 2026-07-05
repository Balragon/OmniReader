export const meta = {
  name: 'codex-pure-viewer',
  description: 'Strip mdvault to a pure document viewer: remove editor + vault + DOCX-export code, keep the file-tap viewer path',
  phases: [
    { title: 'Strip', detail: 'move shared web helpers, delete editor/vault/export, rewire MainActivity/AppContainer/HomeScreen' },
    { title: 'Review', detail: 'read-only check for dangling references and viewer regressions' },
  ],
}

const RESULT_SCHEMA = {
  type: 'object',
  required: ['summary', 'deletedFiles', 'newFiles', 'modifiedFiles', 'notes'],
  properties: {
    summary: { type: 'string' },
    deletedFiles: { type: 'array', items: { type: 'string' } },
    newFiles: { type: 'array', items: { type: 'string' } },
    modifiedFiles: { type: 'array', items: { type: 'string' } },
    notes: { type: 'string' },
    risks: { type: 'string' },
  },
  additionalProperties: false,
}

const COMMON = `
You are working in the mdvault Android app repo (repo root = your cwd). Read CLAUDE.md at the repo root first.
Stack: Kotlin + Jetpack Compose M3, minSdk 29 / targetSdk 34, manual DI via AppContainer.kt. Do NOT add dependencies. Do NOT run gradle (sandbox blocks the daemon; builds fail spuriously — just write correct code, the orchestrator builds locally). Do NOT git commit. UI strings and comments are Korean; match existing style.
`

phase('Strip')
const strip = await agent(`${COMMON}
GOAL: convert mdvault into a PURE DOCUMENT VIEWER. The app's only job is: another app (Files) hands us a file via ACTION_VIEW/ACTION_SEND and we display it (md/txt/html/pdf/image/docx-rendered-as-markdown). Remove ALL editing, the "내 폴더"/vault (SAF tree) browser, and DOCX *export/creation*. Keep DOCX *import* (viewing a .docx by converting it to markdown in-memory).

This must all land as ONE coherent change that compiles: every deletion's references must be removed in the same pass.

=== STEP 1 (DO THIS FIRST): extract shared WebView helpers so the viewer survives ===
The file app/src/main/java/dev/gold/mdvault/preview/MarkdownReaderScreen.kt is being DELETED, but it defines helpers that app/src/main/java/dev/gold/mdvault/preview/SingleDocumentViewerScreen.kt depends on. Create a new file app/src/main/java/dev/gold/mdvault/preview/DocumentWebView.kt (package dev.gold.mdvault.preview) and MOVE these declarations into it verbatim (adjust imports):
  - const VAULT_HOST and fun vaultBaseUrl(baseDirectory: String)
  - class DocumentWebViewClient — but DROP its onOpenNote constructor parameter and the internal-link navigation branch that uses it (the viewer only needs loadAsset + onPageFinished; single-file mode has no cross-note navigation). Keep shouldInterceptRequest (asset interception via loadAsset), the shouldOverrideUrlLoading that blocks in-page navigation, onPageFinished forwarding, and mimeTypeFor.
  - fun nextReaderFontScalePercent, fun String?.webReadingRatioOrNull, fun saveWebReadingPositionAsync, fun webContentHeightPx, const WEB_RESTORE_MIN_RATIO, and the private READER_FONT_SCALE_STEPS list they use.
Then verify SingleDocumentViewerScreen.kt still resolves all of these (same package, so no import needed) and that its DocumentWebViewClient(...) call site does not pass onOpenNote.

=== STEP 2: delete these main-source files ===
  app/src/main/java/dev/gold/mdvault/editor/ComposeEditorPort.kt
  app/src/main/java/dev/gold/mdvault/editor/EditorPort.kt
  app/src/main/java/dev/gold/mdvault/editor/MarkdownEditorScreen.kt
  app/src/main/java/dev/gold/mdvault/editor/S5KoreanSample.kt
  app/src/main/java/dev/gold/mdvault/ui/EditorShellScreen.kt
  app/src/main/java/dev/gold/mdvault/ui/FileListScreen.kt
  app/src/main/java/dev/gold/mdvault/ui/VaultSetupScreen.kt
  app/src/main/java/dev/gold/mdvault/preview/MarkdownReaderScreen.kt   (AFTER step 1 extraction)
  app/src/main/java/dev/gold/mdvault/storage/VaultRepository.kt
  app/src/main/java/dev/gold/mdvault/storage/SafDocumentRepository.kt
  app/src/main/java/dev/gold/mdvault/document/VaultDocxExporter.kt
  app/src/main/java/dev/gold/mdvault/docx/DocxExportEngine.kt
  app/src/main/java/dev/gold/mdvault/docx/OoxmlWriter.kt
  app/src/main/java/dev/gold/mdvault/docx/SimpleOoxmlDocxExportEngine.kt

=== STEP 3: delete these test files ===
  app/src/androidTest/java/dev/gold/mdvault/editor/EditorImeCompositionUndoTest.kt
  app/src/androidTest/java/dev/gold/mdvault/editor/EditorUndoTest.kt
  app/src/androidTest/java/dev/gold/mdvault/storage/SafDocumentRepositoryPerformanceTest.kt
  app/src/androidTest/java/dev/gold/mdvault/storage/TestVaultDocumentsProvider.kt
  app/src/test/java/dev/gold/mdvault/docx/SimpleOoxmlDocxExportEngineTest.kt
  app/src/test/java/dev/gold/mdvault/docx/S3SampleDocxGenerator.kt
KEEP all other tests (ConversionPurityTest, DocumentTypeDetectorTest, DocxToMarkdownImporterTest, DocxXmlSanitizerTest, ImageDimensionReaderTest, MammothDocxImportEngineTest, FlexmarkMarkdownEngineTest, PreviewHtmlBuilderTest). If any KEPT test references a deleted export/vault symbol, it does not — but double-check FlexmarkMarkdownEngineTest and MammothDocxImportEngineTest still compile (they only use ConversionWarning + import, which stay).

=== STEP 4: rewire AppContainer.kt ===
Remove fields: vaultRepository, docxExportEngine, vaultDocxExporter (and their imports: VaultDocxExporter, DocxExportEngine, SimpleOoxmlDocxExportEngine, VaultRepository). KEEP: recentFilesRepository, readerSettingsRepository, htmlCleaner, markdownEngine, docxImportEngine (MammothDocxImportEngine), docxToMarkdownImporter.

=== STEP 5: rewire ui/MainActivity.kt ===
This is the biggest edit. The external-intent path (onCreate reads ACTION_VIEW/ACTION_SEND → externalUriState → SingleDocumentViewerScreen with onBack=finish()) STAYS unchanged. Rewrite the in-app MdvaultApp so there is NO vault. Specifically:
  - Delete the Screen enum entirely (Home/VaultSetup/FileList/Reader/Editor/Spike) and its ScreenSaver.
  - Delete VaultState, the vaultTreeUri collectAsState, DirectoryBackStackSaver, editorPath, directoryBackStack, LoadingScreen, and the whole when(screen) block.
  - Delete SpikeHome, S1SpikeScreen, S4PerfScreen and their imports (ComposeEditorPort, MarkdownEditorScreen, s5KoreanSample, MarkdownReaderScreen).
  - New MdvaultApp(container): just a viewer host. Keep a rememberSaveable<String?> viewerUriString. When null, show HomeScreen(recentFilesRepository = container.recentFilesRepository, onOpenDocument = { viewerUriString = it.toString() }). When non-null, show SingleDocumentViewerScreen(uri = parsed, markdownEngine, docxImporter = container.docxToMarkdownImporter, recentFiles = container.recentFilesRepository, readerSettingsRepository = container.readerSettingsRepository, onBack = { viewerUriString = null }). Do NOT pass onOpenVaultSetup anywhere.
  - Remove now-unused imports.

=== STEP 6: rewire ui/HomeScreen.kt ===
Remove the "내 폴더" Button, and the parameters canOpenVault and onOpenVault. HomeScreen keeps: recentFilesRepository, onOpenDocument, modifier. Everything else (파일 열기 picker, 최근 파일 list, notice) stays.

=== STEP 7: light cleanup (only if trivial & safe) ===
  - ui/VaultErrorUi.kt: VaultErrorRecoveryButton likely has an onOpenVaultSetup param. The viewer callers now always pass null / omit it. Keep the file; if onOpenVaultSetup becomes entirely unused you MAY make it default-null, but do NOT break PdfPagesView/SingleDocumentViewerScreen call sites. Prefer minimal change.
  - storage/BoundedDocumentReads.kt: if it defines a vault-only helper vaultDocumentSize that is now unreferenced, remove just that function; KEEP readTextBounded, openableSize, BoundedTextRead (viewer uses them).
  - Do NOT rename VaultError / VaultErrorUi (still used as the viewer's error type; renaming ripples too far).

Do NOT touch: CLAUDE.md, docs/*, AndroidManifest.xml, proguard-rules.pro, gradle files, the markdown/ and docx-import files, PreviewHtmlBuilder, PdfPagesView internals, ReaderSettingsRepository, RecentFilesRepository, VaultError.kt.

Return JSON per schema. In notes, list any place you were unsure or left a compile risk.`,
  { label: 'strip', sandbox: 'workspace-write', schema: RESULT_SCHEMA, key: 'pv-strip' })

log(`Strip done: deleted ${strip?.deletedFiles?.length ?? '?'}, new ${strip?.newFiles?.length ?? '?'}, modified ${strip?.modifiedFiles?.length ?? '?'}`)

phase('Review')
const review = await agent(`${COMMON}
Read-only review (do NOT modify files). The working tree was just refactored to a PURE VIEWER: editor, vault (SAF tree browser), and DOCX export were removed; the file-tap viewer path was kept. Run "git status" and "git diff" to see everything.

Verify, and report any problem with file:line:
1. COMPILE INTEGRITY: no remaining references to deleted symbols anywhere in app/src/main or the KEPT tests — grep for: VaultRepository, SafDocumentRepository, VaultDocxExporter, DocxExportEngine, SimpleOoxmlDocxExportEngine, OoxmlWriter, ComposeEditorPort, EditorPort, MarkdownEditorScreen, MarkdownReaderScreen, EditorShellScreen, FileListScreen, VaultSetupScreen, s5KoreanSample. Each hit outside a deleted file (or the new DocumentWebView.kt legitimately) is a BLOCKER.
2. SHARED HELPERS: DocumentWebView.kt exists and defines vaultBaseUrl, DocumentWebViewClient, nextReaderFontScalePercent, webReadingRatioOrNull, saveWebReadingPositionAsync, webContentHeightPx, WEB_RESTORE_MIN_RATIO; SingleDocumentViewerScreen.kt resolves all of them and does NOT pass onOpenNote.
3. VIEWER STILL WHOLE: SingleDocumentViewerScreen (md/txt/html/pdf/image/docx routing, MD 저장, Aa font, reading position), PdfPagesView, HomeScreen (파일 열기 + 최근 파일, NO 내 폴더 button), MainActivity external-intent path (ACTION_VIEW/SEND → viewer, onBack=finish), and AppContainer's kept fields are all intact and consistently wired.
4. NO ORPHANS: no import of a deleted class remains; no unused param left dangling that would fail to compile.
5. WebView security preserved in DocumentWebView.kt / viewer: JS disabled, network blocked, file/content access disabled.

Return JSON: {"summary": string, "deletedFiles": [], "newFiles": [], "modifiedFiles": [], "notes": "findings with file:line and BLOCKER/WARN/NIT, or 'clean'", "risks": string}.`,
  { label: 'review', sandbox: 'read-only', schema: RESULT_SCHEMA, key: 'pv-review' })

return { strip, review }
