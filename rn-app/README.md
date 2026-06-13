# React Native App (#2, опционально)

Кросс-платформенный клиент (Expo) с тем же функционалом, что и нативное
Android-приложение, через тот же публичный API Gateway:

- авторизация/регистрация (JWT в AsyncStorage, авто-refresh при 401);
- рынок с живыми ценами по WebSocket;
- карточка инструмента: график (react-native-svg) + ордер Market/Limit;
- портфель с P&L (автообновление), история ордеров/сделок с отменой.

## Запуск

```bash
npm install
npx expo start          # затем 'a' — Android-эмулятор, 'i' — iOS-симулятор
```

Бэкенд должен быть поднят (`docker compose up` в `deploy/`).
Адрес Gateway: `10.0.2.2:8080` на Android-эмуляторе, `localhost:8080` на iOS
(см. `src/api.js`).
