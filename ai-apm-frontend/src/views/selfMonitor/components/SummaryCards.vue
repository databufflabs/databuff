<template>
  <div class="sm-cards" v-loading="loading">
    <div
      v-for="(card, idx) in cards"
      :key="idx"
      :class="['sm-card', `tone-${card.tone || 'default'}`]">
      <div class="sm-card-title">{{ card.title }}</div>
      <div class="sm-card-value">{{ display[idx] || '—' }}</div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import { SummaryCardSpec, formatCardValue, queryPlatformSummary, timeWindowSeconds } from '../shared';

@Component
export default class SummaryCards extends Vue {
  @Prop({ required: true }) public cards!: SummaryCardSpec[];
  @Prop({ required: true }) public timeParams!: { fromTime: string; toTime: string };

  private loading = false;
  private display: string[] = [];

  @Watch('timeParams', { deep: true })
  private onTime() {
    this.load();
  }

  @Watch('cards', { deep: true })
  private onCards() {
    this.load();
  }

  private mounted() {
    this.load();
  }

  private async load() {
    if (!this.timeParams?.fromTime || !this.cards?.length) {
      return;
    }
    this.loading = true;
    try {
      const values = await Promise.all(
        this.cards.map(card => queryPlatformSummary(this.timeParams, card)),
      );
      const windowSec = timeWindowSeconds(this.timeParams);
      this.display = values.map((v, i) => {
        const card = this.cards[i];
        if (card.asRate) {
          if (v == null || windowSec <= 0) {
            return '—';
          }
          const rate = Number(v) / windowSec;
          const digits = card.digits != null ? card.digits : 1;
          const unit = card.rateUnit || '/s';
          return `${formatCardValue(rate, digits)}${unit}`;
        }
        const text = formatCardValue(v as any, card.digits || 0);
        if (text === '—' || !card.unit) {
          return text;
        }
        return `${text}${card.unit}`;
      });
    } finally {
      this.loading = false;
    }
  }
}
</script>

<style lang="scss" scoped>
.sm-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

@media (max-width: 1100px) {
  .sm-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.sm-card {
  position: relative;
  background: linear-gradient(180deg, #ffffff 0%, #fbfcfd 100%);
  border: 1px solid #e6ebf0;
  border-radius: 10px;
  padding: 14px 16px 16px;
  min-height: 84px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);

  &::before {
    content: '';
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 3px;
    background: #94a3b8;
  }

  &.tone-ok::before { background: #0f766e; }
  &.tone-warn::before { background: #b45309; }
  &.tone-danger::before { background: #be123c; }
  &.tone-default::before { background: #2563eb; }
}

.sm-card-title {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 10px;
  font-weight: 500;
}

.sm-card-value {
  font-size: 26px;
  font-weight: 650;
  color: #0f172a;
  line-height: 1.1;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}
</style>
