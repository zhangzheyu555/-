<template>
  <section class="assistant-layout">
    <div class="panel assistant-panel">
      <div class="panel-head">
        <h2>数据助手</h2>
        <StatusTag :label="sourceLabel" tone="info" />
      </div>
      <div class="chat-list">
        <div v-for="message in messages" :key="message.id" class="chat-message" :class="message.role">
          <strong>{{ message.role === 'user' ? '你' : '数据助手' }}</strong>
          <p>{{ message.text }}</p>
        </div>
      </div>
      <form class="chat-input" @submit.prevent="send">
        <input v-model="draft" :disabled="loading" placeholder="输入门店、月份、指标，例如：7月净利润排名" />
        <button class="primary-button" type="submit" :disabled="loading">
          <Send />
          {{ loading ? '思考中' : '发送' }}
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
import { computed, ref } from 'vue';
import { Send } from 'lucide-vue-next';
import StatusTag from '../components/StatusTag.vue';
import { chatWithAssistant, type AssistantTurn } from '../services/api';

interface ChatMessage {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  source?: string;
}

const draft = ref('');
const loading = ref(false);
const messages = ref<ChatMessage[]>([
  { id: 1, role: 'assistant', text: '可以查询门店营业额、实收收入、净利润、排名、亏损门店和多月趋势。我只回答当前门店利润系统内的问题。', source: 'local-data' }
]);

const quickQuestions = ['7月净利润排名前十', '1-5月各店净利润排名', '哪些门店亏损', '保利店7月经营明细', '7月营业收入最高的门店'];
const sourceLabel = computed(() => {
  const last = [...messages.value].reverse().find((message) => message.role === 'assistant');
  if (last?.source === 'deepseek') return 'DeepSeek';
  if (last?.source === 'local-fallback') return '本地兜底';
  return '系统内回答';
});

function ask(question: string) {
  draft.value = question;
  send();
}

async function send() {
  const text = draft.value.trim();
  if (!text || loading.value) return;
  messages.value.push({ id: Date.now(), role: 'user', text });
  draft.value = '';
  loading.value = true;
  try {
    const reply = await chatWithAssistant(text, history());
    messages.value.push({ id: Date.now() + 1, role: 'assistant', text: reply.answer, source: reply.source });
  } catch {
    messages.value.push({
      id: Date.now() + 1,
      role: 'assistant',
      text: '数据助手暂时无法连接后端，请确认登录状态和服务是否正常。',
      source: 'error'
    });
  } finally {
    loading.value = false;
  }
}

function history(): AssistantTurn[] {
  return messages.value.slice(-10).map((message) => ({
    role: message.role,
    content: message.text
  }));
}
</script>
