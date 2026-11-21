import { useQueryClient } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, refreshSession, setAccessToken, setSessionEndedHandler } from "@/lib/api";
import type { Me, TokenResponse } from "@/types/api";

type AuthState = {
  user: Me | null;
  ready: boolean;
  login: (email: string, password: string) => Promise<Me>;
  logout: () => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  can: (permission: string) => boolean;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [ready, setReady] = useState(false);
  const queryClient = useQueryClient();

  const endSession = useCallback(() => {
    setAccessToken(null);
    setUser(null);
    queryClient.clear();
  }, [queryClient]);

  useEffect(() => {
    setSessionEndedHandler(endSession);
    // Restore a session from the refresh cookie on page load.
    refreshSession()
      .then(async (ok) => {
        if (ok) setUser(await api.get<Me>("/auth/me"));
      })
      .catch(() => setUser(null))
      .finally(() => setReady(true));
  }, [endSession]);

  const login = useCallback(async (email: string, password: string) => {
    const res = await api.post<TokenResponse>("/auth/login", { email, password });
    setAccessToken(res.accessToken);
    setUser(res.user);
    return res.user;
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.post("/auth/logout");
    } finally {
      endSession();
    }
  }, [endSession]);

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    const res = await api.post<TokenResponse>("/auth/change-password", { currentPassword, newPassword });
    setAccessToken(res.accessToken);
    setUser(res.user);
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      user,
      ready,
      login,
      logout,
      changePassword,
      can: (p: string) => !!user?.permissions.includes(p),
    }),
    [user, ready, login, logout, changePassword],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
