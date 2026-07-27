<script setup lang="ts">
import { FileSearch, Search } from 'lucide-vue-next'
import type { KnowledgeBaseTopicSearchResult } from '../../api/knowledgeBase'

defineProps<{
  query: string
  searching: boolean
  message: string
  summary: string
  topics: KnowledgeBaseTopicSearchResult[]
}>()

const emit = defineEmits<{
  'update:query': [value: string]
  search: []
}>()
</script>

<template>
  <section class="content-panel" aria-labelledby="knowledge-search-title">
    <div class="section-title">
      <FileSearch :size="20" aria-hidden="true" />
      <div>
        <h2 id="knowledge-search-title">检索资料</h2>
        <p>结果按知识主题整合，保留原始文件和正文位置作为依据。</p>
      </div>
    </div>

    <form class="search-form" @submit.prevent="emit('search')">
      <label class="sr-only" for="knowledge-query">检索内容</label>
      <input
        id="knowledge-query"
        :value="query"
        maxlength="300"
        placeholder="例如：门店交接班流程"
        autocomplete="off"
        @input="emit('update:query', ($event.target as HTMLInputElement).value)"
      >
      <button type="submit" :disabled="searching">
        <Search :size="17" />{{ searching ? '检索中…' : '检索' }}
      </button>
    </form>

    <p v-if="message" class="empty-copy">{{ message }}</p>
    <section v-if="summary" class="overall-summary" aria-label="综合要点">
      <strong>综合要点</strong>
      <p>{{ summary }}</p>
    </section>

    <div v-if="topics.length" class="topic-results" aria-live="polite">
      <section v-for="topic in topics" :key="topic.topicId" class="topic-result">
        <header>
          <div>
            <span>知识主题</span>
            <h3>{{ topic.topicName }}</h3>
          </div>
          <small>{{ topic.sources.length }} 个有效来源</small>
        </header>
        <p class="topic-summary">{{ topic.summary }}</p>
        <div class="source-list">
          <article
            v-for="source in topic.sources"
            :key="`${source.documentId}-${source.sourceLocator}-${source.excerpt}`"
            class="source-result"
          >
            <div class="source-meta">
              <strong>{{ source.title }}</strong>
              <span>第 {{ source.versionNo }} 版</span>
              <span>{{ source.category }}</span>
              <span>{{ source.sourceLocator }}</span>
              <small>匹配度 {{ Math.round(source.score * 100) }}%</small>
            </div>
            <p>{{ source.excerpt }}</p>
          </article>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.content-panel { border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; background: #fff; padding: 20px; box-shadow: 0 8px 24px rgba(20, 71, 68, .035); }
.section-title { display: flex; align-items: flex-start; gap: 10px; color: var(--ds-primary, #126c68); }
.section-title h2 { margin: 0; color: var(--ds-text, #183434); font-size: 18px; }
.section-title p { margin: 4px 0 0; color: var(--ds-muted, #607576); font-size: 13px; }
.search-form { display: flex; gap: 10px; margin-top: 18px; }
.search-form input { min-width: 0; width: 100%; min-height: 42px; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; padding: 0 13px; background: #fff; color: var(--ds-text, #183434); font: inherit; }
.search-form button { display: inline-flex; min-width: 94px; min-height: 42px; align-items: center; justify-content: center; gap: 6px; border: 1px solid var(--ds-line, #dbe8e6); border-radius: 8px; padding: 0 14px; background: #fff; color: var(--ds-primary, #126c68); font: inherit; font-weight: 800; cursor: pointer; }
.search-form button:hover { background: #eff9f7; }
button:disabled { cursor: not-allowed; opacity: .55; }
.overall-summary { margin-top: 16px; border-left: 3px solid #2f7d57; border-radius: 6px; padding: 12px 14px; background: #f3faf6; color: #264846; }
.overall-summary strong { display: block; margin-bottom: 6px; color: #126c68; }
.overall-summary p, .topic-summary, .source-result p { margin: 0; white-space: pre-wrap; line-height: 1.7; }
.topic-results { display: grid; gap: 18px; margin-top: 18px; }
.topic-result { border-top: 1px solid var(--ds-line, #dbe8e6); padding-top: 16px; }
.topic-result header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.topic-result header span { color: #9a6200; font-size: 12px; font-weight: 800; }
.topic-result h3 { margin: 3px 0 0; color: var(--ds-text, #183434); font-size: 17px; }
.topic-result header small { color: var(--ds-muted, #607576); }
.topic-summary { margin-top: 10px; color: #264846; }
.source-list { display: grid; gap: 9px; margin-top: 12px; }
.source-result { border-left: 3px solid var(--ds-primary, #126c68); border-radius: 6px; padding: 11px 13px; background: #f7fbfa; }
.source-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; color: var(--ds-muted, #607576); font-size: 12px; }
.source-meta strong { color: var(--ds-text, #183434); font-size: 14px; }
.source-meta span { border-radius: 12px; padding: 2px 7px; background: #e7f3f0; }
.source-meta small { margin-left: auto; color: var(--ds-primary, #126c68); font-weight: 800; }
.source-result p { margin-top: 8px; color: #354d4d; }
.empty-copy { margin: 14px 0 0; color: var(--ds-muted, #607576); font-size: 14px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 620px) {
  .content-panel { padding: 15px; }
  .search-form { flex-direction: column; }
  .source-meta small { margin-left: 0; }
}
</style>
