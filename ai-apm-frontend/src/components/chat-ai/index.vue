<template>
  <el-drawer
    :visible.sync="showChat"
    destroy-on-close
    :size="640"
    :before-close="cancelHandle"
    ref="chatAIDrawer"
    class="chat-ai-drawer">
    <template slot="title">
      <div class="title-head">
        <div class="flex-h">
          <img src="/src/assets/img/chatai/chat_star.svg" class="chat-star" />{{ $t('modules.components.chat-ai.s_10353d70') }}
        </div>
        <div class="flex-h">
          <span
            @click="retryHandle"
            class="retry">{{ $t('modules.components.chat-ai.s_a7c23201') }}</span>
          <el-switch
            v-model="showThoughtProcess"
            disabled
            @change="toggleShowThoughtProcessHandle"
            :active-text="$t('modules.components.chat-ai.s_0178ef06')"
            class="switch" />
        </div>
      </div>

      <div class="title-cont">
        <div>{{ $t('modules.components.chat-ai.s_48a8427c') }}</div>
        <div class="flex-h alarm-info">
          <span class="alarm-tag"
            :class="{
              'alarm-tag-red': params.level === 3,
              'alarm-tag-yellow': params.level === 2,
            }">{{ params.level | AlarmStatusFilter }}</span>
          <div class="alarm-title">{{ params.descriptionKey ? $t(params.descriptionKey) : params.description }}</div>
        </div>
      </div>

      <img src="/src/assets/img/chatai/chat_bg.svg" class="chat-bg" />
    </template>

    <!-- <div class="timestamp"><span>{{ $t('modules.components.chat-ai.s_8fc3a8a9') }}</span></div> -->

    <!-- 已完成动画的对话 -->
    <div v-for="item in chatDoneList"
      :key="item.id"
      :class="item.isAsk ? 'ask-item' : 'answer-item'"
      class="char-item">
      <div class="item-content">
        <div v-if="showThoughtProcess && !item.isAsk && item.reasoningContent" class="think-box">
          <div class="think-head" :class="{ active: item.showThought }" @click="item.showThought = !item.showThought">
            <div class="flex-h"><i class="el-icon-circle-check check-icon"></i>{{ $t('modules.components.chat-ai.s_38bb24db') }}</div>
            <span class="el-icon-arrow-down down-arrow"></span>
          </div>
          <el-collapse-transition>
            <div v-show="item.showThought" class="think-cont">{{ item.reasoningContent }}</div>
          </el-collapse-transition>
        </div>

        <marked-view
          :data="item.content || ''"
          :showCopy="false"
          class="item-marked-cont" />
      </div>
    </div>

    <!-- 正在动画中的对话 -->
    <div ref="chatContentTyped"></div>
    <div ref="chatContentTemp">
      <div>
        <div v-for="(item, index) in chatAnimateList"
          :key="item.id"
          :class="[
            item.isAsk ? 'ask-item' : 'answer-item',
            index === chatAnimateList.length - 1 && !item.isAsk ? 'last-answer' : '',
          ]"
          class="char-item">
          <div class="item-content">
            <div v-if="showThoughtProcess && !item.isAsk && item.reasoningContent" class="think-box">
              <div class="think-head" :class="{ active: item.showThought }" @click="item.showThought = !item.showThought">
                <div class="flex-h"><i class="el-icon-circle-check check-icon"></i>{{ $t('modules.components.chat-ai.s_38bb24db') }}</div>
                <span class="el-icon-arrow-down down-arrow"></span>
              </div>
              <el-collapse-transition>
                <div v-show="item.showThought" class="think-cont">{{ item.reasoningContent }}</div>
              </el-collapse-transition>
            </div>

            <marked-view
              :data="item.content || ''"
              :showCopy="false"
              class="item-marked-cont" />
          </div>
        </div>
      </div>
    </div>

    <!-- loading -->
    <div v-show="isLoading && !chatAnimateList.length" class="char-item answer-item">
      <div class="item-content loading-content">
        <div class="thinking-box flex-h">
          <span class="chat-loading"></span>
          <template v-if="showThoughtProcess">{{ $t('modules.components.chat-ai.s_4cd7a418') }}</template>
          <template v-else>{{ $t('modules.components.chat-ai.s_39789b71') }}</template>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import Typed from 'typed.js';
import { v4 as uuidv4 } from 'uuid';
import MarkedView from '@/components/marked-view.vue';
import { toAsyncWait } from '@/utils/common';
import AlarmApi from '@/api/alarm';

@Component({
  components: {
    MarkedView,
  }
})
export default class ChatAI extends Vue {
  @Prop({ default: () => ({}) }) private params!: any;

  public $refs!: {
    chatAIDrawer: any;
    chatContentTyped: HTMLDivElement;
    chatContentTemp: HTMLDivElement;
  }

  private showChat = false;
  private isLoading = false;
  private firstLoad = true;

  private showThoughtProcess = false;

  private chatDoneList: any[] = []; // 已完成动画的对话
  private chatAnimateList: any[] = []; // 正在动画中的对话
  private chatQueuingList: any[] = []; // 正在排队中的对话

  private chatEnd = false;

  private chatTimer: any = null;
  private scrollToBottomTimer: any = null;

  private scrollContainer: any = null;
  private scrollHandle: any = null;

  private isUserScrolling = false; // 用户是否主动滚动（离开底部）
  private programmaticScroll = false;
  private readonly stickBottomThreshold = 48;

  private typed: any = null

  private beforeDestroy () {
    this.cancelHandle()
  }

  public async showHandle (retry: boolean = false) {
    this.showChat = true;
    this.isLoading = true;
    this.startAiRootAnalyse(retry).then(async() => {
      await this.getAiResult();
      if (!this.chatEnd) {
        this.loopGetAiResult();
      } else {
        this.isLoading = false;
      }
    }).catch(() => {
      this.isLoading = false;
    })

    this.$nextTick(() => {
      if (!this.scrollContainer) {
        const scrollContainer = this.$refs.chatAIDrawer.$el.querySelector('.el-drawer__body');
        this.scrollContainer = scrollContainer;
      }
      if (this.scrollContainer && !this.scrollHandle) {
        this.scrollHandle = () => {
          if (!this.scrollContainer) {
            return
          }
          const { scrollHeight, clientHeight, scrollTop } = this.scrollContainer
          const distanceFromBottom = scrollHeight - clientHeight - scrollTop
          const nearBottom = distanceFromBottom <= this.stickBottomThreshold
          // 程序化贴底时仍允许检测「已离开底部」，避免自动滚动窗口吞掉用户上滑
          if (this.programmaticScroll) {
            if (!nearBottom) {
              this.isUserScrolling = true
              this.programmaticScroll = false
            }
            return
          }
          this.isUserScrolling = !nearBottom
        }
        this.scrollContainer.addEventListener('scroll', this.scrollHandle)
        this.scrollContainer.addEventListener('wheel', this.onChatWheel, { passive: true })
      }
      if (this.scrollContainer && !this.scrollToBottomTimer) {
        this.loopScrollToBottom();
      }
    })
  }

  private cancelHandle () {
    this.isLoading = false
    this.showChat = false
    this.firstLoad = true
    this.showThoughtProcess = false
    this.chatDoneList = []
    this.chatAnimateList = []
    this.chatQueuingList = []
    this.chatEnd = false
    if (this.chatTimer) {
      window.clearTimeout(this.chatTimer);
      this.chatTimer = null;
    }
    if (this.scrollToBottomTimer) {
      window.clearInterval(this.scrollToBottomTimer);
      this.scrollToBottomTimer = null;
    }
    if (this.scrollContainer) {
      this.scrollContainer.removeEventListener('scroll', this.scrollHandle)
      this.scrollContainer.removeEventListener('wheel', this.onChatWheel)
      this.scrollContainer = null
      this.scrollHandle = null
    }
    this.isUserScrolling = false
    this.programmaticScroll = false
    if (this.typed) {
      this.typed.destroy();
      this.typed = null;
    }
  }

  private retryHandle () {
    this.cancelHandle()
    this.showHandle(true)
  }

  private async startAiRootAnalyse (retry: boolean = false) {
    const params = {
      service: this.params.service,
      fromTime: this.params.fromTime,
      toTime: this.params.toTime,
      retry: !!retry,
    }
    const { result, error } = await toAsyncWait(AlarmApi.startAiRootAnalyse(params));
    if (error && error.message !== 'interrupt') {
      this.$message.error(error.message);
    }
  }

  private async getAiResult () {
    const params = {
      service: this.params.service,
      fromTime: this.params.fromTime,
      toTime: this.params.toTime,
    }
    const { result, error } = await toAsyncWait(AlarmApi.getAiResult(params));
    if (!error) {
      const messages = (result?.messages || []).map((t: any) => ({
        ...t,
        id: uuidv4(),
        isAsk: t.messageType === 'databuff',
        showThought: false,
      }));
      this.chatEnd = !!result?.end;

      // TODO:测试代码
      // messages.length = messages.length > 6 ? 6 : messages.length;
      // const _messagesLength = messages.length;
      // const _length01 = this.chatDoneList.length;
      // const _length02 = this.chatAnimateList.length;
      // const _length03 = this.chatQueuingList.length;
      // let _totalLength = _length01 + _length02 + _length03;
      // if (_totalLength < messages.length) {
      //   _totalLength = _totalLength + 1;
      //   messages.length = _totalLength;
      // }
      // this.chatEnd = !!result?.end && _totalLength >= _messagesLength;
      // TODO:测试代码

      if (this.firstLoad && this.chatEnd) { // 首次加载并且对话已经结束
        this.chatDoneList = messages;
      } else {
        const doneLength = this.chatDoneList.length;
        const animateLength = this.chatAnimateList.length;
        this.chatDoneList = messages.slice(0, doneLength);
        this.chatQueuingList = messages.slice(doneLength + animateLength);
        this.updateList();
      }
      this.firstLoad = false;
    } else {
      this.chatEnd = true;
      if (error.message !== 'interrupt') {
        this.$message.error(error.message);
      }
    }
  }

  // 更新列表
  private updateList () {
    // 没有排队数据或者存在动画数据，不需要更新
    if (this.chatAnimateList.length || !this.chatQueuingList.length) {
      return
    }
    this.chatAnimateList = [...this.chatQueuingList]
    this.chatQueuingList = []
    this.$nextTick(() => {
      this.typed = new Typed(this.$refs.chatContentTyped, {
        stringsElement: this.$refs.chatContentTemp,
        showCursor: false,
        typeSpeed: 0,
        onComplete: () => {
          this.chatDoneList = this.chatDoneList.concat(this.chatAnimateList);
          this.chatAnimateList = [];
          if (this.typed) {
            this.typed.destroy();
            this.typed = null;
          }
          this.updateList();
        },
      });
    })
  }

  // 循环获取AI结果
  private loopGetAiResult () {
    if (this.chatTimer) {
      window.clearTimeout(this.chatTimer);
      this.chatTimer = null;
    }
    this.chatTimer = setTimeout(async() => {
      await this.getAiResult();
      if (!this.chatEnd) {
        this.loopGetAiResult();
      } else {
        this.isLoading = false;
      }
    }, 5000);
  }

  // 自动滚动：仅在用户贴底时跟随最新内容
  private onChatWheel = (event: WheelEvent) => {
    if (event.deltaY < 0) {
      this.isUserScrolling = true
      this.programmaticScroll = false
    }
  }

  private loopScrollToBottom () {
    if (this.scrollToBottomTimer) {
      window.clearInterval(this.scrollToBottomTimer);
      this.scrollToBottomTimer = null;
    }
    this.scrollToBottomTimer = setInterval(async() => {
      this.$nextTick(() => {
        if (!this.scrollContainer || this.isUserScrolling) {
          return;
        }
        this.programmaticScroll = true;
        const { scrollHeight, clientHeight } = this.scrollContainer;
        this.scrollContainer.scrollTop = scrollHeight - clientHeight;
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            this.programmaticScroll = false;
          });
        });
      })
    }, 1000);
  }

  private toggleShowThoughtProcessHandle () {
    this.chatDoneList.forEach((item) => {
      this.$set(item, 'showThought', false);
    })
    this.chatQueuingList.forEach((item) => {
      this.$set(item, 'showThought', false);
    })
  }
}
</script>

<style lang="scss" scoped>
.chat-ai-drawer {
  :deep(.el-drawer__header) {
    margin: 0;
    padding: 16px 20px 10px;
    display: block;
    height: 140px;
    background: var(--bg-color) !important;
    box-shadow: none !important;
    position: relative;
    z-index: 0;
    font-size: 14px;
    font-weight: 500;
    line-height: 24px;
  }
  :deep(.el-drawer__body) {
    flex: 1;
    padding: 10px 20px 20px;
    background: var(--bg-color) !important;
  }
  :deep(.el-drawer__close-btn) {
    padding: 2px 4px;
    width: 24px;
    height: 20px;
    display: flex;
    font-size: 16px;
    position: absolute;
    top: 18px;
    right: 16px;
  }
  .chat-bg {
    width: 100%;
    height: 120px;
    position: absolute;
    top: 0;
    left: 0;
    z-index: -1;
    pointer-events: none;
  }
}

.title-head {
  margin-bottom: 16px;
  padding-right: 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  .chat-star {
    margin-right: 6px;
  }
  .retry {
    margin-right: 16px;
    font-size: 13px;
    font-weight: normal;
    color: var(--color-primary);
    cursor: pointer;
  }
  .switch {
    height: 24px;
    line-height: 24px;
    align-items: center;
    flex-direction: row-reverse;
    cursor: pointer;
    &.is-disabled {
      cursor: not-allowed;
    }
    :deep(.el-switch__label) {
      height: 14px;
      margin: 0 4px 0 0;
      font-weight: normal;
      line-height: 14px;
      * {
        display: block;
        font-size: 13px;
      }
    }
    :deep(.el-switch__core) {
      width: 26px !important;
      height: 14px !important;
      background-color: var(--color-info);
      border-color: var(--color-info);
      &::after {
        width: 12px;
        height: 12px;
        top: 0;
        left: 0;
      }
    }
    &.is-checked {
      :deep(.el-switch__core) {
        &::after {
          left: 100%;
          margin-left: -12px;
        }
      }
    }
  }
}

.title-cont {
  padding: 11px;
  background: 
    linear-gradient(to bottom, var(--bg-color), var(--bg-color)) padding-box,
    linear-gradient(263deg, #E98CDE -1%, #B484F4 34%, #3991FF 66%, #63D8FF 100%, #63D8FF 100%) border-box;
  border: 1px solid transparent;
  border-radius: 8px;
  background-clip: padding-box, border-box;
  font-size: 13px;
  line-height: 22px;
  font-weight: normal;
  .alarm-info {
    margin-top: 6px;
  }
  .alarm-tag {
    margin-right: 6px;
    padding: 0 6px;
    height: 20px;
    border-radius: 2px;
    font-size: 12px;
    font-weight: normal;
    line-height: 20px;
    text-align: center;
    color: #FFFFFF;
    background-color: var(--color-info);
    &.alarm-tag-red {
      background-color: var(--color-danger);
    }
    &.alarm-tag-yellow {
      background-color: var(--color-warning);
    }
  }
  .alarm-title {
    flex: 1;
    text-overflow: ellipsis;
    white-space: nowrap;
    overflow: hidden;
    font-weight: 500;
  }
}

.timestamp {
  margin-bottom: 16px;
  text-align: center;
  span {
    display: inline-flex;
    height: 24px;
    line-height: 24px;
    border-radius: 4px;
    padding: 0 8px;
    background: var(--bg-color03);
    color: var(--color-text-secondary);
  }
}

.chat-loading {
  display: inline-flex;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  position: relative;
  animation: 1s linear 0s infinite normal none running rotating;
  &::before {
    content: '';
    width: 100%;
    height: 100%;
    border-radius: 50%;
    background: conic-gradient(var(--color-primary), rgba(0, 0, 0, 0));
    scale: 0.812;
    position: absolute;
    top: 0;
    left: 0;
  }
  &::after {
    content: '';
    width: 100%;
    height: 100%;
    border-radius: 50%;
    background: var(--bg-color);
    scale: 0.555;
    position: absolute;
    top: 0;
    left: 0;
  }
}

.char-item {
  display: flex;
  margin-bottom: 16px;
  .item-content {
    max-width: 544px;
    min-height: 44px;
    color: var(--color-text-primary);
    padding: 9px 11px;
    font-size: 13px;
    font-weight: normal;
    line-height: 24px;
    border: 1px solid transparent;
  }
  .chat-loading {
    margin: 1px;
  }
  .loading-content {
    display: flex;
    align-items: center;
    .thinking-box {
      padding: 7px 12px;
      border-radius: 8px;
      background: var(--bg-color03);
      .chat-loading {
        margin: 0 8px 0 0;
      }
    }
  }
}
.ask-item {
  justify-content: flex-end;
  .item-content {
    border-radius: 8px 8px 0px 8px;
    background: #E1E9FF;
  }
}
.answer-item {
  .item-content {
    border-radius: 8px 8px 8px 0px;
    background: var(--bg-color);
    border-color: var(--border-color-lighter);
  }
  &.last-answer {
    .item-content {
      border-color: transparent;
      background: linear-gradient(to bottom, var(--bg-color), var(--bg-color)) padding-box, linear-gradient(263deg, #E98CDE -1%, #B484F4 34%, #3991FF 66%, #63D8FF 100%, #63D8FF 100%) border-box;
      background-clip: padding-box, border-box;
    }
  }
}

.think-box {
  margin-bottom: 10px;
  padding: 7px 12px 3px 12px;
  border-radius: 8px;
  background: var(--bg-color03);
  .think-head {
    padding-bottom: 4px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    cursor: pointer;
    .check-icon {
      margin-right: 8px;
      font-size: 18px;
      color: var(--color-primary);
    }
    .down-arrow {
      font-size: 16px;
      transition: all .3s ease;
    }
    &.active .down-arrow {
      transform: rotate(180deg);
    }
  }
  .think-cont {
    // padding-bottom: 7px;
    padding-left: 26px;
    position: relative;
    color: var(--color-text-secondary);
    &::before {
      content: '';
      width: 2px;
      height: calc(100% - 15px);
      position: absolute;
      top: 4px;
      left: 8px;
      transform: scaleX(0.5);
      background-color: var(--border-color-base);
      pointer-events: none;
    }
    &::after {
      content: '';
      display: block;
      height: 7px;
      pointer-events: none;
    }
  }
}

@keyframes rotating {
  0% {
    transform: rotate(0deg);
  }
  100% {
    transform: rotate(1turn);
  }
}
</style>

<style lang="scss">
.chat-ai-drawer .item-content .item-marked-cont {
  font-size: 13px;
  font-weight: normal;
  line-height: 24px;
  p {
    margin: 0;
    code {
      margin-left: 6px;
      margin-right: 6px;
      padding-left: 6px;
      padding-right: 6px;
    }
  }

  code {
    background: #F1F5F9;
  }
  .hljs-comment,
  .hljs-quote {
    color: var(--color-text-secondary);
  }
}
</style>
