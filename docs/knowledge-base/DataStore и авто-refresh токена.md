# DataStore и авто-refresh токена

Android-приложение хранит токены в **DataStore**. Когда access-токен истекает,
клиент пытается обновить его автоматически.

## DataStore: где лежит токен

Access- и refresh-токены нужно сохранить между запусками приложения. На Android
для этого используется **Jetpack DataStore**.

Чем DataStore лучше `SharedPreferences`:

- **асинхронный, на корутинах/Flow** — не блокирует UI-поток при чтении с диска
  (старый `SharedPreferences.getString` мог дёрнуть диск прямо в главном потоке);
- отдаёт значения как **`Flow`** — можно реактивно следить за изменениями;
- безопаснее при конкурентной записи.

```kotlin
private val Context.dataStore by preferencesDataStore(name = "auth")

class TokenStore(private val context: Context) {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")

    val accessToken: Flow<String?> = context.dataStore.data.map { it[accessKey] }
    suspend fun save(access: String, refresh: String? = null) { … }
    suspend fun clear() { … }
}
```

`accessToken` как `Flow` удобен для навигации: пропал токен (`clear()` после
неудачного refresh) — приложение реактивно перекидывает на экран логина.

## Авто-refresh: протухший токен чинится сам

Access-токен живёт 15 минут. Чтобы пользователь не входил заново, клиент
обрабатывает истечение по схеме **«401 → refresh → повтор»**:

1. К каждому запросу подставляется `Authorization: Bearer <access>`.
2. Сервер ответил `401 Unauthorized` → access протух.
3. Клиент дёргает `/auth/refresh` с refresh-токеном, получает новый access.
4. **Повторяет исходный запрос** с новым токеном — пользователь ничего не заметил.
5. Если и refresh не сработал → чистим токены → на экран логина.

В коде это перехватчик Ktor-клиента (`HttpSend`):

```kotlin
http.plugin(HttpSend).intercept { request ->
    request.header(Authorization, "Bearer ${tokens.current().access}")
    val call = execute(request)
    if (call.response.status != Unauthorized) return@intercept call

    val refreshed = tryRefresh() ?: return@intercept call   // обновляем access
    request.headers.remove(Authorization)
    request.header(Authorization, "Bearer $refreshed")
    execute(request)                                         // повтор один раз
}
```

Запрос повторяется только один раз. Если после обновления токена сервер снова
вернул `401`, токены очищаются. Иначе клиент может попасть в бесконечный цикл
refresh-запросов.

Один экземпляр Ktor `HttpClient` используется и для REST, и для
[[WebSocket и fan-out котировок|WebSocket]].
