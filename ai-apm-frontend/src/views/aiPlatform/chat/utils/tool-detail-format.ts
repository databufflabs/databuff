export const TERMINAL_TOOL_NAMES = new Set(['Bash', 'BashOutput', 'KillShell']);

export interface TerminalToolDisplay {
  meta: string;
  body: string;
}

export interface BashToolParamsDisplay {
  meta: string;
  /** Beautified multi-line command for display */
  command: string;
  /** Original command (prefer for copy) */
  rawCommand: string;
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

function asToolInputObject(value: unknown): Record<string, unknown> | null {
  const normalized = normalizeToolValue(value);
  if (!normalized || typeof normalized !== 'object' || Array.isArray(normalized)) {
    return null;
  }
  return normalized as Record<string, unknown>;
}

export function extractBashCommand(value: unknown): string {
  const obj = asToolInputObject(value);
  if (!obj) {
    return '';
  }
  return typeof obj.command === 'string' ? obj.command.trim() : '';
}

/**
 * Compact one-line preview for timeline labels (newlines → spaces).
 */
export function summarizeBashCommand(command: string, maxLen = 120): string {
  const compact = String(command || '')
    .replace(/\s+/g, ' ')
    .trim();
  if (!compact) {
    return '';
  }
  if (compact.length <= maxLen) {
    return compact;
  }
  return `${compact.slice(0, Math.max(0, maxLen - 1)).trimEnd()}…`;
}

/**
 * Pretty-print shell commands: break at top-level && / || / | / ; outside quotes.
 */
export function beautifyBashCommand(command: string): string {
  const source = String(command || '').trim();
  if (!source) {
    return '';
  }
  // Already multi-line from the model — normalize indent only
  if (source.includes('\n')) {
    return source
      .split('\n')
      .map((line) => line.replace(/[ \t]+$/g, ''))
      .join('\n')
      .trim();
  }

  const parts: string[] = [];
  let current = '';
  let quote: '"' | "'" | '`' | null = null;
  let escaped = false;

  const flush = () => {
    const piece = current.replace(/[ \t]+$/g, '');
    if (piece) {
      parts.push(piece);
    }
    current = '';
  };

  for (let i = 0; i < source.length; i += 1) {
    const ch = source[i];
    const next = source[i + 1];

    if (escaped) {
      current += ch;
      escaped = false;
      continue;
    }
    if (ch === '\\' && quote !== "'") {
      current += ch;
      escaped = true;
      continue;
    }
    if (quote) {
      current += ch;
      if (ch === quote) {
        quote = null;
      }
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      quote = ch;
      current += ch;
      continue;
    }

    if (ch === '&' && next === '&') {
      current += '&&';
      flush();
      i += 1;
      // skip following spaces; indent on next line
      while (source[i + 1] === ' ' || source[i + 1] === '\t') {
        i += 1;
      }
      continue;
    }
    if (ch === '|' && next === '|') {
      current += '||';
      flush();
      i += 1;
      while (source[i + 1] === ' ' || source[i + 1] === '\t') {
        i += 1;
      }
      continue;
    }
    if (ch === '|') {
      current += '|';
      flush();
      while (source[i + 1] === ' ' || source[i + 1] === '\t') {
        i += 1;
      }
      continue;
    }
    if (ch === ';') {
      current += ';';
      flush();
      while (source[i + 1] === ' ' || source[i + 1] === '\t') {
        i += 1;
      }
      continue;
    }

    current += ch;
  }
  flush();

  if (parts.length <= 1) {
    return source;
  }

  return parts
    .map((part, index) => {
      const trimmed = part.trim();
      if (index === 0) {
        return trimmed;
      }
      // operators were kept at end of previous part; indent continuation
      return `  ${trimmed}`;
    })
    .join('\n');
}

/**
 * Timeline / title label: "Bash · docker ps …" instead of bare "Bash".
 */
export function formatToolCallDisplayName(toolName: string, toolInput?: unknown): string {
  const name = String(toolName || '').trim() || 'tool';
  const obj = asToolInputObject(toolInput);

  if (name === 'Bash') {
    const command = extractBashCommand(toolInput);
    if (command) {
      return `Bash · ${summarizeBashCommand(command)}`;
    }
    const description = typeof obj?.description === 'string' ? obj.description.trim() : '';
    if (description) {
      return `Bash · ${summarizeBashCommand(description, 80)}`;
    }
    return 'Bash';
  }

  if (name === 'BashOutput') {
    const bashId = typeof obj?.bash_id === 'string' ? obj.bash_id.trim() : '';
    return bashId ? `BashOutput · ${bashId}` : 'BashOutput';
  }

  if (name === 'KillShell') {
    const shellId = typeof obj?.shell_id === 'string'
      ? obj.shell_id.trim()
      : (typeof obj?.bash_id === 'string' ? obj.bash_id.trim() : '');
    return shellId ? `KillShell · ${shellId}` : 'KillShell';
  }

  return name;
}

export function formatBashToolParamsDisplay(value: unknown): BashToolParamsDisplay | null {
  const obj = asToolInputObject(value);
  if (!obj) {
    return null;
  }
  const rawCommand = typeof obj.command === 'string' ? obj.command.trim() : '';
  if (!rawCommand) {
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
    command: beautifyBashCommand(rawCommand),
    rawCommand,
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
