import http from '@/utils/axios';

export interface LlmEnvVarItem {
  key: string;
  value: string;
}

export interface LlmModelView {
  modelId: string;
  displayName: string;
  contextWindow?: number | null;
  maxOutputTokens?: number | null;
  envVars?: LlmEnvVarItem[];
  defaultModel: boolean;
}

export interface LlmProviderView {
  providerCode: string;
  displayName: string;
  baseUrl: string;
  defaultModel: string;
  apiType: string;
  modelCount: number;
  enabled: boolean;
  configured: boolean;
  defaultProvider: boolean;
}

export interface LlmProviderDetailView {
  providerCode: string;
  providerName: string;
  apiType: string;
  baseUrl: string;
  configured: boolean;
  apiKey?: string | null;
  apiKeyMasked?: boolean;
  enabled: boolean;
  defaultProvider: boolean;
  models: LlmModelView[];
}

export interface SaveLlmProviderRequest {
  providerName?: string;
  apiType?: string;
  baseUrl?: string;
  apiKey?: string;
  enabled?: boolean;
  defaultProvider?: boolean;
  defaultModelId?: string;
  models?: LlmModelView[];
}

export interface CreateLlmProviderRequest {
  providerCode: string;
  displayName: string;
  baseUrl: string;
  defaultModel?: string;
  apiKey?: string;
  enabled?: boolean;
}

export interface TestLlmProviderRequest {
  baseUrl: string;
  apiKey?: string;
  apiType?: string;
  modelId?: string;
  providerCode?: string;
}

export interface TestLlmProviderResult {
  ok: boolean;
  message: string;
}

export function listLlmProviders () {
  return http.request<LlmProviderView[]>({
    url: '/api/v1/config/ai/providers',
    method: 'get',
  });
}

export function getLlmProviderDetail (providerCode: string) {
  return http.request<LlmProviderDetailView>({
    url: `/api/v1/config/ai/providers/${encodeURIComponent(providerCode)}/detail`,
    method: 'get',
  });
}

export function saveLlmProviderDetail (providerCode: string, data: SaveLlmProviderRequest) {
  return http.request<LlmProviderView>({
    url: `/api/v1/config/ai/providers/${encodeURIComponent(providerCode)}/detail`,
    method: 'put',
    data,
  });
}

export function createLlmProvider (data: CreateLlmProviderRequest) {
  return http.request<LlmProviderView>({
    url: '/api/v1/config/ai/providers',
    method: 'post',
    data,
  });
}

export function setDefaultLlmProvider (providerCode: string) {
  return http.request<LlmProviderView>({
    url: `/api/v1/config/ai/providers/${encodeURIComponent(providerCode)}/default`,
    method: 'put',
  });
}

export function testLlmProvider (data: TestLlmProviderRequest) {
  return http.request<TestLlmProviderResult>({
    url: '/api/v1/config/ai/providers/test',
    method: 'post',
    data,
  });
}

export function getLlmStatus () {
  return http.request<{ ready: boolean; maskProviderApiKey?: boolean }>({
    url: '/api/v1/config/ai/status',
    method: 'get',
  });
}
