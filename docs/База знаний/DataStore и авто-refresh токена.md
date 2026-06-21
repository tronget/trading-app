# DataStore и авто-refresh токена

Две связанные клиентские темы: **где Android-приложение (`#1`) хранит JWT** и
**как оно само переживает протухший токен**, не отправляя пользователя на
повторный логин. Это та часть, что делает вход «бесшовным».

## DataStore: где лежит токен

[[JWT, токены и bcrypt|Токены]] (access + refresh) надо где-то сохранить, чтобы
они пережили перезапуск приложения. На Android для этого есть **Jetpack
DataStore** — современная замена старому `SharedPreferences`.

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

Access-токен живёт всего 15 минут (см. [[JWT, токены и bcrypt]]). Гонять
пользователя на логин каждые 15 минут — дикость. Поэтому клиент **сам**
обрабатывает протухание по схеме **«401 → refresh → повтор»**:

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

> [!warning] Почему повтор ровно один раз
> Повторяем запрос **единожды**. Если и после свежего токена снова `401` — значит
> дело не в протухании (refresh недействителен, пользователь забанен и т.п.), и
> зацикливаться нельзя. Иначе можно устроить бесконечный цикл refresh-запросов.

> [!note] HttpClient: один движок, разные роли
> Тот же Ktor-клиент (на движке OkHttp) обслуживает и REST, и
> [[WebSocket и fan-out котировок|WebSocket]] — плагин `WebSockets`
> устанавливается на том же `HttpClient`.

## Связанное

- [[JWT, токены и bcrypt]] — серверная половина и зачем два токена.
- [[Jetpack Compose, MVVM и StateFlow]] — как состояние «залогинен/нет» рулит UI.
- [[API Gateway и микросервисы]] — кто отдаёт 401 и обновляет токены.
