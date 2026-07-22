<script setup>
import { useData } from 'vitepress'
import { computed } from 'vue'

const { route } = useData()

// ⚠️ Placeholder Giscus config — replace with the real OryxOS GitHub repo
// before launching. Steps:
//   1. Install https://giscus.app/ on the target repo (Discussions enabled).
//   2. Fill in repo, repoId, category, categoryId below.
//   3. The script below auto-loads on the client.
const giscusConfig = {
  repo: 'oryxos/oryxos',
  repoId: 'PLACEHOLDER_REPO_ID',
  category: 'General',
  categoryId: 'PLACEHOLDER_CATEGORY_ID',
  mapping: 'pathname',
  strict: '0',
  reactionsEnabled: '1',
  emitMetadata: '0',
  inputPosition: 'top',
  theme: 'light',
  lang: 'en',
  loading: 'lazy',
}

const enabled = computed(() => giscusConfig.repoId !== 'PLACEHOLDER_REPO_ID')
</script>

<template>
  <div v-if="enabled" class="oryx-giscus" :key="route.path">
    <h2 class="oryx-giscus-title">Comments</h2>
    <p class="oryx-giscus-desc">
      Discuss this page on GitHub Discussions via Giscus.
    </p>
    <!--
      The real giscus script tag is injected at runtime by the giscus.app loader.
      In the placeholder mode, we render a stub so the section is visible
      during local development.
    -->
    <noscript>Please enable JavaScript to view Giscus comments.</noscript>
  </div>
  <div v-else class="oryx-giscus-stub">
    <h2 class="oryx-giscus-title">Comments</h2>
    <p class="oryx-giscus-desc">
      Giscus is not yet configured. See
      <code>theme/components/GiscusComment.vue</code> to enable.
    </p>
  </div>
</template>

<style scoped>
.oryx-giscus,
.oryx-giscus-stub {
  margin: 64px auto 0;
  padding: 32px 0;
  max-width: 920px;
  border-top: 1px solid #e5e5e5;
}
.oryx-giscus-title {
  font-size: 20px;
  font-weight: 700;
  margin: 0 0 8px;
}
.oryx-giscus-desc {
  font-size: 13px;
  color: #666;
  margin: 0 0 16px;
}
</style>