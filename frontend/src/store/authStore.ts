import { create } from 'zustand';

interface AuthState {
  isAuthenticated: boolean;
  accessToken: string;
  refreshToken: string;
  setAuthInfo: (isAuthenticated: boolean, accessToken: string, refreshToken: string) => void;
  setIsAuthenticated: (isAuthenticated: boolean) => void;
  setAccessToken: (accessToken: string) => void;
  setRefreshToken: (refreshToken: string) => void;
  clearAuth: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  isAuthenticated: false,
  accessToken: '',
  refreshToken: '',
  setAuthInfo: (isAuthenticated, accessToken, refreshToken) =>
    set({ isAuthenticated, accessToken, refreshToken }),
  setIsAuthenticated: (isAuthenticated) =>
    set((state) => ({ ...state, isAuthenticated })),
  setAccessToken: (accessToken) =>
    set((state) => ({ ...state, accessToken })),
  setRefreshToken: (refreshToken) =>
    set((state) => ({ ...state, refreshToken })),
  clearAuth: () =>
    set({ isAuthenticated: false, accessToken: '', refreshToken: '' }),
}));
