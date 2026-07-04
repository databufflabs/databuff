export const SEVERITY_ORDER = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];
export const ERROR_SEVERITIES = ['ERROR', 'FATAL'];

/** Chart hex colors aligned with theme CSS variables (--color-success/warning/danger/info). */
export const LOG_SEVERITY_CHART_COLORS: Record<string, string> = {
  FATAL: '#E12828',
  ERROR: '#E12828',
  WARN: '#F79532',
  INFO: '#08BE7E',
  DEBUG: '#B5B7BB',
  TRACE: '#B5B7BB',
};

export function sortSeverities (levels: string[]) {
  const unique = Array.from(new Set((levels || []).map((item) => String(item).toUpperCase()).filter(Boolean)));
  return unique.sort((a, b) => {
    const ai = SEVERITY_ORDER.indexOf(a);
    const bi = SEVERITY_ORDER.indexOf(b);
    if (ai === -1 && bi === -1) {
      return a.localeCompare(b);
    }
    if (ai === -1) {
      return 1;
    }
    if (bi === -1) {
      return -1;
    }
    return ai - bi;
  });
}

export function getSeverityChartColor (severity: string) {
  const key = String(severity || '').toUpperCase();
  return LOG_SEVERITY_CHART_COLORS[key] || '#B5B7BB';
}

export function getSeverityClass (level: string) {
  const text = String(level || '').toUpperCase();
  if (text === 'ERROR' || text === 'FATAL') {
    return 'is-error';
  }
  if (text === 'WARN') {
    return 'is-warn';
  }
  if (text === 'INFO') {
    return 'is-info';
  }
  return 'is-muted';
}
