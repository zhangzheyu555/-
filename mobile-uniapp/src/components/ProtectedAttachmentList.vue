<script setup lang="ts">
import { ref } from 'vue'
import { openProtectedFile } from '@/platform'
export interface ProtectedAttachment { id: string|number; fileName: string; contentType?: string; path: string }
const props=defineProps<{items:ProtectedAttachment[]}>();const emit=defineEmits<{error:[message:string]}>();const opening=ref<string|number|null>(null)
async function open(item:ProtectedAttachment){if(opening.value!==null)return;opening.value=item.id;try{await openProtectedFile({path:item.path,fileName:item.fileName,contentType:item.contentType})}catch(e){emit('error',e instanceof Error?e.message:'附件打开失败')}finally{opening.value=null}}
</script><template><view v-if="items.length" class="attachments"><button v-for="item in items" :key="item.id" :loading="opening===item.id" :disabled="opening!==null" @click="open(item)"><text class="icon">↗</text><text class="name">{{item.fileName||'查看附件'}}</text></button></view></template>
<style scoped lang="scss">.attachments{display:flex;flex-direction:column;gap:10rpx;margin-top:14rpx}.attachments button{display:flex;min-height:72rpx;margin:0;padding:0 18rpx;align-items:center;gap:12rpx;text-align:left;background:#f7faf9;color:#45505c;border:1rpx solid #d9e6e3;border-radius:12rpx;font-size:24rpx}.attachments button::after{border:0}.icon{color:#27655f}.name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}</style>
