export const meta = {
  name: 'mdvault-codex-viewer-home',
  description: 'Batch 2: viewer-first home UI — recents, open-file, vault demoted, spike hidden',
  phases: [{ title: 'Home UI', detail: 'single Codex agent reworking ui/ entry flow' }],
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

const PROMPT = `
## Repository context
You are working in the "mdvault" Android repo (current working directory).
Read CLAUDE.md first and obey it. Kotlin + Compose M3, manual DI (AppContainer),
no new dependencies, no navigation-compose, no Hilt.
IMPORTANT: Gradle CANNOT run in your sandbox (daemon socket denied) — do NOT
attempt ./gradlew. Write compile-safe code; the reviewer compiles after you.
Do NOT run git. Do NOT create GitHub issues.

## Product pivot (already decided)
mdvault is primarily a document VIEWER ("내 파일"에서 파일 탭 → 열리는 연결 앱).
Batch 1 already landed (read these frozen files first):
- preview/SingleDocumentViewerScreen.kt  (uri 기반 뷰어: md/txt/docx/html/pdf/이미지)
- storage/RecentFilesRepository.kt       (최근 파일 DataStore)
- document/DocumentKind.kt               (타입 판별)
- ui/MainActivity.kt                     (VIEW/SEND 인텐트 → 뷰어 단독 표시는 이미 구현됨)

## Task: rework the in-app entry flow (ui/ only + MainActivity wiring)

1. New ui/HomeScreen.kt — the FIRST screen on normal launch, vault NOT required:
   - "파일 열기" button: ActivityResultContracts.OpenDocument with mime types
     ["text/markdown","text/plain","text/html","application/pdf",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "image/*"]. On result: try
     contentResolver.takePersistableUriPermission(uri, READ) inside runCatching
     (picker URIs support it), then open the in-app viewer.
   - Recent files list from RecentFilesRepository.recentFiles (name + relative
     time). Tap → open viewer with Uri.parse(entry.uri); if opening throws
     SecurityException, call recentFiles.remove(entry.uri) and show a small
     "권한이 만료되어 목록에서 제거했습니다" notice.
   - "내 폴더" button → existing vault flow: if vault configured go to the
     file browser (FileListScreen), else VaultSetupScreen first.
   - Clean, simple Korean labels. No developer jargon.
2. MdvaultApp (MainActivity.kt) rework:
   - Start screen = HomeScreen always (no vault gate at startup).
   - Add in-app viewer route: state var for viewer Uri; when set, show
     SingleDocumentViewerScreen(uri, markdownEngine, docxImporter,
     recentFiles, onBack = clear state back to Home).
   - Keep existing vault browser/reader/editor routes reachable from
     "내 폴더". Keep the external-intent path in MainActivity.onCreate as is.
3. FileListScreen: list ALL viewer-supported types (md, docx, pdf, html, txt,
   png/jpg/webp/gif) plus directories, not only .md. Tapping a non-md file
   opens SingleDocumentViewerScreen with the SafDocument's uri (pass a new
   callback up to MdvaultApp). md files keep the existing Reader route.
   Rename the "볼트 루트" title to "내 폴더". Keep 새 노트/DOCX 가져오기.
4. Hide developer entries: remove the Spike button from FileListScreen's
   header; instead add a small "Spike" TextButton at the bottom of
   VaultSetupScreen only. Remove the "볼트 설정" button from FileListScreen
   header too — put a small settings-ish TextButton ("폴더 변경") that opens
   VaultSetupScreen.
Return file list, what you verified by reading, and risks.
`

phase('Home UI')
const result = await agent(PROMPT, {
  label: 'batch2:home-ui',
  sandbox: 'workspace-write',
  effort: 'high',
  schema: RESULT_SCHEMA,
  key: 'batch2-home',
})
return result
