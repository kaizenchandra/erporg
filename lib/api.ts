let csrf: { token: string; headerName: string } | null = null;
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public fieldErrors: Record<string, string> = {},
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const mutation = options.method && options.method !== "GET";
  if (mutation && !csrf) {
    const response = await fetch("/api/csrf", {
      cache: "no-store",
      signal: AbortSignal.timeout(10000),
    });
    if (!response.ok)
      throw new ApiError(
        "Unable to establish a secure session. Please retry.",
        response.status,
      );
    csrf = await response.json();
  }
  const response = await fetch(path, {
    ...options,
    cache: "no-store",
    signal: options.signal ?? AbortSignal.timeout(10000),
    headers: {
      ...(options.body instanceof URLSearchParams
        ? {}
        : { "Content-Type": "application/json" }),
      ...(mutation && csrf ? { [csrf.headerName]: csrf.token } : {}),
      ...options.headers,
    },
  });
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) csrf = null;
    if (response.status === 403 && path !== "/api/login") {
      const session = await fetch("/api/v1/session", {
        cache: "no-store",
        signal: AbortSignal.timeout(10000),
      });
      if (session.status === 401 && typeof window !== "undefined")
        window.dispatchEvent(new Event("atlas-session-expired"));
    }
    if (
      response.status === 401 &&
      path !== "/api/login" &&
      typeof window !== "undefined"
    )
      window.dispatchEvent(new Event("atlas-session-expired"));
    let detail =
      response.status === 401
        ? "Your session has ended. Please sign in."
        : "We could not complete this action. Please try again.";
    const fieldErrors: Record<string, string> = {};
    try {
      const error = await response.json();
      if (typeof error.detail === "string") detail = error.detail;
      if (error.fieldErrors && typeof error.fieldErrors === "object") {
        for (const [key, value] of Object.entries(error.fieldErrors)) {
          if (typeof value === "string") fieldErrors[key] = value;
        }
      }
    } catch {
      /* Server may return an empty security response. */
    }
    throw new ApiError(detail, response.status, fieldErrors);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}
export function clearSession() {
  csrf = null;
}
export async function signIn(username: string, password: string) {
  await api<void>("/api/login", {
    method: "POST",
    body: new URLSearchParams({ username, password }),
  });
  clearSession();
}

export async function signOut(mode: string) {
  if (mode !== "oidc") {
    await api("/api/logout", { method: "POST" });
    clearSession();
    return;
  }
  const token = await api<{ token: string }>("/api/csrf");
  const form = document.createElement("form");
  form.method = "POST";
  form.action = "/api/logout";
  const input = document.createElement("input");
  input.type = "hidden";
  input.name = "_csrf";
  input.value = token.token;
  form.appendChild(input);
  document.body.appendChild(form);
  form.submit();
}
