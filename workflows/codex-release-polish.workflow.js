export const meta = {
  name: 'mdvault-release-polish',
  description: 'Gallery-style immersive toggle implementation + 3-lens release-readiness review',
  phases: [
    { title: 'Implement', detail: 'media viewer chrome toggle (Codex, write)' },
    { title: 'Review', detail: '3 parallel read-only reviewers' },
    { title: 'Synthesize', detail: 'merge into prioritized fix list' },
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

const FINDINGS_SCHEMA = {
  type: 'object',
  required: ['findings'],
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        required: ['priority', 'issue', 'fix', 'files'],
        properties: {
          priority: { type: 'string', enum: ['P0', 'P1', 'P2'] },
          issue: { type: 'string' },
          fix: { type: 'string' },
          files: { type: 'array', items: { type: 'string' } },
        },
      },
    },
  },
}

const COMMON = `
## Repository context
"mdvault" Android repo (cwd). Read CLAUDE.md and docs/HANDOFF.md first (rules +
current state + landmine list). Kotlin + Compose M3, manual DI, no new deps.
The product: offline document viewer opened from file managers
(md/txt/docx/html/pdf/images) + optional folder notes. User demands
release-grade polish and non-developer-facing simplicity (Korean UI).
Do NOT run git. Do NOT create GitHub issues.
`

const IMPLEMENT_PROMPT = `${COMMON}
IMPORTANT: Gradle CANNOT run in your sandbox — write compile-safe code,
reviewer compiles after.

## Task: gallery-style chrome toggle for the media viewer (image + PDF)

Reference behavior = Samsung Gallery: single tap anywhere toggles ALL chrome
together — app top bar (back arrow + filename) AND system status bar AND
system navigation bar. Tap again brings everything back. Content stays
full-bleed black behind.

Spec:
1. Applies to the unified media viewer chrome used by image and PDF screens
   (see preview/ package — Codex previously unified these; find the current
   chrome composable).
2. Initial state when opening: chrome VISIBLE.
3. Single tap toggles visibility. Must NOT conflict with existing gestures:
   pinch zoom / pan (transformable) on both viewers, LazyColumn scroll on PDF.
   Use pointerInput(Unit) { detectTapGestures(onTap = ...) } layered so tap
   fires only for genuine taps (drag/pinch/scroll must not toggle).
4. System bars: hide/show via WindowCompat / WindowInsetsControllerCompat
   (systemBars(), BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE so a swipe from edge
   temporarily reveals). Get the Activity window from LocalContext/LocalView.
5. CRITICAL cleanup: when the media screen leaves composition (back to home/
   list) OR the screen is disposed for any reason, system bars MUST be
   restored (DisposableEffect onDispose). Non-media screens must never be
   left with hidden bars.
6. App top bar shows/hides with a short fade or slide animation
   (AnimatedVisibility) — no layout jump for the content (content is always
   full-screen behind chrome).
7. Status text color/scrim: keep the top bar readable over content
   (existing dark bar style is fine).
Return files touched + risks. Compile-safe only.
`

const REVIEW_LENSES = [
  {
    key: 'viewer-ux',
    prompt: `${COMMON}READ-ONLY review. Lens: per-type viewer UX completeness vs
a release-grade document viewer. For each supported type (md, txt, docx, html,
pdf, image) inspect the actual code paths and judge against what a polished
viewer app ships: loading states, huge-file behavior, scroll position feel,
text readability defaults, docx conversion feedback, pdf page quality/perf,
image edge cases (tiny images, panoramas, GIF animation — PdfRenderer/coil-free
constraints noted), reader typography. List CONCRETE gaps with priority
(P0=embarrassing in release, P1=noticeable, P2=nice-to-have), each with a
specific fix and files.`,
  },
  {
    key: 'robustness',
    prompt: `${COMMON}READ-ONLY review. Lens: robustness. Inspect code for:
configuration change / rotation (state loss? viewerUri/screen state in
MdvaultApp is remember{} not rememberSaveable — verify), process death
restoration, permission revocation mid-use, corrupted/truncated files per
type, very large files (OOM paths: readBytes() on huge md/html/images,
PdfRenderer bitmaps), empty files, concurrent vault mutation, SAF provider
exceptions surfacing as raw messages to users. Priority P0/P1/P2 + concrete
fix + files for each finding.`,
  },
  {
    key: 'platform',
    prompt: `${COMMON}READ-ONLY review. Lens: Android platform conventions for
a release app. Inspect AndroidManifest.xml, gradle config, resources:
app icon (currently default?), app label, predictive back / back behavior
consistency, theme (Material3 dynamic color? dark mode of non-viewer
screens), edge-to-edge/insets handling, targetSdk currency, locale (Korean
labels hardcoded — acceptable for personal app, note only if trivial),
launchMode/taskAffinity for VIEW intents (multiple documents open behavior),
missing intent mime types users would expect (csv? rtf? epub? — judge),
accessibility basics (contentDescription on tappable icons). P0/P1/P2 +
fix + files.`,
  },
]

phase('Implement')
const implPromise = agent(IMPLEMENT_PROMPT, {
  label: 'impl:chrome-toggle',
  sandbox: 'workspace-write',
  effort: 'high',
  schema: RESULT_SCHEMA,
  key: 'impl-chrome-toggle',
})

phase('Review')
const reviews = await parallel(REVIEW_LENSES.map(lens => () =>
  agent(lens.prompt, {
    label: `review:${lens.key}`,
    effort: 'high',
    schema: FINDINGS_SCHEMA,
    key: `review-${lens.key}`,
  })))

const impl = await implPromise
log(`impl done: ${impl ? impl.summary.slice(0, 80) : 'skipped'}`)

phase('Synthesize')
const allFindings = reviews.filter(Boolean).flatMap(r => r.findings)
const report = await agent(`${COMMON}
You are synthesizing a release-readiness report. Input findings (JSON):
${JSON.stringify(allFindings, null, 2)}

Dedup overlapping findings, drop anything that contradicts CLAUDE.md rules
(e.g. suggesting new libraries), re-rank pragmatically for a PERSONAL app
being polished to release quality (P0 = fix before calling it done,
P1 = next batch, P2 = backlog). Output a concise Korean markdown report:
priority-grouped list, each item 1-2 lines with file references. No fluff.`, {
  label: 'synthesize',
  effort: 'medium',
  key: 'synthesize',
})

return { impl, findingsCount: allFindings.length, report }
