export const meta = {
  name: 'mdvault-p0-hardening',
  description: 'Release P0 fixes: state restoration, OOM gates, SAF error modeling (sequential)',
  phases: [
    { title: 'State', detail: 'rememberSaveable + editor draft protection' },
    { title: 'OOM', detail: 'size gates, image downsampling' },
    { title: 'Errors', detail: 'SAF failure modeling with Korean recovery UI' },
  ],
}

const RESULT_SCHEMA = {
  type: 'object',
  required: ['summary', 'files', 'verification', 'issues'],
  properties: {
    summary: { type: 'string' },
    files: { type: 'array', items: { type: 'string' } },
    verification: { type: 'string' },
    issues: { type: 'array', items: { type: 'string' } },
  },
}

const COMMON = `
## Repository context
"mdvault" Android repo (cwd). Read CLAUDE.md and docs/HANDOFF.md first.
Kotlin + Compose M3, manual DI, NO new dependencies, Korean UI strings.
Gradle CANNOT run in your sandbox — compile-safe code only, reviewer builds.
Do NOT run git. Previous tasks in this workflow may have already modified
files — always read current file state before editing.
`

phase('State')
const state = await agent(`${COMMON}
## Task P0-A: survive rotation and process death

1. MdvaultApp (ui/MainActivity.kt): convert navigation state to
   rememberSaveable — screen (enum: use a String or custom Saver),
   viewerUri (String), directoryBackStack (List<String>), editorPath.
   Verify each type has a working Saver (Uri -> store as String).
2. Editor draft protection (ui/EditorShellScreen.kt +
   editor/ComposeEditorPort.kt): unsaved editor text must survive process
   death. TextFieldState content can exceed savedInstanceState budget
   (50KB+ docs), so do NOT put the text in the Bundle. Instead: autosave a
   draft file to context.cacheDir/drafts/<sha12-of-path>.md whenever typing
   pauses (reuse a 1-2s debounce; snapshotFlow pattern exists in
   ComposeEditorPort). On (re)opening the editor for a path: if a draft
   newer than the vault file exists and differs, offer "임시 저장본 복원"
   / "무시" choice. Delete the draft on explicit save. Keep it simple and
   data-safe (CLAUDE.md 우선순위: 데이터 안전성 최우선).
3. Scroll: LazyListState in FileListScreen already survives config change
   only if remembered with rememberSaveable-backed state — use
   rememberLazyListState() (it saves itself) if not already.
Return files + risks.`, {
  label: 'p0:state',
  sandbox: 'workspace-write',
  effort: 'high',
  schema: RESULT_SCHEMA,
  key: 'p0-state',
})
log(`state: ${state ? state.summary.slice(0, 70) : 'skipped'}`)

phase('OOM')
const oom = await agent(`${COMMON}
## Task P0-B: large-input OOM protection

1. Text-based types (md/txt/html) in preview/SingleDocumentViewerScreen.kt
   and preview/MarkdownReaderScreen.kt and ui/EditorShellScreen.kt:
   before readBytes(), stat the size (ContentResolver query SIZE column or
   AssetFileDescriptor length; vault: SafDocument.size). If > 4MB, show
   Korean notice "파일이 너무 커서 앞부분만 표시합니다" and read only the
   first 4MB (readNBytes-style loop); editor refuses >2MB with a clear
   Korean message instead of opening.
2. Images: find the native image decode path (preview/ media viewer).
   Use BitmapFactory.Options with inJustDecodeBounds first, then
   inSampleSize so the decoded bitmap is at most ~2x screen resolution.
   Handle decode failure with a Korean error message.
3. PDF (preview/PdfPagesView.kt): pages render at TARGET_WIDTH_PX ARGB_8888
   and LazyColumn evicts, which is acceptable — but add a page-count guard:
   if pageCount > 300, render pages on demand only (already lazy) and make
   sure placeholder height approximates real aspect to avoid scrollbar jumps
   (obtain first page aspect once and reuse). Skip if already fine — judge.
4. DOCX open path buffers whole file (DocxXmlSanitizer in-memory) — do NOT
   restructure the pure pipeline; just add a size gate (>50MB → Korean
   message refusing conversion) at the call site.
Return files + risks.`, {
  label: 'p0:oom',
  sandbox: 'workspace-write',
  effort: 'high',
  schema: RESULT_SCHEMA,
  key: 'p0-oom',
})
log(`oom: ${oom ? oom.summary.slice(0, 70) : 'skipped'}`)

phase('Errors')
const errors = await agent(`${COMMON}
## Task P0-C: SAF failure modeling — no raw exceptions in UI

1. Define storage/VaultError.kt: sealed class VaultError : Exception() with
   PermissionLost, DocumentMissing(name), ProviderUnavailable, Unknown(cause).
   In storage/VaultRepository.kt and storage/SafDocumentRepository.kt map
   failures: SecurityException -> PermissionLost; resolve() null / null
   cursor when the tree permission is gone -> distinguish PermissionLost
   (check persistedUriPermissions contains the tree) vs DocumentMissing;
   RemoteException/IllegalStateException from provider -> ProviderUnavailable.
   IMPORTANT: null cursor must NOT silently mean "empty folder".
2. UI (ui/FileListScreen.kt, preview/MarkdownReaderScreen.kt,
   ui/EditorShellScreen.kt, preview/SingleDocumentViewerScreen.kt): catch
   VaultError and show Korean messages with a recovery action where
   applicable — PermissionLost: "폴더 접근 권한이 사라졌습니다" + "폴더
   다시 선택" button routing to VaultSetup; DocumentMissing: "파일이 이동
   되었거나 삭제되었습니다" + back to list; ProviderUnavailable: "저장소가
   응답하지 않습니다. 잠시 후 다시 시도하세요". Raw e.message must no longer
   reach the screen for these paths (keep it in Log.w only).
3. Do not change public method signatures beyond throwing the new sealed
   error type (it extends Exception, so callers compile unchanged).
Return files + risks.`, {
  label: 'p0:errors',
  sandbox: 'workspace-write',
  effort: 'high',
  schema: RESULT_SCHEMA,
  key: 'p0-errors',
})

return { state, oom, errors }
