export const meta = {
  name: 'mdvault-codex-p0-shell',
  description: 'mdvault Phase 2: P0-1 vault wiring then P0-2 markdown shell (sequential, Codex)',
  phases: [
    { title: 'P0-1 Vault', detail: 'AppContainer context wiring + vault picker screen' },
    { title: 'P0-2 Shell', detail: 'file list, editor shell, DOCX import flow' },
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
You are working in the "mdvault" Android repo (current working directory).
Read CLAUDE.md at the repo root first and obey all its rules. Key facts:
- Kotlin + Jetpack Compose Material 3, package root dev.gold.mdvault, manual DI
  via AppContainer (Hilt forbidden). Do NOT add any dependency — in particular
  do NOT add navigation-compose; use plain state-based navigation.
- Existing, FROZEN interfaces you must use as-is (read them first):
  storage/SafDocumentRepository.kt, storage/VaultRepository.kt,
  document/DocxToMarkdownImporter.kt, docx/DocxImportEngine.kt (ImageSink),
  editor/EditorPort.kt, editor/ComposeEditorPort.kt, editor/MarkdownEditorScreen.kt,
  ui/MainActivity.kt (S1/S5 spike screens — must remain reachable).
- IMPORTANT: Gradle CANNOT run in your sandbox (daemon socket bind is denied).
  Do NOT attempt ./gradlew. Write compile-safe code; the reviewer compiles and
  tests after you finish. Do NOT run any git commands. Do not create GitHub issues.
- UI labels in Korean is fine.
`

const P01_PROMPT = `${COMMON}
## Task P0-1: vault wiring + vault picker

1. AppContainer: change to class AppContainer(context: android.content.Context)
   (use applicationContext). Add: val vaultRepository = VaultRepository(context)
   and keep existing engine properties unchanged.
2. MainActivity: construct AppContainer(applicationContext) lazily in onCreate
   (it currently does AppContainer() as a field — adjust carefully).
3. New ui/VaultSetupScreen.kt: shown when vaultRepository has no persisted tree
   URI (collect vaultTreeUri flow). One button "볼트 폴더 선택" launching
   ActivityResultContracts.OpenDocumentTree; on result call
   vaultRepository.setVaultTreeUri(uri) in a coroutine. Show the current vault
   path and a "볼트 변경" option when already configured.
4. Restructure MainActivity into state-based navigation:
   sealed class/enum Screen { VaultSetup, Home, Spike }. Home is a placeholder
   screen for now (P0-2 fills it) showing vault status + a "Spike" button that
   reaches the existing SpikeHome (S1/S5 screens must keep working).
   App starts at VaultSetup if no vault, else Home.
Keep everything compile-safe against the frozen interfaces. Return the file
list, what you verified by careful reading, and any risks.
`

const P02_PROMPT = `${COMMON}
## Task P0-2: markdown shell (runs AFTER P0-1 — read the current code first)

Build the daily-driver shell on top of P0-1's navigation:
1. ui/FileListScreen.kt — list vault root entries via vaultRepository.list():
   directories first then .md files (filter displayName endsWith ".md" or
   directory), tap directory navigates into it (keep a relative-path back stack),
   tap file opens editor. TopAppBar shows current path. Actions:
   - "새 노트": creates "note-<n>.md" (first free n) in current directory via
     vaultRepository.create(path, "text/markdown"), then opens it in the editor
   - "DOCX 가져오기": ActivityResultContracts.OpenDocument for mime types
     [application/vnd.openxmlformats-officedocument.wordprocessingml.document,
     application/octet-stream]; run AppContainer.docxToMarkdownImporter on
     Dispatchers.IO. ImageSink must write each image into the vault under
     "<current-dir>/media/" using vaultRepository (create the media directory
     first with DocumentsContract.Document.MIME_TYPE_DIR if missing, via
     vaultRepository.create). Then write "<docx-name>.md" with the markdown.
     NEVER write back to the source DOCX. Show result (warnings count) in a
     snackbar or status text.
2. ui/EditorShellScreen.kt — loads the file content (vaultRepository.read) into
   a ComposeEditorPort, renders editor/MarkdownEditorScreen, TopAppBar with
   back + "저장" (vaultRepository.write, then show saved feedback). Warn-free
   simple approach: save is explicit; also save when back is pressed.
3. Wire into MainActivity Home: Home now shows FileListScreen; keep the Spike
   entry reachable. Loading/error states: simple Text placeholders are fine.
All IO on Dispatchers.IO via viewModelScope or rememberCoroutineScope. No new
dependencies. Compile-safe code only — reviewer compiles after you finish.
`

phase('P0-1 Vault')
const p01 = await agent(P01_PROMPT, {
  label: 'p0-1:vault',
  sandbox: 'workspace-write',
  effort: 'high',
  schema: RESULT_SCHEMA,
  key: 'p0-1-vault',
})
log(`P0-1 done: ${p01 ? p01.summary : 'skipped'}`)

phase('P0-2 Shell')
const p02 = await agent(P02_PROMPT, {
  label: 'p0-2:shell',
  sandbox: 'workspace-write',
  effort: 'high',
  schema: RESULT_SCHEMA,
  key: 'p0-2-shell',
})

return { p01, p02 }
