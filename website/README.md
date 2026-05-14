# website/

Public marketing site for AccessFlow. Static HTML / CSS / vanilla JavaScript — no build
step, no Node, no package.json. Deployable to any static host (GitHub Pages, S3, Netlify,
Cloudflare Pages, plain Nginx).

All copy is sourced from the application itself and the `docs/` chapters; no claims are
invented here. When the underlying code or docs change, the website must be updated in
the same change set — see the **"Do not let `website/` drift"** rule in
[`CLAUDE.md`](../CLAUDE.md).

---

## Local preview

```bash
cd website
python3 -m http.server 4173
```

Then open <http://localhost:4173>.

Any HTTP server that can serve a directory works equally well (`npx http-server`,
`caddy file-server`, etc.).

---

## Content-source map

When you change one of the source files on the left, check the corresponding section on
the right.

| Source of truth | Website section |
|---|---|
| [`README.md`](../README.md) (pitch, quick start) | Hero, Install tabs, terminal preview |
| [`docs/02-architecture.md`](../docs/02-architecture.md) | Architecture diagram |
| [`backend/pom.xml`](../backend/pom.xml), [`frontend/package.json`](../frontend/package.json) | Architecture callouts, Install requirements panel |
| [`docs/07-security.md`](../docs/07-security.md) | "Workforce-ready auth" feature tile |
| [`docs/08-notifications.md`](../docs/08-notifications.md) | "Configurable review workflows" feature tile |
| [`docs/12-roadmap.md`](../docs/12-roadmap.md) | Roadmap track |
| [`docs/`](../docs/) chapter filenames + H1s | Docs grid cards |
| [`CLAUDE.md`](../CLAUDE.md) (supported db list, env-var defaults) | Hero meta strip, Features tags |
| [`charts/accessflow/`](../charts/accessflow/) | Helm install tab |
| [`README.md`](../README.md) quick start + [`docs/04-api-spec.md`](../docs/04-api-spec.md), [`docs/05-backend.md`](../docs/05-backend.md), [`docs/07-security.md`](../docs/07-security.md), [`docs/08-notifications.md`](../docs/08-notifications.md), [`docs/09-deployment.md`](../docs/09-deployment.md) | [`docs/index.html`](docs/index.html) — user documentation page (run + configure) |
| Existing on-page copy (hero, features, supported DBs, license) | SEO meta block (canonical, OG, Twitter, JSON-LD) in both [`index.html`](index.html) and [`docs/index.html`](docs/index.html) |

---

## File layout

```
website/
├── index.html      # Marketing site — single-page, all sections inline
├── styles.css      # Hi-tech dark theme — Geist + Geist Mono, OKLCH accents
├── app.js          # Vanilla JS: install tabs, copy buttons, how-it-works stepper
├── favicon.svg     # Brand mark (shared with frontend/public/favicon.svg)
├── og-image.png    # 1200×630 social-share image (Open Graph / Twitter Card)
├── robots.txt      # Crawler directives + sitemap pointer
├── sitemap.xml     # XML sitemap (homepage + docs page)
├── docs/
│   └── index.html  # Public user documentation — run + configure (sidebar TOC)
└── README.md       # this file
```

The marketing site at the root targets visitors evaluating AccessFlow. The
`docs/index.html` page targets operators and admins who need step-by-step instructions
for running and configuring a deployment. Both reuse `styles.css` and `app.js`.

No frameworks, no bundlers, no CDN runtime. The Geist + Geist Mono fonts load from
Google Fonts; everything else is local.

---

## SEO

Both HTML pages ship a full SEO meta block — canonical URL, Open Graph, Twitter Card,
`theme-color`, and a single JSON-LD `@graph` (`SoftwareApplication` + `Organization` +
`WebSite` on the homepage; `TechArticle` + `BreadcrumbList` on the docs page). The
`og:image` / `twitter:image` is `og-image.png` (1200×630, PNG, ~143 KB), regenerable from
a one-off HTML template via headless Chrome — re-create the template and run

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless --disable-gpu --hide-scrollbars --window-size=1200,630 \
  --screenshot=og-image.png http://localhost:4173/<template>.html
```

(then delete the template). All canonical / `og:url` values are hard-coded to
`https://accessflow.bablsoft.com` — if the deployed origin ever changes, search both
HTML files plus `sitemap.xml` and `robots.txt` and update in lockstep.

`robots.txt` allows all crawlers and points to `sitemap.xml`. `sitemap.xml` lists the
two HTML pages (`/` and `/docs/`).

---

## Deployment

Out of scope for this folder — the repo's existing `gh-pages` branch is reserved for the
Helm chart index. When you're ready to publish:

- **GitHub Pages** — add a workflow that uploads `website/` to a separate Pages
  environment, or to a path that does not collide with `index.yaml`.
- **Netlify / Vercel / Cloudflare Pages** — point a site at this folder, no build command.
- **S3 + CloudFront** — sync the folder, set `index.html` as the index document.
- **Nginx / Caddy** — serve the directory directly.

Whichever target you pick, the only runtime requirement is a static-file server.
