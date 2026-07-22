# OryxOS Website

VitePress site for [OryxOS](https://github.com/oryxos/oryxos). Bilingual (English + 中文), mirrors the structure of [mq9.robustmq.com](https://mq9.robustmq.com).

## Develop

```bash
npm install
npm run docs:dev          # http://localhost:5173
```

## Build

```bash
npm run docs:build        # output: .vitepress/dist/
npm run docs:preview      # serve dist locally
```

## Structure

```
.
├── docs/                  # English content (VitePress default locale)
├── zh/                    # Chinese content (locale = zh)
├── .vitepress/
│   ├── config.mts         # nav + sidebar + SEO + locales
│   └── theme/
│       ├── index.ts       # register <Home /> global component
│       ├── custom.css     # small global tweaks
│       └── components/
│           ├── Home.vue       # 7-section homepage (Hero → CTA)
│           ├── Layout.vue     # adds Giscus comments on doc pages
│           └── GiscusComment.vue
└── public/                # static assets (favicon, logos, placeholder diagrams)
```

The homepage (`docs/index.md`) is just one line: `<Home />`. The full visual is in `theme/components/Home.vue`.

## Add a new page

1. Create the Markdown file under `docs/` (and `zh/` if bilingual).
2. Add a sidebar entry in `.vitepress/config.mts` under both `locales.root.themeConfig.sidebar` and `locales.zh.themeConfig.sidebar`.
3. The dev server hot-reloads.

## Placeholder assets

`public/favicon.svg`, `public/logo-*.svg`, and `public/flow.svg` are placeholder geometry. Replace with final brand assets before public launch.