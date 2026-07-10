import http from '@/utils/axios';

export interface ExpertRuntimeOptions {
  category?: string;
  stream?: boolean;
  enablePlan?: boolean;
  dynamicSkillsEnabled?: boolean;
  exposeToolEvents?: boolean;
  toolAccessMode?: 'ALLOWLIST' | 'BLOCKLIST';
}

export interface AiChatRequest {
  sessionId?: string | null;
  expertId?: string | null;
  message: string;
  stream?: boolean;
  context?: Record<string, unknown>;
  requestId?: string | null;
  modelProviderCode?: string | null;
  modelName?: string | null;
  userName?: string | null;
}

export interface AiChatResponse {
  sessionId: string;
  expertId: string;
  reply: string;
  llmReady: boolean;
}

export interface AiSessionSummary {
  sessionId: string;
  expertId?: string;
  title?: string;
  updatedAt?: string;
}

export interface AiSessionListResponse {
  data: AiSessionSummary[];
  total: number;
  offset: number;
  size: number;
  status?: number;
  message?: string;
}

export interface AiSessionCountResponse {
  total: number;
  status?: number;
  message?: string;
}

export interface AiChatMessage {
  messageId?: string;
  role: string;
  content: string;
  expertId?: string;
  messageType?: string;
  messageStatus?: string;
  metadata?: Record<string, unknown>;
  roundIndex?: number;
  messageIndex?: number;
  ts?: string;
  updatedAt?: string;
  timestamp?: string;
}

export interface AiChatPollResponse {
  running: boolean;
  messages: AiChatMessage[];
}

export interface AiChatSubmitResponse {
  sessionId: string;
  expertId: string;
  status: string;
  assistantMessageId?: string | null;
}

export interface AiChatAbortResponse {
  sessionId: string;
  aborted: boolean;
}

export interface AiToolDefinition {
  toolId: string;
  name: string;
  category?: string;
  description?: string;
  type?: string;
  implementation?: string;
  schemaJson?: string;
  configJson?: string;
  enabled: boolean;
  builtIn: boolean;
  version?: number;
}

export interface AiSkillFileEntry {
  path: string;
  size: number;
}

export interface AiSkillFileContent {
  path: string;
  content: string;
  contentType: string;
}

export interface AiSkillImportPreview {
  skillId: string;
  skillName: string;
  description?: string;
  files: AiSkillFileEntry[];
}

export interface AiSkillDefinition {
  skillId: string;
  name: string;
  category?: string;
  description?: string;
  contentUri: string;
  filePath?: string;
  enabled: boolean;
  builtIn: boolean;
  version?: number;
}

export interface AiExpertDefinition {
  expertId: string;
  name: string;
  category?: string;
  description?: string;
  type?: string;
  modelProviderCode?: string;
  modelName?: string;
  systemPrompt?: string;
  toolIds?: string[];
  skillIds?: string[];
  options?: ExpertRuntimeOptions;
  enabled: boolean;
  builtIn: boolean;
  version?: number;
}

export interface ExpertTask {
  taskId: string;
  sessionId?: string;
  sourceExpertId?: string;
  targetExpertId?: string;
  status: string;
  input?: string;
  output?: string;
  error?: string;
  createdAt?: string;
  updatedAt?: string;
}

const unwrapList = <T>(res: any): T[] => {
  if (Array.isArray(res)) {
    return res;
  }
  if (Array.isArray(res?.data)) {
    return res.data;
  }
  return [];
};

export default {
  submitChat: (data: AiChatRequest) =>
    http.post('/api/v1/ai/chat/submit', { ...data, stream: false }),

  pollMessages: (sessionId: string, afterMessageId?: string) => {
    const query = afterMessageId ? `?afterMessageId=${encodeURIComponent(afterMessageId)}` : '';
    return http.get(`/api/v1/ai/sessions/${encodeURIComponent(sessionId)}/messages${query}`);
  },

  abortChat: (sessionId: string) =>
    http.post(`/api/v1/ai/sessions/${encodeURIComponent(sessionId)}/abort`),

  downloadWorkspaceFile: (sessionId: string, path: string) =>
    http.get(`/api/v1/ai/sessions/${encodeURIComponent(sessionId)}/workspace/files`, {
      params: { path },
      responseType: 'blob',
    }),

  listSessions: (params?: { offset?: number; limit?: number }) =>
    http.get('/api/v1/ai/sessions', { params }),

  countSessions: () =>
    http.get('/api/v1/ai/sessions/count'),

  sessionTasks: (sessionId: string) =>
    http.get(`/api/v1/ai/sessions/${encodeURIComponent(sessionId)}/tasks`),

  listTools: () => http.get('/api/v1/ai/tools').then(unwrapList<AiToolDefinition>),
  getTool: (toolId: string) => http.get(`/api/v1/ai/tools/${encodeURIComponent(toolId)}`),
  toolReferences: (toolId: string) => http.get(`/api/v1/ai/tools/${encodeURIComponent(toolId)}/references`),
  createTool: (data: Partial<AiToolDefinition>) => http.post('/api/v1/ai/tools', data),
  updateTool: (toolId: string, data: Partial<AiToolDefinition>) =>
    http.put(`/api/v1/ai/tools/${encodeURIComponent(toolId)}`, data),
  deleteTool: (toolId: string) => http.delete(`/api/v1/ai/tools/${encodeURIComponent(toolId)}`),
  enableTool: (toolId: string) => http.post(`/api/v1/ai/tools/${encodeURIComponent(toolId)}/enable`),
  disableTool: (toolId: string) => http.post(`/api/v1/ai/tools/${encodeURIComponent(toolId)}/disable`),
  testTool: (toolId: string, service?: string) =>
    http.post(`/api/v1/ai/tools/${encodeURIComponent(toolId)}/test`, service ? { service } : {}),

  listSkills: () => http.get('/api/v1/ai/skills').then(unwrapList<AiSkillDefinition>),
  getSkill: (skillId: string) => http.get(`/api/v1/ai/skills/${encodeURIComponent(skillId)}`),
  skillReferences: (skillId: string) => http.get(`/api/v1/ai/skills/${encodeURIComponent(skillId)}/references`),
  skillContent: (skillId: string) => http.get(`/api/v1/ai/skills/${encodeURIComponent(skillId)}/content`),
  skillFiles: (skillId: string) =>
    http.get(`/api/v1/ai/skills/${encodeURIComponent(skillId)}/files`).then(unwrapList<AiSkillFileEntry>),
  skillFileContent: (skillId: string, path: string) =>
    http.get(`/api/v1/ai/skills/${encodeURIComponent(skillId)}/files/content`, { params: { path } }),
  previewSkillImport: (file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return http.post('/api/v1/ai/skills/import/preview', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  importSkill: (file: File, data: { name: string; category?: string; description?: string; enabled?: boolean }) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('name', data.name);
    if (data.description) {
      formData.append('description', data.description);
    }
    if (data.category) {
      formData.append('category', data.category);
    }
    if (data.enabled !== undefined) {
      formData.append('enabled', String(data.enabled));
    }
    return http.post('/api/v1/ai/skills/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  createSkill: (data: Partial<AiSkillDefinition>) => http.post('/api/v1/ai/skills', data),
  updateSkill: (skillId: string, data: Partial<AiSkillDefinition>) =>
    http.put(`/api/v1/ai/skills/${encodeURIComponent(skillId)}`, data),
  deleteSkill: (skillId: string) => http.delete(`/api/v1/ai/skills/${encodeURIComponent(skillId)}`),
  validateSkill: (skillId: string, data?: Partial<AiSkillDefinition>) =>
    http.post(`/api/v1/ai/skills/${encodeURIComponent(skillId)}/validate`, data || {}),
  enableSkill: (skillId: string) => http.post(`/api/v1/ai/skills/${encodeURIComponent(skillId)}/enable`),
  disableSkill: (skillId: string) => http.post(`/api/v1/ai/skills/${encodeURIComponent(skillId)}/disable`),

  listExperts: () => http.get('/api/v1/ai/experts').then(unwrapList<AiExpertDefinition>),
  getExpert: (expertId: string) => http.get(`/api/v1/ai/experts/${encodeURIComponent(expertId)}`),
  createExpert: (data: Partial<AiExpertDefinition>) => http.post('/api/v1/ai/experts', data),
  updateExpert: (expertId: string, data: Partial<AiExpertDefinition>) =>
    http.put(`/api/v1/ai/experts/${encodeURIComponent(expertId)}`, data),
  deleteExpert: (expertId: string) => http.delete(`/api/v1/ai/experts/${encodeURIComponent(expertId)}`),
  enableExpert: (expertId: string) => http.post(`/api/v1/ai/experts/${encodeURIComponent(expertId)}/enable`),
  disableExpert: (expertId: string) => http.post(`/api/v1/ai/experts/${encodeURIComponent(expertId)}/disable`),
  reloadExpert: (expertId: string) => http.post(`/api/v1/ai/experts/${encodeURIComponent(expertId)}/reload`),
  debugExpert: (expertId: string, message: string) =>
    http.post(`/api/v1/ai/experts/${encodeURIComponent(expertId)}/debug`, { message }),
};
