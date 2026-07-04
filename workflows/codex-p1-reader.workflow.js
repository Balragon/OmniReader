export const meta = {
  name: 'codex-p1-reader',
  description: 'P1 reader polish: EXIF/GIF image viewer, DOCX MD-save with images, reading position + font size',
  phases: [
    { title: 'Image viewer', detail: 'EXIF rotation + animated GIF via ImageDecoder' },
    { title: 'MD save with images', detail: 'DOCX → MD save now includes extracted images' },
    { title: 'Reading position + font size', detail: 'DataStore-backed scroll restore and Aa text zoom' },
    { title: 'Review', detail: 'read-only diff review for bugs and rule violations' },
  ],
}

const RESULT_SCHEMA = {
  type: 'object',
  required: ['summary', 'changedFiles', 'notes'],
  properties: {
    summary: { type: 'string' },
    changedFiles: { type: 'array', items: { type: 'string' } },
    newFiles: { type: 'array', items: { type: 'string' } },
    notes: { type: 'string' },
    risks: { type: 'string' },
  },
  additionalProperties: false,
}

const COMMON = `
You are working in the mdvault Android app repo (repo root = your cwd).
Read CLAUDE.md at the repo root first and obey it strictly. Key rules:
- Kotlin + Jetpack Compose M3, minSdk 29 / targetSdk 34. Manual DI via AppContainer.kt (repo root package). Hilt/DocumentFile/Robolectric are BANNED.
- Dependency allowlist is closed: do NOT add any new Gradle dependency. Use Android framework APIs and already-present AndroidX libs only.
- markdown/ and docx/ packages must stay pure JVM (no android.*/androidx.* imports) — ConversionPurityTest enforces this.
- Priority order: data safety > offline > stability > editing convenience > DOCX fidelity > looks. NEVER overwrite existing user files.
- UI strings are Korean; code comments are Korean and sparse, matching existing style. Match surrounding code idiom.
- Do NOT run gradle (the sandbox blocks the gradle daemon socket — builds will fail spuriously). Just write correct code; the orchestrator builds and tests locally afterwards.
- Do NOT git commit. Leave changes in the working tree.
- docs/HANDOFF.md: do not edit it; the orchestrator logs the work.
Your final message must be raw JSON data per the requested schema, nothing else.
`

phase('Image viewer')
const imageResult = await agent(`${COMMON}
Task: improve the fullscreen image viewer in app/src/main/java/dev/gold/mdvault/preview/SingleDocumentViewerScreen.kt (composable FullscreenImageContent + helper decodeSampledBitmap).

Two requirements:
1. EXIF orientation: JPEG photos taken with a rotated camera must display upright. Today BitmapFactory ignores EXIF.
2. Animated GIF: .gif files must animate. Today only the first frame shows as a static bitmap.

Recommended approach (minSdk is 29, so android.graphics.ImageDecoder is always available):
- Use ImageDecoder.createSource(contentResolver, uri).
- For GIFs (and any animatable result): ImageDecoder.decodeDrawable → if the result is an AnimatedImageDrawable, call start() and host it in an AndroidView(ImageView) so it animates. Keep the existing pinch-zoom working: apply the same graphicsLayer(scale/translation) modifier to that AndroidView, and keep the transformable() gesture handling on the container exactly as today.
- For static images: ImageDecoder.decodeBitmap with an OnHeaderDecodedListener that calls setTargetSampleSize(...) so a huge image is downsampled to roughly the current 2x-screen budget (reuse/adapt calculateInSampleSize). ImageDecoder applies EXIF orientation automatically. Set allocator to software if needed for asImageBitmap().
- Keep the OOM guard property: a 100MP photo must not allocate full-size.
- Keep all the existing error mapping (SecurityException→PermissionLost, FileNotFoundException→DocumentMissing, RemoteException/IllegalStateException→ProviderUnavailable, generic → message) and the "여는 중…" placeholder and the black immersive MediaViewerScaffold, all unchanged.
- If ImageDecoder throws ImageDecoder.DecodeException or similar for a corrupt file, surface the same "이미지를 열 수 없습니다" error UI (no crash). You may keep the old BitmapFactory path as a fallback or delete it — your call; prefer the simpler correct code.
- Beware an existing landmine documented in the file: BitmapFactory.decodeStream with inJustDecodeBounds returns null even on success. If you keep that code, keep the comment.
- Note: files under /fixtures with .jpg extension are intentionally malformed (header-only) — do not treat them as evidence your code is wrong.

Also make sure the state handling still resets zoom/pan per uri and that recomposition doesn't restart the GIF unnecessarily (remember the drawable per uri).

Return JSON: {"summary": string, "changedFiles": string[], "newFiles": string[], "notes": string, "risks": string}.`,
  { label: 'exif-gif', sandbox: 'workspace-write', schema: RESULT_SCHEMA, key: 'p1-exif-gif' })

log(`Image viewer done: ${imageResult?.summary ?? 'no result'}`)

phase('MD save with images')
const mdSaveResult = await agent(`${COMMON}
Context: a previous task in this run may have already modified app/src/main/java/dev/gold/mdvault/preview/SingleDocumentViewerScreen.kt (image viewer internals: FullscreenImageContent/decodeSampledBitmap). Do not undo those changes; your work is in a different area of the same file.

Task: make the DOCX viewer's "MD 저장" action also save extracted images.

Current behavior (SingleDocumentViewerScreen.kt): when a .docx is opened, loadDocument() imports it via docxImporter.import(stream) { relativePath, contentType, bytes -> ... } writing image assets into cacheDir/opened/<sha12>/<relativePath>, and the markdown references those relative paths. The top bar shows a "MD 저장" TextButton that launches ActivityResultContracts.CreateDocument("text/markdown") and writes ONLY the markdown text; the notice literally says "MD 저장 완료 (이미지는 별도 저장되지 않음)".

New behavior:
- Track the list of extracted asset relative paths during import (extend ViewerState.Web with e.g. assetRelativePaths: List<String> plus keep the existing loadAsset). The asset bytes are already on disk under the cache assetRoot — also carry the assetRoot (or a resolver lambda) so saving can read them.
- If there are NO assets: keep today's CreateDocument flow unchanged.
- If there ARE assets: the button instead launches ActivityResultContracts.OpenDocumentTree() to pick a target folder. Then, via DocumentsContract directly (DocumentFile is banned):
  1. Create a subfolder named after the document (displayName without extension). If a child with that name already exists in the picked tree, use "name-2", "name-3", ... — NEVER overwrite anything (data safety rule).
  2. Inside it, create "<name>.md" with the markdown text, and create every asset file at its relative path (create intermediate subdirectories as needed, e.g. images/img1.png) so the markdown's relative image links resolve when that folder is later opened as a vault or viewed by other tools.
  3. Use appropriate MIME types for assets (derive from extension; fall back to application/octet-stream).
- Update the notice: success → "MD 저장 완료 (이미지 N개 포함)" (or the old text when there were no images); failure → keep "저장 실패: ..." style. Partial failure (some asset write throws) must not lose the markdown: write the .md first, and report how many assets succeeded.
- Keep everything on Dispatchers.IO like the current save code. Take persistable permission is NOT needed (one-shot write through the returned grant).
- If you need a small SAF helper, prefer a private function in this file or look at storage/SafDocumentRepository.kt for existing DocumentsContract idioms to imitate (do not refactor that repository).

Return JSON: {"summary": string, "changedFiles": string[], "newFiles": string[], "notes": string, "risks": string}.`,
  { label: 'md-save-images', sandbox: 'workspace-write', schema: RESULT_SCHEMA, key: 'p1-md-save' })

log(`MD save done: ${mdSaveResult?.summary ?? 'no result'}`)

phase('Reading position + font size')
const readerResult = await agent(`${COMMON}
Context: previous tasks in this run already modified app/src/main/java/dev/gold/mdvault/preview/SingleDocumentViewerScreen.kt (image viewer internals and the "MD 저장" flow). Do not undo those changes.

Task: add (A) a global reader font-size setting and (B) per-document reading-position memory.

New file — app/src/main/java/dev/gold/mdvault/settings/ReaderSettingsRepository.kt (package dev.gold.mdvault.settings, DataStore Preferences, name "reader_settings"):
- fontScalePercent: Flow<Int>, default 100. setFontScalePercent(value) clamped to a sane range.
- Reading positions keyed by a stable document key string. Store as a single preference encoding (like VaultRepository's RECENT_DOCUMENTS newline encoding — read storage/VaultRepository.kt for the idiom): key → position payload, most-recently-used first, capped at 100 entries. API: suspend fun readingPosition(key: String): String? and suspend fun saveReadingPosition(key: String, payload: String) (payload is an opaque string the caller formats, e.g. "web:0.42" or "pdf:3:120"). Keys/payloads must be Uri.encode-ed in the stored encoding so delimiters can't break it.
Wire it into AppContainer.kt (manual DI, follow existing style) and pass it where needed from ui/MainActivity.kt.

(A) Font size — applies to text-like documents rendered in WebView:
- SingleDocumentViewerScreen.kt: for ViewerState.Web documents, add an "Aa" TextButton in the top bar that cycles fontScalePercent through 85 → 100 → 115 → 130 → 150 → 85… and persists it. Apply with webView.settings.textZoom = fontScalePercent; when the value changes, update the existing WebView's settings (avoid recreating the WebView; use AndroidView update block or a state read inside factory/update).
- preview/MarkdownReaderScreen.kt (the vault reader): same Aa button in its top bar and same textZoom application, sharing the same repository value.
- Do NOT touch the editor; PDF and images are unaffected by font scale.

(B) Reading position:
- Document key: in SingleDocumentViewerScreen use uri.toString(); in MarkdownReaderScreen use the vault-relative path.
- WebView documents (md/txt/html/docx in both screens): save scroll as a ratio (webView.scrollY / max(1, contentHeight px)) so it survives font-scale reflow. Compute content height via webView.contentHeight * resources.displayMetrics.density (no JavaScript — JS stays disabled). Save on dispose (AndroidView onRelease / DisposableEffect) and restore after content loads: in a WebViewClient.onPageFinished (SingleDocumentViewerScreen already installs DocumentWebViewClient — extend it with an optional onPageFinished callback rather than replacing it; check preview/MarkdownReaderScreen.kt's client too) post a scroll to ratio*contentHeight. Guard: only restore once per load, and skip restore if ratio < 0.01.
- PDF (preview/PdfPagesView.kt): hoist a rememberLazyListState(initialFirstVisibleItemIndex = restoredIndex, initialFirstVisibleItemScrollOffset = restoredOffset); persist firstVisibleItemIndex/offset on dispose and debounced while scrolling (snapshotFlow + debounce ~1s). PdfPagesView needs the document key (pass uri) and the repository — add parameters with sensible defaults so existing call sites stay compilable, then update the call sites.
- Images: no position memory.
- Restoring must never crash on a stale payload (parse defensively; ignore malformed).

Keep the top bars visually consistent (TextButton style like the existing "←" / "MD 저장" buttons). Remember: MainActivity has two SingleDocumentViewerScreen call sites and one MarkdownReaderScreen call site — update all wiring.

Return JSON: {"summary": string, "changedFiles": string[], "newFiles": string[], "notes": string, "risks": string}.`,
  { label: 'position-fontsize', sandbox: 'workspace-write', schema: RESULT_SCHEMA, key: 'p1-pos-font' })

log(`Reader settings done: ${readerResult?.summary ?? 'no result'}`)

phase('Review')
const review = await agent(`${COMMON}
Read-only review task (do not modify files). Run "git diff" and "git status" in the repo to see all uncommitted changes (they implement: 1) ImageDecoder-based EXIF rotation + animated GIF in the image viewer, 2) DOCX "MD 저장" now saving extracted images via OpenDocumentTree/DocumentsContract, 3) reader font-size setting + per-document reading position via a new settings/ReaderSettingsRepository).

Check specifically:
- Compile-level mistakes: missing imports, signature mismatches between the three changes (they touched the same file sequentially), AppContainer/MainActivity wiring gaps.
- CLAUDE.md rule violations: new dependencies, DocumentFile usage, android.*/androidx.* imports leaking into markdown// docx/ packages, weakened tests.
- Data-safety: any path that can overwrite an existing user file; asset save collision handling.
- Behavior regressions: pinch zoom, immersive chrome toggle, VaultError mapping, WebView security flags (JS must stay disabled, network blocked) — textZoom must not have enabled anything else.
- Lifecycle bugs: AnimatedImageDrawable leaks/restarts, WebView scroll restore racing onPageFinished, DataStore writes on main thread.

Return JSON: {"summary": string, "changedFiles": [], "notes": "detailed findings list, each with file:line and severity (BLOCKER/WARN/NIT), or 'clean'", "risks": string}.`,
  { label: 'diff-review', sandbox: 'read-only', schema: RESULT_SCHEMA, key: 'p1-review' })

return {
  image: imageResult,
  mdSave: mdSaveResult,
  reader: readerResult,
  review: review,
}
