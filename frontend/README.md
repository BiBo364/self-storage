# Frontend

React app for the self-storage flows, wired to the backend API (dev server proxies `/api` to
`VITE_API_PROXY_TARGET`, default `http://localhost:8080`).

| Role     | Home                  | Screens                                                                                                                                                                                                                     |
| -------- | --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Public   | `/`                   | landing, `/units` catalog, `/rental-requests/new`, login / register / verify, chat assistant widget                                                                                                                         |
| CUSTOMER | `/my-storage`         | orders + active rentals + handovers, `/my-storage/{contractId}` (extend, return, access code), `/proposals`, `/invoices` + `/payments/result` (payOS), `/appointments/new`, `/handovers/{id}`, `/support`, `/notifications` |
| FM       | `/fm/rental-requests` | requests, orders, re-propose, appointments, return requests, overdue contracts, support, storage units, staff, report, invoices, contracts + start-date overrides                                                           |
| FS       | `/staff/schedule`     | schedule, handover queue + checklist, returns + checkout, support                                                                                                                                                           |
| BOM      | `/bom/dashboard`      | revenue dashboard, facilities & FM, policies & prices, chain invoices                                                                                                                                                       |
| ADMIN    | `/admin/accounts`     | accounts, permission matrix, login history, audit log                                                                                                                                                                       |

API calls live in `src/app/services/*.ts` (DTO types next to the calls) and `src/app/http.ts` (`authed`).

## Run

```bash
npm install
npm run dev
```

The TypeScript, HTML, CSS, JSON, Tailwind, and Emmet language servers are project dependencies. The Neovim setup prefers `node_modules/.bin` for this project and falls back to its configured global/Mason binary for other projects.

`npm run build` runs the TypeScript check and Vite production build.

## Docker

```bash
docker compose up --build
```

Open `http://localhost:5173`. The Compose setup mounts the source directory and keeps `node_modules` in a named volume, so Vite hot reload remains available without sharing host dependencies with the container.

Stop the container with:

```bash
docker compose down
```

## Formatting

```bash
npm run format
npm run format:check
```

Prettier formats the frontend source and checks formatting in CI or before review. The shared rules live in `.prettierrc.json` and `.editorconfig`.

## Styling

Tailwind CSS v4 through `@tailwindcss/vite`. There is no `tailwind.config.*`; the theme lives in `src/tailwind.css`.

- `src/tailwind.css` — Tailwind entrypoint, layer order, and the `@theme` bridge that maps Tailwind tokens onto the runtime CSS variables.
- `src/styles.css` — runtime design tokens (`:root`, `[data-theme="light"]`) plus the base reset. Imported into the `base` layer.
- `src/app/layout.ts` — `PAGE_CONTAINER`, the shared page width. Use it instead of Tailwind's `container` class, which adds its own breakpoint max-widths.
- `DESIGN.md` — the visual source of truth. Read it before adding or changing UI.

Components are styled with Tailwind utilities directly in the markup. Wall-to-wall custom CSS is gone; only tokens, the reset, native `<select>` color-scheme handling, scrollbar/selection styles, and the two shared keyframes remain as CSS.

## Boundary

Every screen reads and writes through the backend API; business rules (allowed actions, steps, prices) come from the
API responses. `src/mocks/**` is no longer imported by any screen.
