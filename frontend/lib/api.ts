import type { AiConfig, AiConfigTest, AiConfigUpdate, Conversation, Message, Profile, Report, RunSubmit, RunView, User } from "./types";

export const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "/api/backend";
const RETRYABLE_STATUSES = new Set([502, 503, 504]);

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

// 仅用于 GET/HEAD 的短暂退避，避免首次打开时后端尚未健康而要求用户刷新。
function delay(ms: number) {
  return new Promise<void>(resolve => window.setTimeout(resolve, ms));
}

// 统一处理 Cookie、后端启动期重试和结构化错误消息。
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  // 带幂等键的消息提交可安全重试：即使第一次已到达后端，也只会返回同一个任务。
  const retryable = method === "GET" || method === "HEAD" || new Headers(init.headers).has("Idempotency-Key");
  const attempts = retryable ? 4 : 1;

  for (let attempt = 0; attempt < attempts; attempt++) {
    let response: Response;
    try {
      response = await fetch(`${API_BASE}${path}`, {
        ...init,
        credentials: "include",
        headers: {"Content-Type": "application/json", ...(init.headers ?? {})},
        cache: "no-store",
      });
    } catch {
      if (retryable && attempt < attempts - 1) {
        await delay(800 + attempt * 500);
        continue;
      }
      throw new ApiError(503, "服务正在启动，请稍候，页面会自动重试");
    }

    if (retryable && RETRYABLE_STATUSES.has(response.status) && attempt < attempts - 1) {
      await delay(800 + attempt * 500);
      continue;
    }

    if (!response.ok) {
      let message = `请求失败 (${response.status})`;
      try {
        const body = await response.json() as {message?: string};
        message = body.message ?? message;
      } catch {}
      throw new ApiError(response.status, message);
    }

    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }

  throw new ApiError(503, "服务正在启动，请稍候，页面会自动重试");
}

export const api = {
  me: () => request<User>("/auth/me"),
  anonymous: () => request<{user: User}>("/auth/anonymous", {method: "POST", body: "{}"}),
  login: (email: string, password: string) => request<{user: User}>("/auth/login", {method: "POST", body: JSON.stringify({email, password})}),
  register: (email: string, password: string, displayName: string) => request<{user: User}>("/auth/register", {method: "POST", body: JSON.stringify({email, password, displayName})}),
  logout: () => request<{ok: boolean}>("/auth/logout", {method: "POST", body: "{}"}),
  conversations: () => request<Conversation[]>("/conversations"),
  createConversation: (title = "新建志愿咨询") => request<Conversation>("/conversations", {method: "POST", body: JSON.stringify({title})}),
  conversation: (id: string) => request<{conversation: Conversation; messages: Message[]}>(`/conversations/${id}`),
  deleteConversation: (id: string) => request<{deleted: boolean}>(`/conversations/${id}`, {method: "DELETE"}),
  send: (id: string, content: string) => request<RunSubmit>(`/conversations/${id}/messages`, {method: "POST", headers: {"Idempotency-Key": crypto.randomUUID()}, body: JSON.stringify({content})}),
  run: (id: string) => request<RunView>(`/runs/${id}`),
  report: (id: string) => request<Report>(`/reports/${id}`),
  reports: () => request<Array<{id: string; title: string; datasetVersion: string; createdAt: string; compliancePassed: boolean}>>("/reports"),
  profile: () => request<Profile>("/profiles/current"),
  updateProfile: (patch: Partial<Profile>) => request<Profile>("/profiles/current", {method: "PATCH", body: JSON.stringify(patch)}),
  source: (id: string) => request<Record<string, unknown>>(`/sources/${id}`),
  coverage: (province: string, year: number) => request<Record<string, unknown>>(`/datasets/coverage?province=${encodeURIComponent(province)}&year=${year}`),
  dataQuality: () => request<Record<string, number>>("/admin/data-quality"),
  imports: () => request<Array<Record<string, unknown>>>("/admin/imports"),
  createImport: (body: Record<string, unknown>) => request<Record<string, unknown>>("/admin/imports", {method: "POST", body: JSON.stringify(body)}),
  aiConfig: () => request<AiConfig>("/admin/ai-config"),
  saveAiConfig: (body: AiConfigUpdate) => request<AiConfig>("/admin/ai-config", {method: "PUT", body: JSON.stringify(body)}),
  testAiConfig: () => request<AiConfigTest>("/admin/ai-config/test", {method: "POST", body: "{}"}),
};
