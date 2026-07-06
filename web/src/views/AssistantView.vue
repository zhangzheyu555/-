<template>
  <section class="assistant-layout">
    <div class="panel assistant-panel">
      <div class="panel-head">
        <h2>数据助手</h2>
        <StatusTag label="系统内回答" tone="info" />
      </div>
      <div class="chat-list">
        <div v-for="message in messages" :key="message.id" class="chat-message" :class="message.role">
          <strong>{{ message.role === 'user' ? '你' : '数据助手' }}</strong>
          <p>{{ message.text }}</p>
        </div>
      </div>
      <form class="chat-input" @submit.prevent="send">
        <input v-model="draft" placeholder="输入门店、月份、指标，例如：保利店7月净利润" />
        <button class="primary-button" type="submit">
          <Send />
          发送
        </button>
      </form>
    </div>

    <aside class="panel">
      <div class="panel-head">
        <h2>快捷问题</h2>
      </div>
      <div class="quick-list">
        <button v-for="question in quickQuestions" :key="question" @click="ask(question)">{{ question }}</button>
      </div>
    </aside>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { Send } from 'lucide-vue-next';
import StatusTag from '../components/StatusTag.vue';
import { assistantReplies } from '../data/mock';

interface ChatMessage {
  id: number;
  role: 'user' | 'assistant';
  text: string;
}

const draft = ref('');
const messages = ref<ChatMessage[]>([
  { id: 1, role: 'assistant', text: '可以查询门店营业额、净利润、排名、亏损门店、工资和巡店记录。' }
]);

const quickQuestions = ['保利店7月净利润', '哪些门店亏损', '净利润排名前三', '长大店营业额趋势'];

function ask(question: string) {
  draft.value = question;
  send();
}

function send() {
  const text = draft.value.trim();
  if (!text) return;
  messages.value.push({ id: Date.now(), role: 'user', text });
  const reply = assistantReplies[messages.value.length % assistantReplies.length];
  messages.value.push({ id: Date.now() + 1, role: 'assistant', text: reply });
  draft.value = '';
}
</script>
