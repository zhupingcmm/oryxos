import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'OryxOS',
  titleTemplate: ':title — OryxOS',
  description:
    'Enterprise Agent OS written in Java on Spring Boot. A private, auditable runtime for running multiple business AI Agents on your own infrastructure.',
  base: '/',
  cleanUrls: true,
  appearance: 'force-light',

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.googleapis.com' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' }],
    [
      'link',
      {
        rel: 'stylesheet',
        href: 'https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@700&display=swap',
      },
    ],
    ['meta', { name: 'author', content: 'OryxOS' }],
    [
      'meta',
      {
        name: 'keywords',
        content:
          'OryxOS, Agent OS, Java, Spring Boot, Spring AI, ReAct, multi-agent, enterprise agent, on-prem agent, MCP, audit',
      },
    ],
    ['meta', { name: 'robots', content: 'index, follow' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'OryxOS' }],
    ['meta', { property: 'og:title', content: 'OryxOS — Enterprise Agent OS on Spring Boot' }],
    [
      'meta',
      {
        property: 'og:description',
        content:
          'Java-native Agent OS runtime. Private deployment, audit-grade, zero-cloud-lock-in.',
      },
    ],
    ['meta', { property: 'og:url', content: 'https://oryxos.dev' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'OryxOS — Enterprise Agent OS on Spring Boot' }],
    [
      'meta',
      {
        name: 'twitter:description',
        content:
          'Java-native Agent OS runtime. Private deployment, audit-grade, zero-cloud-lock-in.',
      },
    ],
    ['link', { rel: 'canonical', href: 'https://oryxos.dev' }],
  ],

  locales: {
    root: {
      label: 'English',
      lang: 'en-US',
      themeConfig: {
        nav: [
          { text: 'Home', link: '/' },
          { text: 'Docs', link: '/docs/what' },
          { text: 'GitHub', link: 'https://github.com/oryxos/oryxos' },
        ],
        sidebar: {
          '/docs/': [
            {
              text: 'Getting Started',
              items: [
                { text: 'What is OryxOS', link: '/docs/what' },
                { text: 'For Engineers', link: '/docs/for-engineer' },
                { text: 'For Agents', link: '/docs/for-agent' },
                { text: 'Quick Start', link: '/docs/quick-start' },
              ],
            },
            {
              text: 'Deep Dives',
              items: [
                { text: 'Overview', link: '/docs/overview' },
                { text: 'Architecture', link: '/docs/architecture' },
                { text: 'Features', link: '/docs/features' },
                { text: 'Scenarios', link: '/docs/scenarios' },
              ],
            },
            {
              text: 'SDK',
              items: [
                { text: 'Java SDK', link: '/docs/sdk/java' },
                { text: 'Spring Boot Starter', link: '/docs/sdk/spring-boot-starter' },
                { text: 'CLI', link: '/docs/sdk/cli' },
              ],
            },
            {
              text: 'Integrations',
              items: [
                { text: 'Spring AI', link: '/docs/integrations/spring-ai' },
                { text: 'MCP Server', link: '/docs/integrations/mcp' },
                { text: 'LangChain4j', link: '/docs/integrations/langchain4j' },
              ],
            },
            {
              text: 'Reference',
              items: [
                { text: 'FAQ', link: '/docs/faq' },
                { text: 'Roadmap', link: '/docs/roadmap' },
                { text: 'Constitution', link: '/docs/constitution' },
              ],
            },
          ],
        },
      },
    },
    zh: {
      label: '中文',
      lang: 'zh-CN',
      link: '/zh/',
      themeConfig: {
        nav: [
          { text: '首页', link: '/zh/' },
          { text: '文档', link: '/zh/docs/what' },
          { text: 'GitHub', link: 'https://github.com/oryxos/oryxos' },
        ],
        sidebar: {
          '/zh/docs/': [
            {
              text: '快速入门',
              items: [
                { text: 'OryxOS 是什么', link: '/zh/docs/what' },
                { text: '给工程师', link: '/zh/docs/for-engineer' },
                { text: '给 Agent', link: '/zh/docs/for-agent' },
                { text: '快速开始', link: '/zh/docs/quick-start' },
              ],
            },
            {
              text: '深入了解',
              items: [
                { text: '总览', link: '/zh/docs/overview' },
                { text: '系统架构', link: '/zh/docs/architecture' },
                { text: '功能特性', link: '/zh/docs/features' },
                { text: '使用场景', link: '/zh/docs/scenarios' },
              ],
            },
            {
              text: 'SDK',
              items: [
                { text: 'Java SDK', link: '/zh/docs/sdk/java' },
                { text: 'Spring Boot Starter', link: '/zh/docs/sdk/spring-boot-starter' },
                { text: 'CLI', link: '/zh/docs/sdk/cli' },
              ],
            },
            {
              text: '集成',
              items: [
                { text: 'Spring AI', link: '/zh/docs/integrations/spring-ai' },
                { text: 'MCP Server', link: '/zh/docs/integrations/mcp' },
                { text: 'LangChain4j', link: '/zh/docs/integrations/langchain4j' },
              ],
            },
            {
              text: '参考',
              items: [
                { text: '常见问题', link: '/zh/docs/faq' },
                { text: '路线图', link: '/zh/docs/roadmap' },
                { text: '七条原则', link: '/zh/docs/constitution' },
              ],
            },
          ],
        },
      },
    },
  },

  themeConfig: {
    siteTitle: false,
    logo: '/logo-light.svg',
    socialLinks: [
      { icon: 'github', link: 'https://github.com/oryxos/oryxos' },
    ],
  },

  sitemap: {
    hostname: 'https://oryxos.dev',
  },
})