<template>
  <coming-soon :page-name="$t('modules.views.configStatus.agent.s_4e402479')" />
</template>

<script lang="ts">
import { Vue, Component } from 'vue-property-decorator';
import ComingSoon from '@/components/coming-soon/index.vue';

@Component({ components: { ComingSoon } })
export default class AgentPackages extends Vue {}
</script>
