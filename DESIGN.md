# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-07-17
- Primary product surfaces: Login, SQL diagnostic workspace, task report, model operations console, skill prompt console, deterministic rule catalog.
- Evidence reviewed: `frontend/src/pages/App.tsx`, `frontend/src/components/AppShell.tsx`, `frontend/src/components/SqlInputPanel.tsx`, `frontend/src/components/ResultTabs.tsx`, `frontend/src/pages/*AdminPage.tsx`, `frontend/src/styles/global.css`, `README.md`, user-approved sqlTuner overhaul plan.

## Brand
- Personality: precise, operational, evidence-first, calm under production pressure.
- Trust signals: context completeness, queue/status visibility, cited evidence, explicit missing information, model/review health, deterministic rules separated from model advice.
- Avoid: marketing homepages, decorative gradients, oversized hero typography, fake controls, one-note purple/blue dashboards, unverified certainty.

## Product goals
- Goals: help engineers convert OceanBase slow SQL evidence into reviewable diagnoses, rewrite candidates, index directions and validation plans.
- Non-goals: connect to business databases, auto-apply DDL, support generic database engines, import DOC/DOCX/PDF report files, hide uncertainty behind confident prose.
- Success signals: users can see why advice was given, what evidence is missing, what can be verified next, and whether a task is queued/running/done.

## Personas and jobs
- Primary personas: database engineer, backend engineer, operations owner, admin maintaining model/skill configuration.
- User jobs: paste a SQL or a complete patrol-report text block, attach execution-plan/monitoring screenshots, supplement schema/index/text EXPLAIN/context, track task progress, inspect evidence and risks, compare rewrite/index candidates, recover historical sessions, operate model and skill settings.
- Key contexts of use: incident triage, slow query review, pre-release SQL review, post-migration OceanBase tuning.

## Information architecture
- Primary navigation: left command rail with new tuning, searchable sessions, admin entry points and account controls.
- Core routes/screens: `/login`, `/chat`, `/tasks/:taskId`, `/admin/model`, `/admin/skills`, `/admin/rules`.
- Content hierarchy: left session list, center SQL/context composer and conversation timeline, right diagnostic dossier with evidence, diagnoses, rewrites, indexes, validation, warnings and raw artifacts.

## Design principles
- Principle 1: Evidence before advice; every recommendation area must expose supporting facts or missing input.
- Principle 2: Operations density without clutter; repeat actions should be visible, compact and keyboard reachable.
- Tradeoffs: prefer explicit structured panels over conversational flourish; preserve legacy result display while making the new structured contract primary.

## Visual language
- Color: graphite and porcelain neutrals, cobalt primary actions, amber/red reserved for warning and risk, green only for healthy/pass states.
- Typography: IBM Plex Sans style system stack for UI, JetBrains Mono style stack for SQL and artifacts.
- Spacing/layout rhythm: 4px token grid, compact 8-16px internal spacing, constrained work areas with predictable columns.
- Shape/radius/elevation: 6-8px radius, hairline borders, low shadows only for overlays and sticky panels.
- Motion: short state transitions; respect reduced motion.
- Imagery/iconography: lucide icons for commands and status; no decorative illustrations.

## Components
- Existing components to reuse: authentication API shape, conversation/task APIs, theme hook, admin pages and task polling behavior.
- New/changed components: router shell, mobile navigation dialog, CodeMirror SQL/report editor, pasted-report field extraction, image evidence tray with paste/drop/upload, context completeness meter, structured diagnostic dossier, searchable session list, compact admin consoles.
- Variants and states: queued/running/terminal task states, standard/deep analysis, `ADVICE`/`NEEDS_INPUT`, empty/error/loading/disabled/offline-ish retry states.
- Token/component ownership: global CSS owns tokens and shared layout primitives; React components own state and semantics.

## Accessibility
- Target standard: practical WCAG 2.1 AA for contrast, focus visibility, names and keyboard access.
- Keyboard/focus behavior: route navigation, tabs, dialogs, search, form controls and icon actions must be reachable and visibly focused.
- Contrast/readability: neutral backgrounds with strong text contrast; warnings never rely on color alone.
- Screen-reader semantics: dialogs, tabs, status text, labels and icon-only button labels are explicit.
- Reduced motion and sensory considerations: spinners and transitions are disabled or minimized with `prefers-reduced-motion`.

## Responsive behavior
- Supported breakpoints/devices: desktop 1440, laptop/tablet 1024/768, mobile 390.
- Layout adaptations: desktop uses persistent left/center/right workbench; tablet collapses report width; mobile uses a top bar, dialog navigation and full-screen report sheet.
- Touch/hover differences: controls retain visible labels or accessible names; no workflow depends only on hover.

## Interaction states
- Loading: skeleton/status text for user bootstrap, messages, tasks and admin config.
- Empty: workspace explains the next concrete input without marketing copy.
- Error: inline error blocks include actionable retry context.
- Success: task and model health use compact pass badges.
- Disabled: disabled buttons keep labels and reason through surrounding status.
- Offline/slow network, if applicable: polling failures surface as recoverable errors; task GET remains the recovery source.

## Content voice
- Tone: direct, technical Chinese with concise English labels where they match operator vocabulary.
- Terminology: OceanBase MySQL, OceanBase Oracle, evidence, diagnosis, rewrite, index candidate, validation, risk, queue.
- Microcopy rules: do not promise automatic speedups; distinguish candidate advice, verified evidence and missing input.
- Input evidence rules: the report sample document is only a transport example; product input is pasted SQL/report text plus pasted, dropped or selected PNG/JPEG/WebP images. Historical root-cause/advice text is an untrusted claim; screenshot facts come from the dedicated vision pass at LOW trust; an image never counts as a complete text EXPLAIN and never unlocks deterministic index DDL by itself.

## Implementation constraints
- Framework/styling system: React + Vite, `react-router-dom`, CodeMirror 6, selected Radix Dialog/Tabs/Tooltip primitives, lucide icons.
- Design-token constraints: theme variables in CSS; ordinary radius 6-8px; no decorative gradients/orbs.
- Performance constraints: frontend is static; model/API work stays backend; long SQL/context fields are bounded by API contract and rendered in scrollable code panels; input images are bounded, persisted outside task JSON, and processed by a bounded vision call before the text analysis.
- Compatibility constraints: preserve existing API response wrapper and legacy result fields while reading new structured fields when present; unsafe requests load `/api/auth/csrf` and send the returned CSRF header token; readiness telemetry comes from `/api/health/ready` (`status`, `mysql`, `queued`, `running`) and model runtime telemetry from `/api/admin/health` (`provider`, `model`, `mockState`, `apiKeyConfigured`).
- Test/screenshot expectations: Vitest + Testing Library for component behavior; Playwright config and smoke specs for login/workspace/admin responsive flows.

## Open questions
- None for this implementation slice.
