import type { NextRequest } from "next/server";

export const dynamic = "force-dynamic";

const backend = process.env.BACKEND_INTERNAL_URL ?? "http://localhost:8080";

// 对幂等请求做短暂重试，覆盖容器刚启动但后端尚未接管端口的时间窗。
async function fetchUpstream(target: URL, request: NextRequest, headers: Headers, body?: ArrayBuffer) {
  // 咨询提交自带幂等键，代理层遇到瞬时断连时也可以安全重试。
  const retryable = request.method === "GET" || request.method === "HEAD" || request.headers.has("idempotency-key");
  const attempts = retryable ? 4 : 1;

  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      return await fetch(target, {
        method: request.method,
        headers,
        body,
        cache: "no-store",
        redirect: "manual",
      });
    } catch (error) {
      if (attempt === attempts - 1) throw error;
      await new Promise(resolve => setTimeout(resolve, 600 + attempt * 500));
    }
  }

  throw new Error("后端服务连接超时");
}

// 代理层将连接失败转换为 503 JSON，避免 Next.js 展示必须手动 Reload 的错误页。
async function proxy(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const target = new URL(`/api/v1/${path.map(encodeURIComponent).join("/")}`, backend);
  target.search = request.nextUrl.search;

  const headers = new Headers();
  for (const name of ["accept", "content-type", "cookie", "idempotency-key", "last-event-id", "x-trace-id"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }

  const hasBody = !["GET", "HEAD"].includes(request.method);
  const body = hasBody ? await request.arrayBuffer() : undefined;
  let upstream: Response;
  try {
    upstream = await fetchUpstream(target, request, headers, body);
  } catch {
    return Response.json(
      {code: "BACKEND_STARTING", message: "后端服务正在启动，页面会自动重试，请稍候"},
      {status: 503, headers: {"cache-control": "no-store", "retry-after": "2"}},
    );
  }

  const responseHeaders = new Headers();
  for (const name of ["cache-control", "content-type", "set-cookie", "x-trace-id"]) {
    const value = upstream.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }

  if (upstream.headers.get("content-type")?.includes("text/event-stream")) {
    responseHeaders.set("cache-control", "no-cache, no-transform");
    responseHeaders.set("x-accel-buffering", "no");
  }
  return new Response(upstream.body, {status: upstream.status, headers: responseHeaders});
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
