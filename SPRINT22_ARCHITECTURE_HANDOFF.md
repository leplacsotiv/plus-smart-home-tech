# Smart Home Technologies — архитектура Sprint 22

## Общая архитектура

Проект состоит из трёх групп Maven-модулей:

| Группа | Компоненты | Ответственность |
| --- | --- | --- |
| telemetry | collector, aggregator, analyzer, serialization | Приём gRPC-событий, Kafka, снимки датчиков и сценарии автоматизации |
| commerce | product-service | Категории и каталог, актуальные названия/цены, soft delete |
| commerce | inventory-service | Остатки, резервы и снятие резервов, конкурентные изменения |
| commerce | order-service | Оркестрация оформления и хранение исторических заказов |
| infra | config-server | Native YAML-конфигурация из `infra/config-repo` |
| infra | discovery-server | Eureka, реестр адресов экземпляров |
| infra | api-gateway | Внешние маршруты, LoadBalancer, Retry чтения, CORS, Basic Auth и роли |

Сервисы используют config-first: сначала Config Server `:8888`, затем адрес Eureka `:8761`
из общего `application.yml`. Рабочие параметры не размножены в локальных bootstrap-файлах.
Config Server видит подкаталоги `telemetry`, `commerce`, `infra` при запуске из корня проекта
или своего Maven-модуля. Native repository хранится в том же Git-репозитории, отдельно от Java-кода.

Каталог владеет `product_db`, склад — `inventory_db`, заказы — `order_db`.
Один PostgreSQL-сервер допустим, но межбазовых связей и прямого доступа к чужим таблицам нет.
Все business-сервисы запускаются на случайных портах; UUID в Eureka instance-id позволяет
зарегистрировать несколько экземпляров одного приложения.

## OpenFeign

В `commerce/order-service` подключены OpenFeign и LoadBalancer, приложение отмечено
`@EnableFeignClients`. В пакете `ru.yandex.practicum.order.feign` находятся:

- `ProductClient` (`product-service`) — `GET /api/products/{id}`;
- `InventoryClient` (`inventory-service`) — `POST /api/inventory/reserve` и `/release`;
- локальные `ProductDto`, `ReserveRequest`, `ReleaseRequest`, `ReserveResponse`.

Feign использует имена Eureka; в production-конфигурации клиентов нет фиксированных business-портов.
Java-код других commerce-модулей не импортируется. `FeignConfiguration` добавляет
`X-Source-Service: order-service`, запрещает повторы через `Retryer.NEVER_RETRY`, декодирует
ошибки и задаёт стабильные имена Circuit Breaker.

В `infra/config-repo/commerce/order-service.yml` установлены connect timeout 1 секунда,
read timeout 2 секунды, BASIC-логирование ProductClient и DEBUG для его Java-logger.
Повторы Spring Cloud LoadBalancer также отключены.

## Путь оформления заказа

1. `POST :8080/api/orders` проходит Basic Auth и проверку роли USER на Gateway.
2. Gateway выбирает экземпляр Order Service по `lb://order-service` и передаёт запрос.
3. `OrderController` валидирует `CreateOrderRequest`: имя, email, непустые позиции,
   положительные `productId` и количества. `price`/`productName` от клиента игнорируются.
4. `OrderOrchestrationService` группирует количества по ID в порядке первого появления.
   `Math.addExact` выявляет переполнение, преобразуемое в понятный отказ HTTP 422.
5. Каждый уникальный товар запрашивается ровно один раз. Сначала проверяется весь доступный
   каталог: корректность ID/названия/цены и `active=true`. Известная бизнес-ошибка прерывает оформление.
6. Если каталог доступен, склад резервирует одно суммарное количество для каждого ID.
7. `OrderDraft` сохраняет исходную детализацию строк, включая повторяющиеся ID,
   с названиями и ценами из каталога.
8. Только после HTTP-вызовов другой Spring-бин `OrderService.saveConfirmed` открывает
   локальную `@Transactional`-транзакцию. Сумма рассчитывается через `BigDecimal`.
9. Сохраняются `CustomerOrder` и `OrderItem` с cascade и установленными обеими сторонами связи.
   Успешный заказ получает `CONFIRMED`, контроллер возвращает HTTP 201.

Оркестратор не отмечен `@Transactional`: соединение с `order_db` не удерживается во время HTTP.
`productId`, название, количество и цена в OrderItem — исторический снимок без FK в каталог.
Последующие изменения товара не меняют ранее созданный заказ.

## Резервирование и компенсация

Inventory использует DB unique constraint по `product_id`, check constraints и `@Version`.
Доступное количество вычисляется как `quantity - reservedQuantity`.
Изменения не могут нарушать `0 <= reservedQuantity <= quantity`.

`InventoryController.release` принимает валидируемый `ReleaseRequest`.
`InventoryService.release` в локальной транзакции находит запись, снимает резерв и flush-ит изменения.
Сущность проверяет достаточность резерва. Неправильное количество — 400, отсутствие записи — 404,
optimistic locking — 409. Успех возвращает тот же DTO, что reserve.

Оркестратор хранит список подтверждённых резервов в памяти одного запроса.
При следующем отказе или неудачном сохранении заказа он снимает их в обратном порядке.
Проверяется и ответ release. Ошибка компенсации логируется, исходная ошибка сохраняется.
При технической деградации после частичного резерва известные резервы также компенсируются
перед сохранением pending-заказа.

## Circuit Breaker и деградация

OpenFeign обёрнут Spring Cloud CircuitBreaker / Resilience4j. Внешний конфиг задаёт
окно 5 вызовов, минимум 5, порог 50%, ожидание в OPEN 10 секунд, 3 пробных HALF_OPEN-вызова.
TimeLimiter ограничивает вызов 3 секундами; HTTP-таймауты Feign короче.
Используются фактические имена `ProductClientgetProductById`, `InventoryClientreserveStock`,
`InventoryClientreleaseStock`, формируемые `CircuitBreakerNameResolver`.

`ProductClientFallbackFactory` и `InventoryClientFallbackFactory` получают исходную причину.
Они не возвращают фиктивный успех или null. `RemoteCallFailures` различает:

- успех — реальные данные зависимости;
- бизнес-отказ — `OrderProcessingException`, HTTP 422 от Order Service;
- техническую недоступность — `DependencyUnavailableException`, допустим pending-сценарий.

HTTP 4xx декодируются в бизнес-исключение до Circuit Breaker и исключены из его failure metrics.
Каталог 404, склад 404/409, неактивный товар и неправильные данные не превращаются в pending.
5xx, ошибки соединения, timeout и открытая цепь относятся к технической недоступности.
Внутренние Feign-детали остаются в диагностике и не выдаются клиенту в HTTP-ошибке.

Если каталог недоступен, позиции сохраняются с `Товар #ID (ожидает проверки)` и нулевой ценой;
доступные данные других товаров сохраняются. Резервирование в этом случае не начинается.
При недоступном складе известный снимок каталога сохраняется, но статус — только
`PENDING_CONFIRMATION`. В `statusDetails` указана необходимость проверки и возможная
неопределённость последней складской операции. Pending-заказ сохраняется отдельным
транзакционным методом `OrderService.savePending`.

TimeLimiter или read timeout может завершить ожидание раньше обработки запроса складом.
Прерывание потока не доказывает откат удалённого POST. Оркестратор не повторяет операцию
и не снимает неподтверждённый резерв вслепую: без reservationId он не может установить
принадлежность и фактический результат такой операции.

## Gateway, Retry и CORS

Bootstrap `infra/api-gateway/src/main/resources/application.yml` содержит имя и Config Server import.
Маршруты и рабочие параметры находятся в `infra/config-repo/infra/api-gateway.yml`.
Gateway использует WebFlux/Netty на `:8080`, регистрируется как `API-GATEWAY`.
Discovery locator отключён: автоматической публикации произвольных Eureka-сервисов нет.

| Префиксы | URI | Маршруты |
| --- | --- | --- |
| `/api/products/**`, `/api/categories/**` | `lb://product-service` | product-service-read/write |
| `/api/inventory/**` | `lb://inventory-service` | inventory-service-read/write |
| `/api/orders/**` | `lb://order-service` | order-service-read/write |

Read-маршруты имеют `Method=GET` и order `-10`. Только у них Retry: два повтора для
502/503/504, backoff от 100 до 500 ms. Default retry по всей серии 5xx отключён;
500 не повторяется. Write-маршруты не имеют Retry. Feign также не повторяет writes.
LoadBalancer выбирает адреса из Eureka; production-код не содержит временных debug-endpoint.

CORS разрешает origins `http://localhost:3000`, `:5173`, `:8443`, методы
GET/POST/PUT/PATCH/DELETE/OPTIONS, любые заголовки, credentials, exposed `Location`, max-age 3600.
Preflight обрабатывается даже без обычного подходящего маршрута.
Текущие business-сервисы не публикуют Swagger/OpenAPI HTTP-endpoint, поэтому фиктивные
публичные Swagger-маршруты на Gateway не добавлены.

## Security

`SecurityConfiguration.securityWebFilterChain` использует реактивный `ServerHttpSecurity`
и `@EnableWebFluxSecurity`. Servlet security-конфигурации в Gateway нет.
Пользователи задаются `app.security.users` во внешнем конфиге и связываются с
`SecurityProperties`. `MapReactiveUserDetailsService` содержит учебных пользователей:
ivan / ivan — USER, anna / anna — USER + ADMIN. В памяти пароли кодируются BCrypt.

HTTP Basic проверяется на каждом запросе. `NoOpServerSecurityContextRepository`
и `NoOpServerRequestCache` исключают сохранение аутентификации в HTTP-сессии.
Login form, logout и CSRF отключены для API. UI имеет собственную учебную форму выбора
пользователя, а к Gateway передаёт Basic Auth при каждом backend-вызове.

| Доступ | Маршруты |
| --- | --- |
| Public | OPTIONS, GET каталог/категории/остатки |
| USER | POST заказы, GET заказ по ID, GET заказы по email |
| ADMIN | GET полный список заказов; POST/PUT/PATCH/DELETE каталог/категории/склад |
| Deny | Всё остальное, в том числе неизвестный API даже для ADMIN |

Правило ADMIN для точного `/api/orders` проверяется раньше USER-правила для `/{id}`.
Отсутствующая или неправильная аутентификация даёт 401; недостаточная роль — 403.
CORS source берётся из Gateway `GlobalCorsProperties` и передаётся Security,
поэтому preflight проходит до проверки аутентификации.

## Проверки

Полный `mvn -B clean verify`: 16 модулей, 70 тестов, без failures/errors/skips.

Новые и обновлённые наборы:

- `InventoryReleaseTest` — успешное снятие, превышение резерва, отсутствие записи, невалидное количество.
- `OrderServiceAcceptanceTest` — новый контракт с управляемыми mock-зависимостями, CONFIRMED,
  сумма, JPA-сохранение/чтение связанных позиций, снимки, поиск по email и валидация.
- `OrderControllerContractTest` — обязательность количества, игнорирование клиентских цен/названий,
  делегирование оркестратору и сохранение GET-контрактов.
- `OrderOrchestrationServiceTest` — группировка дублей, переполнение, active=false, 404/409,
  обратная компенсация, ошибка сохранения, логирование сбоя release без подмены исходной ошибки,
  pending при недоступных зависимостях.
- `FeignCircuitBreakerTest` — реальные Feign-прокси и HTTP backend, внешний YAML,
  технический заголовок, OPEN после пяти сбоев, pending на открытой цепи, отсутствие write-retry,
  прохождение 404/409 через fallback и исключение бизнес-ошибок из failure metrics.
- `GatewayRoutingTest` — реальный HTTP downstream через LoadBalancer, пути и приоритеты,
  Retry GET, один вызов POST, отсутствие retry на 500 и CORS.
- `GatewaySecurityTest` — матрица ролей через WebTestClient, наблюдаемый вызов downstream
  только для разрешённых запросов, 401/403, preflight и stateless-аутентификация.

Product/Inventory acceptance tests Sprint 21 сохранены. Старый Order acceptance test
обновлён под предусмотренный Sprint 22 контракт; искусственного legacy-режима нет.

Локальная проверка 02.11.2026 выполнена с PostgreSQL 16.1, Config Server, Eureka,
двумя экземплярами Product Service, Inventory, Order и Gateway:

- проверены Config Server endpoints для всех клиентов, включая Gateway;
- в Eureka зарегистрированы API-GATEWAY и три business-сервиса, два Product instance-id различны;
- 12 GET-запросов распределились 6/6 между двумя Product Service по их access logs;
- HTTP smoke подтвердил CONFIRMED, актуальные цены, суммарный резерв дублей и неизменность снимка;
- после 409 следующей позиции первый резерв снят; 404 и active=false дали 422;
- остановка Inventory сохранила pending с известной ценой, остановка каталога — pending с ценой 0;
- проверены Basic Auth, USER/ADMIN, неизвестный маршрут и анонимный preflight;
- Web UI проверен в профиле gateway до Security и gateway-secured после Security:
  каталог, корзина, подтверждённое оформление USER, административный список заказов.

Повторяемый базовый HTTP-сценарий находится в `scripts/smoke-commerce.sh`.
Тестовые процессы и временный PostgreSQL-контейнер после проверки останавливаются.

## Навигация по коду

| Относительный путь | Роль |
| --- | --- |
| `pom.xml` | Java/Boot/Cloud dependency management и модули |
| `infra/config-server/src/main/resources/application.yml` | Native search locations |
| `infra/config-repo/application.yml` | Общая Eureka-конфигурация и instance-id |
| `infra/discovery-server/src/main/java/ru/yandex/practicum/discoveryserver/DiscoveryServerApplication.java` | Eureka Server |
| `infra/config-repo/commerce/order-service.yml` | БД, Feign, CB и TimeLimiter |
| `infra/config-repo/infra/api-gateway.yml` | Маршруты, Retry, CORS и учебные пользователи |
| `infra/api-gateway/src/main/java/ru/yandex/practicum/gateway/security/SecurityConfiguration.java` | Реактивная security chain |
| `infra/api-gateway/src/main/java/ru/yandex/practicum/gateway/security/SecurityProperties.java` | Внешние пользователи |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/OrderServiceApp.java` | Boot и Feign scan |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/controller/OrderController.java` | REST-контракт заказов |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/service/OrderOrchestrationService.java` | Удалённые вызовы и компенсация |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/service/OrderDraft.java` | Проверенный снимок для сохранения |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/service/OrderService.java` | Локальные транзакции и чтение |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/entity/CustomerOrder.java` | Aggregate root и статусы |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/entity/OrderItem.java` | Исторические позиции |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/feign/ProductClient.java` | HTTP API каталога |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/feign/InventoryClient.java` | HTTP API склада |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/feign/FeignConfiguration.java` | Заголовок, ошибки, no-retry, CB names |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/feign/RemoteCallFailures.java` | Классификация причин fallback |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/feign/ProductClientFallbackFactory.java` | Fallback каталога |
| `commerce/order-service/src/main/java/ru/yandex/practicum/order/feign/InventoryClientFallbackFactory.java` | Fallback склада |
| `commerce/inventory-service/src/main/java/ru/yandex/practicum/inventory/service/InventoryService.java` | Транзакции reserve/release |
| `commerce/inventory-service/src/main/java/ru/yandex/practicum/inventory/entity/Inventory.java` | Инварианты и optimistic locking |
| `commerce/product-service/src/main/java/ru/yandex/practicum/product/service/ProductService.java` | Каталог, поиск и PATCH |
| `scripts/smoke-commerce.sh` | Интеграционный HTTP smoke |

## Архитектурные границы учебной реализации

- Потеря ответа reserve/release не позволяет достоверно определить результат удалённой операции.
- Компенсация — best effort без durable-журнала, retry worker и восстановления после падения процесса.
- Полноценной Saga и идемпотентных резервов с reservationId пока нет.
- Pending-заказы требуют последующей проверки; автоматического подтверждения/reconciliation нет.
- Basic Auth и конфигурация учебных пользователей подходят для локальной демонстрации.
  Для production нужны TLS, управление секретами и полноценная модель идентификации.
- USER может запросить заказ по ID или email без проверки принадлежности: object-level authorization
  этим учебным спринтом не реализована.
- Без сетевой изоляции прямой доступ к business-порту обходит Gateway. Доступ сервисов
  в production нужно ограничивать на уровне сети; сами business-сервисы Security здесь не дублируют.
- `ddl-auto=update` оставлен для учебного запуска; production потребует управляемых миграций.

Эти границы отделяют текущую учебную модель от отказоустойчивой production-системы.
