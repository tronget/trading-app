import AsyncStorage from '@react-native-async-storage/async-storage';
import { Platform } from 'react-native';

// 10.0.2.2 — хост из Android-эмулятора; для iOS-симулятора — localhost
export const BASE_URL =
  Platform.OS === 'android' ? 'http://10.0.2.2:8080' : 'http://localhost:8080';

const ACCESS_KEY = 'accessToken';
const REFRESH_KEY = 'refreshToken';

export async function saveTokens(access, refresh) {
  await AsyncStorage.setItem(ACCESS_KEY, access);
  if (refresh) await AsyncStorage.setItem(REFRESH_KEY, refresh);
}

export async function clearTokens() {
  await AsyncStorage.multiRemove([ACCESS_KEY, REFRESH_KEY]);
}

export async function getAccessToken() {
  return AsyncStorage.getItem(ACCESS_KEY);
}

async function tryRefresh() {
  const refreshToken = await AsyncStorage.getItem(REFRESH_KEY);
  if (!refreshToken) return null;
  const response = await fetch(`${BASE_URL}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  if (!response.ok) {
    await clearTokens();
    return null;
  }
  const { accessToken } = await response.json();
  await AsyncStorage.setItem(ACCESS_KEY, accessToken);
  return accessToken;
}

/** fetch с Bearer-токеном и авто-refresh при 401 (один повтор). */
export async function api(path, options = {}) {
  const doFetch = async (token) =>
    fetch(`${BASE_URL}${path}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...options.headers,
      },
    });

  let token = await getAccessToken();
  let response = await doFetch(token);
  if (response.status === 401 && token) {
    token = await tryRefresh();
    if (token) response = await doFetch(token);
  }

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.error || `HTTP ${response.status}`);
  }
  return body;
}

/** WebSocket-подписка на тики; возвращает функцию закрытия. */
export function openTicker(symbols, onTick) {
  let ws;
  let closed = false;

  const connect = async () => {
    const token = await getAccessToken();
    if (!token || closed) return;
    const wsUrl = BASE_URL.replace('http', 'ws');
    ws = new WebSocket(`${wsUrl}/ws?token=${token}`);
    ws.onopen = () => ws.send(JSON.stringify({ action: 'subscribe', symbols }));
    ws.onmessage = (event) => {
      try {
        onTick(JSON.parse(event.data));
      } catch {}
    };
    ws.onclose = () => {
      if (!closed) setTimeout(connect, 2000);
    };
  };

  connect();
  return () => {
    closed = true;
    ws?.close();
  };
}
