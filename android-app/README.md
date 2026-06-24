# Android App (#1)

Нативный биржевой терминал: Kotlin + Jetpack Compose, MVVM
(ViewModel + StateFlow), Ktor-client, DataStore.

## Экраны

- **Авторизация** — вход/регистрация, JWT хранится в DataStore,
  авто-refresh access-токена при 401 (HttpSend-перехватчик).
- **Рынок** — watchlist с ценой и изменением за день; цены обновляются
  в реальном времени по WebSocket (подписка на все символы).
- **Карточка инструмента** — линейный график (Canvas, без сторонних
  библиотек) с интервалами 1m/5m/15m/1h, живая цена, форма ордера
  Market/Limit с кнопками «Купить»/«Продать» (идемпотентность через
  `clientOrderId` = UUID).
- **Портфель** — стоимость, свободный кэш, позиции с P&L (автообновление 5 с).
- **История** — вкладки «Ордера» (с отменой активных лимиток) и «Сделки».

## Архитектура

```
ui/* (Compose) → ViewModel (StateFlow) → Repository (интерфейсы) → ApiClient (Ktor)
                                              ↑ фейки в юнит-тестах
di/AppContainer — ручной DI (один экземпляр в Application)
```

- `ApiClient` — OkHttp-движок, Bearer-токен на каждый запрос, при 401
  делает `/auth/refresh` и повторяет запрос один раз.
- `MarketRepositoryImpl.ticker()` — WebSocket-Flow с автопереподключением.
- Money-поля приходят от API строками (точность NUMERIC) и
  отображаются как есть.

## Сборка и запуск

```bash
# адрес Gateway зашит в BuildConfig.GATEWAY_URL (10.0.2.2 — хост из эмулятора)
./gradlew assembleDebug          # APK в app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # юнит-тесты ViewModel (фейковые репозитории)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Бэкенд должен быть поднят (`docker compose up` в `deploy/` или вручную:
PostgreSQL, Redis, ClickHouse, quotes-go, data-service, gateway).





cd /Users/moyshavondervals/StudioProjects/trading-app/rn-app                                                                                                         
npm install                                                                                                                                                          
npx expo start --android