export const meta = {
  name: 'codex-localize',
  description: 'Localize OmniReader UI to follow device language: English default + Korean, via string resources',
  phases: [
    { title: 'Localize', detail: 'extract strings to resources, wire stringResource/getString' },
    { title: 'Review', detail: 'read-only check for missed strings and compile/context issues' },
  ],
}

const RESULT_SCHEMA = {
  type: 'object',
  required: ['summary', 'newFiles', 'modifiedFiles', 'notes'],
  properties: {
    summary: { type: 'string' },
    newFiles: { type: 'array', items: { type: 'string' } },
    modifiedFiles: { type: 'array', items: { type: 'string' } },
    notes: { type: 'string' },
    risks: { type: 'string' },
  },
  additionalProperties: false,
}

const COMMON = `
You are in the OmniReader Android app repo (repo root = cwd). Kotlin + Jetpack Compose M3, minSdk 29.
Do NOT add dependencies. Do NOT run gradle (sandbox blocks it; just write correct code). Do NOT git commit.
Match existing code style. Comments are Korean and sparse.
`

phase('Localize')
const impl = await agent(`${COMMON}
GOAL: make the app's UI follow the device language automatically. Right now all UI text is hardcoded Korean.
Add Android string resources with ENGLISH as the default and Korean as an override, and wire the code to them.

=== 1. Create res/values/strings.xml (English, DEFAULT) ===
Keep the existing <string name="app_name">OmniReader</string> (do NOT translate app_name).
Add all keys below with the ENGLISH value. Use %d / %s / positional %1$d placeholders exactly as shown.

  home_subtitle = "Document viewer"
  home_open_file = "Open file"
  home_open_hint = "Open a document on your device"
  home_recent = "Recent files"
  home_recent_empty_title = "No recent files"
  home_recent_empty_hint = "Documents you open with \\"Open file\\" show up here"
  home_recent_permission_expired = "Access expired — removed from the list"
  home_open_failed = "Couldn't open the file"
  time_just_now = "just now"
  time_minutes_ago = "%d min ago"
  time_hours_ago = "%d hr ago"
  time_days_ago = "%d days ago"
  time_long_ago = "a while ago"
  viewer_loading = "Opening…"
  viewer_default_title = "Document"
  viewer_save_md = "Save as MD"
  viewer_md_saved_no_images = "Saved as MD (images not included)"
  viewer_md_saved_images = "Saved as MD (%d images included)"
  viewer_md_saved_images_partial = "Saved as MD (%1$d/%2$d images included)"
  viewer_save_failed = "Save failed: %s"
  viewer_open_document_failed = "Couldn't open the document: %s"
  viewer_open_image_failed = "Couldn't open the image: %s"
  viewer_open_pdf_failed = "Couldn't open the PDF: %s"
  viewer_image_decode_failed = "Couldn't decode the image"
  viewer_docx_too_large = "This DOCX is too large to convert (over 50MB)"
  viewer_unsupported_format = "Unsupported format: %s"
  viewer_text_truncated = "File is large — showing the beginning only"
  pdf_page = "Page %d"
  pdf_page_rendering = "Rendering page %d…"
  error_permission_lost = "Folder access permission was lost"
  error_document_missing = "The file was moved or deleted"
  error_provider_unavailable = "Storage isn't responding. Try again shortly"
  error_unknown = "Something went wrong accessing storage"
  recovery_reselect_folder = "Choose folder again"
  recovery_back_to_list = "Back to list"

=== 2. Create res/values-ko/strings.xml (Korean) ===
Same keys, Korean values (these are the CURRENT hardcoded strings — reuse verbatim, adjusting only the placeholder form). Do NOT include app_name here.
  home_subtitle = "문서 뷰어"
  home_open_file = "파일 열기"
  home_open_hint = "기기의 문서를 열어 읽기"
  home_recent = "최근 파일"
  home_recent_empty_title = "최근에 연 파일이 없습니다"
  home_recent_empty_hint = "\\"파일 열기\\"로 문서를 열면 여기에 쌓입니다"
  home_recent_permission_expired = "권한이 만료되어 목록에서 제거했습니다"
  home_open_failed = "파일을 열 수 없습니다"
  time_just_now = "방금"
  time_minutes_ago = "%d분 전"
  time_hours_ago = "%d시간 전"
  time_days_ago = "%d일 전"
  time_long_ago = "오래 전"
  viewer_loading = "여는 중…"
  viewer_default_title = "문서"
  viewer_save_md = "MD 저장"
  viewer_md_saved_no_images = "MD 저장 완료 (이미지는 별도 저장되지 않음)"
  viewer_md_saved_images = "MD 저장 완료 (이미지 %d개 포함)"
  viewer_md_saved_images_partial = "MD 저장 완료 (이미지 %1$d/%2$d개 포함)"
  viewer_save_failed = "저장 실패: %s"
  viewer_open_document_failed = "문서를 열 수 없습니다: %s"
  viewer_open_image_failed = "이미지를 열 수 없습니다: %s"
  viewer_open_pdf_failed = "PDF를 열 수 없습니다: %s"
  viewer_image_decode_failed = "이미지를 디코딩할 수 없습니다"
  viewer_docx_too_large = "DOCX 파일이 너무 커서 변환할 수 없습니다 (50MB 초과)"
  viewer_unsupported_format = "지원하지 않는 형식입니다: %s"
  viewer_text_truncated = "파일이 너무 커서 앞부분만 표시합니다"
  pdf_page = "페이지 %d"
  pdf_page_rendering = "페이지 %d 렌더링 중…"
  error_permission_lost = "폴더 접근 권한이 사라졌습니다"
  error_document_missing = "파일이 이동 되었거나 삭제되었습니다"
  error_provider_unavailable = "저장소가 응답하지 않습니다. 잠시 후 다시 시도하세요"
  error_unknown = "저장소 작업 중 문제가 발생했습니다"
  recovery_reselect_folder = "폴더 다시 선택"
  recovery_back_to_list = "목록으로"

Note on XML escaping: in strings.xml an apostrophe must be \\' and a double quote \\" (or wrap value in double quotes). The "…" ellipsis char and Korean are fine as-is. "%" is literal here (single arg), but with a positional arg like %1$d, other literal % must be %%. There are no stray % in these strings.

=== 3. Wire the code (replace hardcoded Korean with resources) ===
Files: ui/HomeScreen.kt, ui/VaultErrorUi.kt, preview/SingleDocumentViewerScreen.kt, preview/PdfPagesView.kt.
Rules:
- In @Composable code: use stringResource(R.string.key) or stringResource(R.string.key, arg). Import dev.gold.mdvault.R and androidx.compose.ui.res.stringResource.
- In NON-composable code that already has a Context (e.g. loadDocument gets 'resolver' from a composable; the LaunchedEffect has 'context'; coroutine 'scope.launch' blocks capture 'context' via LocalContext.current which is already a val in these composables): use context.getString(R.string.key, arg). Pass 'context' into loadDocument(...) as a new parameter (the call site in the LaunchedEffect has context) so it can resolve viewer_docx_too_large, viewer_unsupported_format, viewer_text_truncated, viewer_default_title. relativeTime(...) in HomeScreen: give it a Context param (call site is composable) and use context.getString for the time strings.
- The three "MD 저장 완료 …" notices and "저장 실패: …" are built inside scope.launch(Dispatchers.IO) in a composable — 'context' is in scope; use context.getString(R.string.viewer_md_saved_images, saved) etc. For the partial one use context.getString(R.string.viewer_md_saved_images_partial, saved, total).
- Where a message currently appends a raw exception message (e.g. "문서를 열 수 없습니다: <errorMessage>"), pass that raw string as the %s arg: stringResource(R.string.viewer_open_document_failed, error.message).

=== 4. Refactor VaultErrorUi (ui/VaultErrorUi.kt) ===
Currently VaultErrorUi holds a resolved Korean 'message: String'. Change it to carry a string resource id, resolved at display time (composable). Concretely:
- data class VaultErrorUi(val messageRes: Int? = null, val rawMessage: String? = null, val recovery: VaultErrorRecovery? = null)
  (messageRes annotated @androidx.annotation.StringRes). Keep rawMessage for arbitrary exception text.
- toVaultErrorUi(): set messageRes = R.string.error_permission_lost / error_document_missing / error_provider_unavailable / error_unknown (drop the Korean literals).
- Add a @Composable helper: '@Composable internal fun VaultErrorUi.text(): String = messageRes?.let { stringResource(it) } ?: rawMessage ?: emptyString', and use it wherever error.message was displayed (SingleDocumentViewerScreen error branches, PdfPagesView error branch). (Use an actual empty string literal in code.)
- Callers that did 'VaultErrorUi(e.message ?: e.javaClass.simpleName)' now pass rawMessage = e.message ?: e.javaClass.simpleName.
- VaultErrorRecoveryButton: replace "폴더 다시 선택"/"목록으로" with stringResource(R.string.recovery_reselect_folder / recovery_back_to_list).
- The error display sites currently do e.g. Text("문서를 열 수 없습니다: \${currentState.error.message}"). Change to stringResource(R.string.viewer_open_document_failed, currentState.error.text()) — but note .text() is @Composable so resolve it into a val first: 'val msg = currentState.error.text()' then Text(stringResource(R.string.viewer_open_document_failed, msg)). Same pattern for PdfPagesView (viewer_open_pdf_failed) and the fullscreen image error (viewer_open_image_failed).

=== 5. Leave alone ===
- storage/VaultError.kt internal English exception messages (dev/log only) — do not touch.
- Log.* messages — do not touch.
- Deep SAF/image helper exceptions in SingleDocumentViewerScreen (e.g. "MD 파일을 만들 수 없습니다", "이미지 경로가 올바르지 않습니다", "이미지 정보를 읽을 수 없습니다", "파일 디스크립터를 열 수 없음", "저장 폴더를 만들 수 없습니다", "이미지 파일을 …", "이미지 폴더 …", "대상 폴더를 읽을 수 없습니다"): these are thrown deep and only surface as the %s detail of a localized "save failed"/"open failed" message. Convert THESE specific literal messages to concise ENGLISH (they are diagnostic detail), so the app has no leftover hardcoded Korean. Example: "MD 파일을 만들 수 없습니다" -> "Couldn't create the MD file". Keep them as plain throw IOException("...") strings (no resource needed).
- Comments containing Korean (KDoc, // …) stay Korean.
- pdf_page ("페이지 %d") is a contentDescription/label — localize it via stringResource (PdfPageItem is composable).

After wiring, there must be NO hardcoded Korean string literals left in the UI code paths (only comments and the intentionally-English inner exceptions). Double-check ui/HomeScreen.kt, ui/VaultErrorUi.kt, preview/SingleDocumentViewerScreen.kt, preview/PdfPagesView.kt.

Return JSON: {"summary","newFiles","modifiedFiles","notes","risks"}.`,
  { label: 'localize', sandbox: 'workspace-write', schema: RESULT_SCHEMA, key: 'loc-impl' })

log(`Localize done: ${impl?.summary ?? 'no result'}`)

phase('Review')
const review = await agent(`${COMMON}
Read-only review (do NOT modify). The working tree was just localized (English default res/values/strings.xml + res/values-ko/strings.xml, code wired to stringResource/getString). Run git diff/status.

Check and report file:line issues:
1. COMPILE: every stringResource() call is inside a @Composable; non-composable uses context.getString with a valid Context in scope; loadDocument/relativeTime got a Context param and all call sites pass it; VaultErrorUi.text() is @Composable and its result is read into a val before being passed as a format arg (not called inline where a plain String is needed); R is imported (dev.gold.mdvault.R); stringResource import present where used.
2. RESOURCE INTEGRITY: res/values/strings.xml and res/values-ko/strings.xml have the SAME set of keys; placeholders match between EN/KO for each key (%d vs %1$d etc.); XML is well-formed; apostrophes/quotes escaped; app_name only in values/ (English) and equals OmniReader.
3. COMPLETENESS: no hardcoded Korean string literals remain in ui/HomeScreen.kt, ui/VaultErrorUi.kt, preview/SingleDocumentViewerScreen.kt, preview/PdfPagesView.kt EXCEPT (a) Korean comments and (b) the deep inner exceptions that were intentionally converted to English. Grep for Hangul in string literals to verify.
4. BEHAVIOR: VaultError.kt untouched; Log.* messages untouched; format args in the right order (esp. viewer_md_saved_images_partial saved then total).

Return JSON {"summary","newFiles":[],"modifiedFiles":[],"notes":"findings with file:line + BLOCKER/WARN/NIT, or 'clean'","risks"}.`,
  { label: 'review', sandbox: 'read-only', schema: RESULT_SCHEMA, key: 'loc-review' })

return { impl, review }
