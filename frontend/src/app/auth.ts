import { ApiError, apiRequest } from "./api";

export type UserRole = "ADMIN" | "BOM" | "FM" | "FS" | "CUSTOMER";

export type AuthUser = {
  id: string;
  name: string;
  email: string;
  role: UserRole;
};

export type AuthSession = {
  user: AuthUser;
  issuedAt: string;
  token: string;
};

export type LoginResult = { ok: true; session: AuthSession } | { ok: false; message: string };

export interface AuthGateway {
  getSession(): AuthSession | null;
  login(email: string, password: string): Promise<LoginResult>;
  restoreSession(): Promise<AuthSession | null>;
  logout(): void;
  subscribe(listener: (session: AuthSession | null) => void): () => void;
}

const SESSION_STORAGE_KEY = "kho-moc-auth-session";
const LEGACY_DEMO_SESSION_STORAGE_KEY = "kho-moc-demo-session";
const LEGACY_ACCESS_TOKEN_STORAGE_KEY = "kho-moc-access-token";
const SESSION_RESTORE_TIMEOUT_MS = 8000;

type LoginResponse = {
  accountId: number;
  email: string;
  token: string;
};

type CurrentUserResponse = {
  email: string;
  role: UserRole;
};

function readSession(): AuthSession | null {
  try {
    window.localStorage.removeItem(LEGACY_DEMO_SESSION_STORAGE_KEY);
    const stored = window.localStorage.getItem(SESSION_STORAGE_KEY);
    return stored ? (JSON.parse(stored) as AuthSession) : null;
  } catch {
    return null;
  }
}

function writeSession(session: AuthSession | null) {
  try {
    if (session) {
      window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
      window.localStorage.removeItem(LEGACY_ACCESS_TOKEN_STORAGE_KEY);
    } else {
      window.localStorage.removeItem(SESSION_STORAGE_KEY);
      window.localStorage.removeItem(LEGACY_ACCESS_TOKEN_STORAGE_KEY);
    }
  } catch {
    // The current tab can still use the in-memory session when storage is unavailable.
  }
}

const listeners = new Set<(session: AuthSession | null) => void>();

export const authGateway: AuthGateway = {
  getSession: readSession,
  async login(email, password) {
    try {
      const loginResponse = await apiRequest<LoginResponse>("/api/auth/login", {
        method: "POST",
        body: { email, password },
      });
      writeSession(null);
      listeners.forEach((listener) => listener(null));
      const currentUser = await apiRequest<CurrentUserResponse>("/api/auth/me", {
        accessToken: loginResponse.token,
      });
      const session: AuthSession = {
        user: {
          id: String(loginResponse.accountId),
          name: currentUser.email.split("@")[0],
          email: currentUser.email,
          role: currentUser.role,
        },
        issuedAt: new Date().toISOString(),
        token: loginResponse.token,
      };

      writeSession(session);
      listeners.forEach((listener) => listener(session));
      return { ok: true, session };
    } catch (error) {
      return {
        ok: false,
        message:
          error instanceof Error && "status" in error
            ? error.message
            : "Không thể kết nối tới máy chủ.",
      };
    }
  },
  async restoreSession() {
    const cachedSession = readSession();
    const accessToken = cachedSession?.token;
    if (!accessToken) {
      if (cachedSession) writeSession(null);
      return null;
    }

    const abortController = new AbortController();
    const timeoutId = window.setTimeout(() => abortController.abort(), SESSION_RESTORE_TIMEOUT_MS);

    try {
      const currentUser = await apiRequest<CurrentUserResponse>("/api/auth/me", {
        accessToken,
        dispatchAuthExpired: false,
        signal: abortController.signal,
      });
      const session: AuthSession = {
        user: {
          id: cachedSession?.user.id ?? currentUser.email,
          name: currentUser.email.split("@")[0],
          email: currentUser.email,
          role: currentUser.role,
        },
        issuedAt: cachedSession?.issuedAt ?? new Date().toISOString(),
        token: accessToken,
      };

      writeSession(session);
      listeners.forEach((listener) => listener(session));
      return session;
    } catch (error) {
      // An aborted restore (backend slower than SESSION_RESTORE_TIMEOUT_MS) is treated as an
      // unverifiable session and cleared. Trade-off: a transient slow start forces re-login
      // even though the token may still be valid — accepted to keep startup bounded.
      if (
        abortController.signal.aborted ||
        (error instanceof ApiError && (error.status === 401 || error.status === 403))
      ) {
        writeSession(null);
        listeners.forEach((listener) => listener(null));
        return null;
      }
      throw error;
    } finally {
      window.clearTimeout(timeoutId);
    }
  },
  logout() {
    writeSession(null);
    listeners.forEach((listener) => listener(null));
  },
  subscribe(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};

export type AuthActionResult =
  { ok: true; message: string } | { ok: false; status: number; message: string };

async function authPost(path: string, body: unknown): Promise<AuthActionResult> {
  try {
    await apiRequest<null>(path, { method: "POST", body });
    return { ok: true, message: "" };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ok: false, status: error.status, message: error.message };
    }
    return {
      ok: false,
      status: 0,
      message: "Không thể kết nối tới máy chủ.",
    };
  }
}

export const authApi = {
  register(email: string, password: string) {
    return authPost("/api/auth/register", { email, password });
  },
  verifyEmail(email: string, code: string) {
    return authPost("/api/auth/verify-email", { email, code });
  },
  resendVerification(email: string) {
    return authPost("/api/auth/resend-verification", { email });
  },
};
