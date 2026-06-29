# Design

## Source of Truth

- Status: Active draft
- Last refreshed: 2026-06-26
- Primary product surfaces: Login, SQL tuning chat workspace, task detail, skill management, model config, rule management.
- Evidence reviewed: user reference screenshot of RAG chat UI, local shadcn MCP component/block inventory, SQL tuning requirements.

## Brand

- Personality: calm, technical, trustworthy, operations-focused.
- Trust signals: visible Harness progress, evidence-backed findings, explicit confidence and risk warnings.
- Avoid: marketing hero pages, decorative gradients, one-file frontend pages, hidden model behavior.

## Product Goals

- Help engineers process slow SQL requests faster.
- Preserve human review by returning advice, not automatically changing databases.
- Make model reasoning auditable through rule hits and Harness artifacts.

## Information Architecture

- Primary navigation: left sidebar with conversations and admin links.
- Core routes: `/login`, `/chat`, `/tasks/:taskId`, `/admin/skills`, `/admin/model`, `/admin/rules`.
- Content hierarchy: conversation stream first, persistent composer second, structured tuning evidence and task detail third.
- Interaction model: `/chat` is a conversational workspace. Users can send SQL or natural-language follow-ups repeatedly in the same conversation. A tuning task/report is attached to each assistant answer instead of replacing the composer.

## Visual Language

- Color: light neutral base, blue accent for primary actions, amber/red only for warnings and risks.
- Typography: compact enterprise dashboard text, no oversized marketing sections.
- Radius/elevation: 8px or less, restrained shadows only for input surface and overlays.
- Iconography: lucide icons for compact commands and status markers.

## Components

- Reuse shadcn-style primitives: sidebar, tabs, textarea, sheet, table, progress, dialog, tooltip, badge, toast.
- New product components: conversation message stream, sticky SQL/natural-language composer, compact assistant answer card, SQL editor, context drawer, Harness timeline, finding card, index suggestion card, risk warning panel.
- States required: loading, empty, error, success, disabled, pending assistant analysis, SSE reconnect notice.

## Accessibility

- All controls need keyboard focus states.
- Icon-only buttons need accessible labels and tooltips.
- Error and status updates should be visible in text, not color-only.

## Responsive Behavior

- Desktop: persistent left sidebar and centered work surface.
- Tablet/mobile: sidebar collapses into sheet; result tabs remain horizontally scrollable.
- SQL input and result cards must not overlap or overflow.

## Implementation Constraints

- Frontend builds to static assets; production does not require Node.
- Backend stays Java 8 compatible.
- All model calls go through backend; frontend never sees API keys.
- Multi-turn context should be summarized and bounded before model calls; do not send unlimited conversation history.
