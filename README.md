# Smart Home Technologies

Java 21 / Spring Boot 3.3.2, Maven multi-module проект.

`telemetry` принимает события устройств по gRPC, публикует их в Kafka, строит снимки датчиков
и выполняет сценарии автоматизации. `commerce` содержит каталог, склад и оформление заказов.
Инфраструктура Spring Cloud вынесена в `infra`.

## Сборка

```bash
mvn -B clean verify
```

Тесты используют H2, управляемые HTTP-backend и mock-клиенты; PostgreSQL, Eureka и Kafka
для Maven-проверок запускать не требуется.

## Локальный запуск commerce

Из корня репозитория:

```bash
docker compose up -d postgres
java -jar infra/config-server/target/config-server-1.0-SNAPSHOT.jar
java -jar infra/discovery-server/target/discovery-server-1.0-SNAPSHOT.jar
java -jar commerce/product-service/target/product-service-1.0-SNAPSHOT.jar
java -jar commerce/inventory-service/target/inventory-service-1.0-SNAPSHOT.jar
java -jar commerce/order-service/target/order-service-1.0-SNAPSHOT.jar
java -jar infra/api-gateway/target/api-gateway-1.0-SNAPSHOT.jar
```

Каждый `java -jar` запускается в отдельном терминале. Config Server — `8888`, Eureka — `8761`,
Gateway — `8080`, business-сервисы используют случайные порты. После запуска дождитесь регистрации
сервисов и обновления клиентских кешей Eureka/LoadBalancer: это может занять около минуты.

Используются отдельные базы `product_db`, `inventory_db`, `order_db`. Их создаёт
`docker/init-databases.sql` при первой инициализации PostgreSQL. Для существующего контейнера
с ранее созданными данными создание новых БД нужно проверить отдельно.

Учебные пользователи Gateway: `ivan / ivan` (USER), `anna / anna` (USER, ADMIN).
Они заданы во внешнем конфиге и предназначены только для локальной учебной среды.

```bash
curl http://localhost:8080/api/products
curl -u ivan:ivan -H 'Content-Type: application/json' \
  -d '{"customerName":"Иван","customerEmail":"ivan@example.com","items":[{"productId":1,"quantity":2}]}' \
  http://localhost:8080/api/orders
bash scripts/smoke-commerce.sh
```

Smoke-скрипт создаёт собственные тестовые категории, товары, остатки и заказы.
Его следует запускать на локальной тестовой базе. Он проверяет маршруты, роли, снимки товаров,
суммарное резервирование, компенсацию, ошибки 404/409 и CORS.

Готовый Web UI, если JAR доступен:

```bash
java -jar commerce/web-ui/web-ui.jar --spring.profiles.active=gateway-secured
```

UI доступен на `http://localhost:8443`. Профиль `gateway` позволяет проверить публичный каталог,
но для защищённых операций итоговой версии нужен `gateway-secured`.

Архитектура Sprint 22 и навигация по коду: [SPRINT22_ARCHITECTURE_HANDOFF.md](SPRINT22_ARCHITECTURE_HANDOFF.md).
Описание telemetry: [telemetry/README.md](telemetry/README.md).
