# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-07-18
- Primary product surfaces: Login, desktop SQL tuning conversation, model operations console, skill prompt console, deterministic rule catalog.
- Evidence reviewed: `frontend/src/pages/App.tsx`, `frontend/src/components/AppShell.tsx`, `frontend/src/components/SqlInputPanel.tsx`, `frontend/src/components/ConversationStream.tsx`, `frontend/src/components/TuningAdviceMessage.tsx`, `frontend/src/pages/*AdminPage.tsx`, `frontend/src/styles/global.css`, `README.md`, user-approved sqlTuner overhaul plan, current production workspace screenshot and the supplied 2940x1846 Codex desktop conversation reference on 2026-07-18.

## Brand
- Personality: focused, technical, quiet, and decisive under production pressure.
- Trust signals: clear task stage, concise advice tied to the supplied input, explicit uncertainty only when it changes the next action, and no unverified model text.
- Avoid: marketing homepages, decorative gradients, oversized hero typography, fake controls, dashboard-card clutter, evidence IDs in routine reading, unverified certainty, and redundant report panels.

## Product goals
- Goals: let engineers paste an OceanBase SQL or a full diagnostic-report text block, attach plan screenshots when useful, and receive a compact, actionable tuning answer in the same conversation.
- Non-goals: connect to business databases, auto-apply DDL, support generic database engines, import DOC/DOCX/PDF report files, expose raw model tokens before validation, support tablet/mobile layouts, or hide uncertainty behind confident prose.
- Success signals: the primary screen has one readable conversation column, no hidden operational report workflow is required, users can see a task progressing, and the final answer surfaces only the strongest actionable diagnoses, one rewrite/index direction when allowed, and the next required input when not.

## Personas and jobs
- Primary personas: database engineer, backend engineer, operations owner, admin maintaining model/skill configuration.
- User jobs: paste a SQL or a complete patrol-report text block, attach execution-plan/monitoring screenshots, track a validation-safe analysis in real time, review the concise recommendation, revisit historical conversations, and operate model and skill settings.
- Key contexts of use: incident triage, slow query review, pre-release SQL review, post-migration OceanBase tuning.

## Information architecture
- Primary navigation: a dark desktop conversation rail with new tuning, real session search, recent conversations and account controls; administrator destinations live once in the top command bar and are not duplicated in the rail.
- Core routes/screens: `/login`, `/chat`, `/admin/model`, `/admin/skills`, `/admin/rules`.
- Content hierarchy: left conversation list, one centered conversation column, compact bottom composer, inline assistant answer. There is no right diagnostic dossier, detail drawer or separate task-detail route in the product reading path; legacy `/tasks/:taskId` URLs redirect to `/chat`.

## Design principles
- Principle 1: One linear reading path. A user question, a trustworthy in-progress state, and a concise answer must fit the center column without switching context.
- Principle 2: Validation before reveal. Live status may stream, but raw model output is never shown before strict validation succeeds.
- Principle 3: Input should accept the form people already have. Full patrol-report text is parsed server-side; an attachment is a compact screenshot chip, not a large evidence form.
- Principle 4: Disclosure earns its place. Evidence IDs, raw artifacts, exhaustive preconditions and duplicate risk text are hidden from the routine chat response.
- Tradeoffs: structured detail remains stored in task APIs for auditability, but no separate audit screen competes with the conversational reading path.

## Visual language
- Color: charcoal conversation rail and workspace by default, warm graphite message surfaces, cobalt for the one primary send action, amber/red only for uncertainty and failure, green only for completed health/pass states. The light theme remains available.
- Typography: IBM Plex Sans style system stack for UI, JetBrains Mono style stack for pasted SQL/report text and rewritten SQL.
- Spacing/layout rhythm: the reference image is Retina 2x and therefore represents a 1470x923 CSS viewport. Its fixed measurements are the implementation authority: 276px conversation rail, 48px top bar, 760px maximum reading/composer column, 16px composer bottom offset and an approximately 98px empty composer. Use a 4px spacing grid and 8-16px internal spacing.
- Shape/radius/elevation: ordinary controls use 6-8px radius; the Codex-specific message composer uses an 18px radius and user bubbles use 14px. Borders are quiet hairlines and elevation is very low. Assistant output is unframed content rather than a card.
- Motion: short section reveals after a validated task reaches `DONE`; respect reduced motion and immediately reveal all content in that mode.
- Imagery/iconography: lucide icons for commands, status, attachments and copy; no decorative illustration, visual dashboard ornament or emoji.

## Components
- Existing components to reuse: authentication API shape, conversation/task APIs, theme hook, admin pages, task SSE/polling behavior, server-side pasted-report extraction and image validation.
- New/changed components: Codex-proportioned desktop conversation rail, compact auto-growing SQL/report message composer, screenshot attachment tray, unframed chat task-progress message, validated tuning-advice message, searchable session list and compact admin consoles.
- Removed from the workspace: context-completeness meter, schema/index/EXPLAIN/runtime/semantic text fields, allowed-action checkboxes, the "补充证据" disclosure, right report inspector, result tabs and routine artifact/evidence cards.
- Variants and states: queued/running/terminal task states, standard/deep analysis, `ADVICE`/`NEEDS_INPUT`, empty/error/loading/disabled/offline-ish retry states.
- Token/component ownership: global CSS owns tokens and shared layout primitives; React components own state and semantics.

## Accessibility
- Target standard: practical WCAG 2.1 AA for contrast, focus visibility, names and keyboard access.
- Keyboard/focus behavior: route navigation, session search, form controls and icon actions must be reachable and visibly focused.
- Contrast/readability: neutral backgrounds with strong text contrast; warnings never rely on color alone.
- Screen-reader semantics: status text, labels and icon-only button labels are explicit.
- Reduced motion and sensory considerations: spinners and transitions are disabled or minimized with `prefers-reduced-motion`.

## Responsive behavior
- Supported breakpoints/devices: desktop browsers at 1366, 1440 and 1920 widths. Tablet and mobile are explicitly unsupported.
- Layout adaptations: the persistent 276px left rail and single centered 760px conversation column never collapse or split into a report panel. Viewports below 1366px retain the desktop canvas with horizontal scrolling instead of responsive rearrangement.
- Touch/hover differences: desktop pointer and keyboard behavior only; no touch-specific interaction path is maintained.

## Interaction states
- Loading: status is an assistant message with the current task stage; it does not reveal unvalidated tokens.
- Empty: the workspace centers the compact composer and asks for a SQL or full diagnostic-report text block, without marketing copy.
- Error: inline assistant message states that the task failed and keeps the submitted text available in the conversation.
- Success: once strict validation is complete, the assistant answer reveals a direct conclusion followed by a short evidence-led narrative. It may then show one validated rewrite or index direction, with a validation step only when that changes the engineer's next action. Legacy diagnosis lists remain a fallback for historical tasks only.
- Disabled: disabled controls retain accessible labels and the send action is disabled only while the composer is empty or submission is in progress.
- Offline/slow network, if applicable: the in-progress message remains truthful; SSE reconnects and task GET remains the recovery source.

## Content voice
- Tone: direct, technical Chinese. The assistant leads with the recommendation rather than process terminology.
- Terminology: OceanBase MySQL, OceanBase Oracle, SQL, report, diagnosis, rewrite, index direction, next step, queue.
- Microcopy rules: use plain imperative suggestions; no evidence-ID prose, no repeated confidence labels, no promise of automatic speedups. State a missing input only when it changes the next action.
- Input evidence rules: the report sample document is only a transport example; product input is pasted SQL/report text plus pasted, dropped or selected PNG/JPEG/WebP images. Historical root-cause/advice text is an untrusted claim; screenshot facts come from the dedicated vision pass at LOW trust; an image never counts as a complete text EXPLAIN and never unlocks deterministic index DDL by itself.

## Implementation constraints
- Framework/styling system: React + Vite, `react-router-dom`, selected Radix Tooltip primitive and lucide icons. The chat composer is a native, accessible textarea rather than a code-editor workbench.
- Design-token constraints: theme variables in CSS; ordinary radius 6-8px; no decorative gradients/orbs.
- Performance constraints: frontend is static; model/API work stays backend; long pasted SQL/report text is bounded by the API contract and displayed in a scrollable monospace message; input images are bounded, persisted outside task JSON, and processed by a bounded vision call before the text analysis.
- Compatibility constraints: preserve existing API response wrapper and legacy result fields while reading new structured fields when present; unsafe requests load `/api/auth/csrf` and send the returned CSRF header token; model gateways use OpenAI-compatible `/chat/completions` and optional `/models`, while manual model IDs remain available for gateways that do not expose catalogs; readiness telemetry comes from `/api/health/ready` (`status`, `mysql`, `queued`, `running`) and model runtime telemetry from `/api/admin/health` (`provider`, `model`, `mockState`, `apiKeyConfigured`).
- Test/screenshot expectations: Vitest + Testing Library for composer attachment handling, concise validated advice rendering and task-stage behavior; Playwright desktop projects at 1366, 1440 and 1920 for login, chat composer, conversation result, fixed rail/history and admin flows. Visual artifacts live under `.omx/artifacts/visual-ralph/codex-chat/`. No tablet/mobile projects or responsive acceptance criteria.

## Open questions
- None for this implementation slice.
