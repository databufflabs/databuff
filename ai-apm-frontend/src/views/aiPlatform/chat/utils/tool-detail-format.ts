export const TERMINAL_TOOL_NAMES = new Set(['Bash', 'BashOutput', 'KillShell']);

export interface TerminalToolDisplay {
  meta: string;
  body: string;
}

export interface BashToolParamsDisplay {
  meta: string;
  command: string;
}

export function normalizeToolValue(value: unknown): unknown {
  if (typeof value !== 'string') {
    return value;
  }
  const trimmed = value.trim();
  if (!trimmed) {
    return '';
  }
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

export function stringifyToolValue(value: unknown): string {
  const normalized = normalizeToolValue(value);
  if (typeof normalized === 'string') {
    return normalized;
  }
  return JSON.stringify(normalized, null, 2);
}

export function isTerminalToolName(toolName: string): boolean {
  return TERMINAL_TOOL_NAMES.has(String(toolName || '').trim());
}

export function formatBashToolParamsDisplay(value: unknown): BashToolParamsDisplay | null {
  const normalized = normalizeToolValue(value);
  if (!normalized || typeof normalized !== 'object' || Array.isArray(normalized)) {
    return null;
  }
  const obj = normalized as Record<string, unknown>;
  const command = typeof obj.command === 'string' ? obj.command.trim() : '';
  if (!command) {
    return null;
  }

  const metaLines: string[] = [];
  const description = typeof obj.description === 'string' ? obj.description.trim() : '';
  if (description) {
    metaLines.push(`description: ${description}`);
  }
  if (typeof obj.timeout === 'number' && obj.timeout > 0) {
    metaLines.push(`timeout: ${obj.timeout}`);
  }
  if (obj.run_in_background === true) {
    metaLines.push('run_in_background: true');
  }

  return {
    meta: metaLines.join(' · '),
    command,
  };
}

export function formatTerminalToolDisplay(text: string): TerminalToolDisplay | null {
  const source = String(text || '');
  if (!source.trim()) {
    return null;
  }

  const exitMatch = source.match(
    /^Exit code:\s*(-?\d+)\n(?:(\(output truncated[^\n]*\)\n)?)\n?([\s\S]*)$/,
  );
  if (exitMatch) {
    const truncated = exitMatch[2] ? `\n${exitMatch[2].trim()}` : '';
    const body = (exitMatch[3] || '').trimEnd();
    return {
      meta: `Exit code: ${exitMatch[1]}${truncated}`,
      body: body || '(no output)',
    };
  }

  const backgroundMatch = source.match(
    /^bash_id:\s*(\S+)\nstatus:\s*(\S+)(?:\nexit code:\s*(-?\d+))?(?:\n(\(output truncated[^\n]*\)))?\n\n?([\s\S]*)$/,
  );
  if (backgroundMatch) {
    const metaLines = [
      `bash_id: ${backgroundMatch[1]}`,
      `status: ${backgroundMatch[2]}`,
    ];
    if (backgroundMatch[3]) {
      metaLines.push(`exit code: ${backgroundMatch[3]}`);
    }
    if (backgroundMatch[4]) {
      metaLines.push(backgroundMatch[4].trim());
    }
    const body = (backgroundMatch[5] || '').trimEnd();
    return {
      meta: metaLines.join(' · '),
      body: body || '(no new output)',
    };
  }

  if (/^Background shell started\.\nbash_id:/.test(source)) {
    const lines = source.split('\n');
    return {
      meta: lines[0] || '',
      body: lines.slice(1).join('\n').trim(),
    };
  }

  return null;
}
