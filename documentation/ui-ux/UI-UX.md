# TAXPILOT AI — UI/UX Specification

## Brand tokens
| Token | Hex | Use |
|---|---|---|
| primary | #173F35 | Sidebar, primary buttons, headings |
| emerald / success | #26846B | Accents, success states, active nav |
| light-green | #E8F3EE | Selected rows, soft badges |
| background | #F7F8F7 | App background |
| card | #FFFFFF | Surfaces |
| text | #202A27 | Body |
| text-secondary | #69756F | Labels, meta |
| warning | #E9A23B | Pending / attention |
| error | #D9534F | Errors, destructive |

Font: Inter (system fallback). Base 14 px, 4-px spacing grid, 8-px radius on cards and inputs.

## Shell
- Left sidebar (collapsible below 1024 px into a drawer). **Only implemented modules appear.**
  Phase 1: Dashboard · Clients · Documents · Audit Log · Settings (Users, Roles).
  Items are also hidden when the user lacks the module's read permission.
- Top bar: page title, user menu (change password, sign out).

## Patterns
- **Tables:** search box, filters, server-side pagination, CSV export where allowed, row click opens the record.
- **Forms:** React Hook Form + Zod, inline field errors, server field errors mapped onto the same fields.
- **States:** skeleton while loading, empty state with a next action, error panel with retry.
- **Status badges:** grey (draft), amber (pending/review), green (active/approved), red (rejected/closed/infected).
- **Consequential actions** (activate, close, approve, reject, deactivate) use a confirmation dialog stating the effect.
- **Sensitive values** (PAN, bank numbers) are masked by default.
- Keyboard: every control reachable by Tab, visible focus ring, dialogs trap focus and close on Esc.

## Phase 1 screens
1. Login · forced change password
2. Dashboard — metric cards, clients-by-status chart, documents-by-status chart, recent activity
3. Clients list — search, status/entity filters, export
4. New client — onboarding form
5. Client profile — tabs: Overview (status actions, assignments, history), Registrations, Contacts & Addresses, Bank Accounts, Documents, Audit Trail
6. Documents — list, upload dialog, detail with review actions, download
7. Audit Log — filters
8. Settings → Users, Roles
