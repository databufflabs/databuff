<template>
  <div class="span-info-wrapper">
    <template v-for="item in allInfoList">
      <div :key="item.title" class="info-title">{{ item.titleKey ? $t(item.titleKey) : item.title }}</div>
      <div v-for="t in item.list" :key="`${item.title}_${t.key}`" class="info-item">
        <div class="info-item-t">{{ t.labelKey ? $t(t.labelKey) : t.label }}:</div>
        <div class="info-item-v text-expand">
          <template v-if="!Array.isArray(t.value)">{{ t.value || '-' }}</template>
          <template
            v-else-if="t.value.length"
            v-for='v,i in t.value.slice(0, 4)'>
            <br v-if="i > 0" :key='i'>{{ v }}
          </template>
          <template v-else>-</template>
          <span @click="expandHandle(t.label, t.value)" class="text-expand-btn blue cp">{{ $t('modules.views.appMonitor.traceDetail.s_a6eebb90') }}</span>
        </div>
      </div>
    </template>

    <expand-dialog
      :showModel="showExportModal"
      :previewText="previewText"
      :title="previewTitle"
      @on-close="showExportModal = false" />
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import SpanKeysCn from './spanKeys';
import { orderBy } from 'lodash';
import ExpandDialog from './expand-dialog.vue';

type InfoItem = {
  key: string;
  label: string;
  labelKey?: string;
  value: string | string[];
}

type Info = {
  title: string;
  titleKey?: string;
  list: InfoItem[];
}

const DetailMetaKeys = [
  'component',
  'language',
  'http.status_code',
  'method',
  'normalized.resource',
  'db.type',
  'db.instance',
  'db.port',
  'db.returnRows',
  'db.updateRows',
  'request.body.length',
  'response.body.length',
  'root.type',
  'root.resource',
]

// 请求头 响应头
const formatHeaderInfo = (headerInfo: any) => {
  const list: InfoItem[] = []
  try {
    const _headerInfo = JSON.parse(headerInfo || '{}')
    const keys = orderBy(Object.keys(_headerInfo), [(key: string) => key.toLocaleLowerCase()], ['asc']);
    keys.forEach(key => {
      list.push({ key, label: key, value: _headerInfo[key] })
    })
  } catch (error) {
    //
  }
  return list
}

@Component({
  components: {
    ExpandDialog,
  }
})
export default class SpanDetailBaseinfo extends Vue {
  @Prop({ default: () => ({}) }) public spanInfo!: any;

  get allInfoList () {
    const list = [
      this.linkInfo,
      this.envInfo,
      this.detailInfo,
      this.requestHeaderInfo,
      this.responseHeaderInfo,
      this.otherInfo,
    ];
    return list.filter(item => item.list.length);
  }

  get linkInfo (): Info {
    const { trace_id, span_id, parent_id } = this.spanInfo;
    return {
      title: i18n.t('modules.views.appMonitor.traceDetail.s_a942bed8') as string, titleKey: 'modules.views.appMonitor.traceDetail.s_a942bed8',
      list: [
        { key: 'trace_id', label: 'TraceID', value: trace_id },
        { key: 'span_id', label: 'SpanID', value: span_id },
        { key: 'parent_id', label: 'ParentID', value: parent_id === '0' ? '-' : parent_id },
      ]
    }
  }

  get envInfo (): Info {
    const spanInfo = this.spanInfo;
    const isIn = Number(this.spanInfo.isIn) === 1 ? 1 : 0;
    const isOut = Number(this.spanInfo.isOut) === 1 ? 1 : 0;
    const meta = this.spanInfo.meta || {};
    const info = {
      title: i18n.t('modules.views.appMonitor.traceDetail.s_a66bea43') as string, titleKey: 'modules.views.appMonitor.traceDetail.s_a66bea43',
      list: [
        { key: 'agent.source', label: SpanKeysCn['agent.source'] || 'agent.source', value: meta['agent.source'] },
        { key: 'hostName', label: i18n.t('modules.views.appMonitor.traceDetail.s_fbf62a06') as string, labelKey: 'modules.views.appMonitor.traceDetail.s_fbf62a06', value: spanInfo.hostName },
        {
          key: 'spanType',
          label: i18n.t('modules.views.appMonitor.traceDetail.s_d4e1cecc') as string, labelKey: 'modules.views.appMonitor.traceDetail.s_d4e1cecc',
          value: isIn === 1
            ? i18n.t('modules.views.appMonitor.traceDetail.s_0d55ccac') as string
            : isOut === 1
              ? i18n.t('modules.views.appMonitor.traceDetail.s_e37a31f0') as string
              : i18n.t('modules.views.appMonitor.traceDetail.s_50ab2160') as string,
        },
        { key: 'service', label: i18n.t('modules.views.appMonitor.traceDetail.s_77f51b56') as string, labelKey: 'modules.views.appMonitor.traceDetail.s_77f51b56', value: spanInfo.service },
        { key: 'serviceInstance', label: i18n.t('modules.views.appMonitor.traceDetail.s_74973c00') as string, labelKey: 'modules.views.appMonitor.traceDetail.s_74973c00', value: spanInfo.serviceInstance },
      ],
    };

    if (isIn === 1) {
      info.list.push(
        { key: 'clientService', label: i18n.t('modules.views.appMonitor.traceDetail.s_35a59e33') as string, labelKey: 'modules.views.appMonitor.traceDetail.s_35a59e33', value: spanInfo.clientService },
        { key: 'clientServiceInstance', label: i18n.t('modules.views.appMonitor.serviceCallDetail.s_d706f6d9') as string, labelKey: 'modules.views.appMonitor.serviceCallDetail.s_d706f6d9', value: spanInfo.clientServiceInstance },
      );
    } else if (isOut === 1) {
      info.list.push(
        { key: 'server.service', label: SpanKeysCn['server.service'] || 'server.service', value: meta['server.service'] },
        { key: 'server.ip', label: SpanKeysCn['server.ip'] || 'server.ip', value: meta['server.ip'] },
      );
    }

    info.list.push(
      { key: 'thread.name', label: SpanKeysCn['thread.name'] || 'thread.name', value: meta['thread.name'] },
    );

    return info;
  }

  get detailInfo (): Info {
    const meta = { ...this.spanInfo.metrics, ...this.spanInfo.meta };
    return {
      title: i18n.t('modules.views.appMonitor.traceDetail.s_342730b6') as string, titleKey: 'modules.views.appMonitor.traceDetail.s_342730b6',
      list: DetailMetaKeys.filter(key => !!meta[key]).map(key => ({
        key,
        label: SpanKeysCn[key] || key,
        value: meta[key],
      })),
    }
  }

  get requestHeaderInfo (): Info {
    const meta = { ...this.spanInfo.metrics, ...this.spanInfo.meta };
    return {
      title: i18n.t('modules.views.appMonitor.traceDetail.s_be47bd27') as string, titleKey: 'modules.views.aiPlatform.tools.s_be47bd27',
      list: formatHeaderInfo(meta.requestHeader)
    }
  }

  get responseHeaderInfo (): Info {
    const meta = { ...this.spanInfo.metrics, ...this.spanInfo.meta };
    return {
      title: i18n.t('modules.views.appMonitor.traceDetail.s_dca6cb61') as string, titleKey: 'modules.views.appMonitor.traceDetail.s_dca6cb61',
      list: formatHeaderInfo(meta.responseHeader)
    }
  }

  get otherInfo (): Info {
    const meta = { ...this.spanInfo.metrics, ...this.spanInfo.meta };
    const ignoreKeys = [
      ...DetailMetaKeys,
      'agent.source',
      'server.service',
      'server.ip',
      'thread.name',
      'requestHeader',
      'responseHeader',
    ];
    const keys = orderBy(Object.keys(meta).filter(key => !ignoreKeys.includes(key)), [(key: string) => key.toLocaleLowerCase()], ['asc']);
    return {
      title: i18n.t('modules.views.appMonitor.traceDetail.s_febe40ce') as string, titleKey: 'modules.views.appMonitor.traceDetail.s_febe40ce',
      list: keys.map(key => ({
        key,
        label: SpanKeysCn[key] || key,
        value: `${meta[key] || ''}`.split('\n').filter(t => !!t),
      })).filter(item => item.value.length),
    };
  }

  private showExportModal = false;
  private previewTitle = '';
  private previewText = '';
  private expandHandle (label: string, value: string | string[]) {
    this.previewTitle = label;
    this.previewText = Array.isArray(value) ? value.join('\n') : value;
    this.showExportModal = true;
  }
}
</script>

<style scoped lang="scss">
.span-info-wrapper {
  --line-height: 18px;

  .info-title {
    font-size: 13px;
    font-weight: 500;
    line-height: 14px;
  }

  .info-item + .info-title {
    margin-top: 20px;
  }

  .info-item {
    margin-top: 8px;
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    font-size: 12px;
    line-height: var(--line-height);
    overflow: hidden;

    .info-item-t {
      flex: none;
      padding-right: 10px;
      width: 100px;
      word-break: break-all;
      color: var(--color-text-secondary);
    }
    .info-item-v {
      flex: 1;
      overflow: hidden;
    }
  }

  .text-expand {
    max-height: calc(var(--line-height) * 4);
    font-size: 12px;
    line-height: var(--line-height);
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 4;
    word-break: break-all;
    overflow: hidden;
    position: relative;
    &::after {
      content: '';
      background-color: var(--bg-color);
      position: absolute;
      top: calc(var(--line-height) * 3);
      left: 0;
      right: 0;
      bottom: 0;
      pointer-events: none;
    }
    .text-expand-btn {
      height: var(--line-height);
      position: absolute;
      top: calc(var(--line-height) * 3);
      left: 0;
      z-index: 1;
    }
  }
}
</style>
