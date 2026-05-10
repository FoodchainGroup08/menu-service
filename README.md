# menu-service

Spring Boot microservice that manages the FoodChain menu catalogue — categories and menu items. It exposes an internal admin API (catalogue CRUD) and a branch-scoped menu feed used by the ordering flow.

## Port and base URL

| Item | Value |
|------|--------|
| **HTTP port** | **8082** (`application.yml`; override with `SERVER_PORT`) |
| **Context path** | `/api` |

Direct base URL: `http://localhost:8082/api`

### Via API Gateway (recommended for apps)

Use **`http://localhost:8080`** as the host. REST paths are prefixed with **`/api/v1/...`** (for example `GET /api/v1/menu/items`). The gateway requires **`Authorization: Bearer`** for almost every route; the only anonymous **GET** APIs are the paginated branch list and **`GET /api/v1/branches/nearby`**. Menu reads therefore require a JWT when called through the gateway, even though this service does not enforce Spring Security on read endpoints itself.

---

## Endpoints

Paths below are relative to **`/api`** on this service. Clients using the gateway call **`http://localhost:8080/api/v1/menu/...`** (see **api-gateway** route table).

### Branch menu (ordering UI)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/menu/branch/{branchId}` | Active menu items for ordering (simplified DTO: `price`, `category` name string, `available`, etc.) |

The catalogue is shared across branches; `branchId` is accepted for future filtering but currently returns all active items.

---

### Menu items — `/v1/menu/items`

| Method | Path | Admin (`X-User-Role`) | Description |
|--------|------|----------------------|-------------|
| `GET` | `/v1/menu/items` | — | Paginated list (`categoryId`, `active`, `page`, `size`) |
| `GET` | `/v1/menu/items/{id}` | — | Detail (Redis cached, 10 min TTL) |
| `POST` | `/v1/menu/items` | **HEAD_OFFICE_ADMIN**, **OFFICE_ADMIN**, or **Admin** (case-insensitive) | Create item |
| `PUT` | `/v1/menu/items/{id}` | same | Partial update |
| `PATCH` | `/v1/menu/items/{id}/activate` | same | Set active |
| `PATCH` | `/v1/menu/items/{id}/deactivate` | same | Set inactive |
| `PATCH` | `/v1/menu/items/{id}/toggle` | same | Toggle active |
| `DELETE` | `/v1/menu/items/{id}` | same | Delete item (**200** with JSON body on success) |

Write operations read **`X-User-Role`** (and **`X-User-Id`**) injected by the API gateway after JWT validation. When calling this service **directly** (Swagger on `:8082`), supply those headers manually for admin actions.

**JSON body:** Create/update requests accept **`price`** in JSON (OpenAPI name); **`basePrice`** is accepted as an alias (`@JsonAlias`).

**Duplicate names:** Creating or renaming an item to a name that already exists (case-insensitive) returns **409 Conflict**.

**Response fields (`MenuItemResponse`):** includes `basePrice`, `categoryId`, `categoryName`, etc.

---

### Categories — `/v1/menu/categories`

| Method | Path | Admin | Description |
|--------|------|-------|-------------|
| `GET` | `/v1/menu/categories` | — | Active categories (cached) |
| `GET` | `/v1/menu/categories?namesOnly=true` | — | Category names as `string[]` |
| `POST` | `/v1/menu/categories` | admin roles above | Create |
| `PUT` | `/v1/menu/categories/{id}` | admin roles above | Partial update |

---

## Caching (Redis)

| Key | TTL |
|-----|-----|
| `menu:item:{id}` | 10 min |
| `menu:categories` | 10 min |

Writes invalidate relevant cache entries. Redis failures are logged; reads fall back to the database.

---

## Kafka

**Topic:** `menu-item-events` — published on create/update/activate/deactivate/delete (`CREATED`, `UPDATED`, …). Payload uses `basePrice` and `eventType`. Publish failures are logged and do not roll back DB commits.

---

## Configuration highlights

| Variable | Typical default | Purpose |
|----------|-----------------|--------|
| `RDS_ENDPOINT`, `RDS_PORT`, `RDS_USERNAME`, `RDS_PASSWORD` | localhost / 3306 / root | MySQL (`menu_db`) |
| `REDIS_HOST` | localhost | Redis |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` in local `application.yml` | Kafka producers |

Optional Config Server: `optional:configserver:http://localhost:8888`.

---

## Running locally

Prerequisites: Java 17+, MySQL `menu_db`, Redis, Kafka (optional for dev if you tolerate publish errors).

```bash
cd menu-service
./mvnw spring-boot:run
```

Tests use H2 and mocks — `./mvnw test`.

Docker Compose (from `foodchain-deployment`): `docker-compose up menu-service`.

---

## Swagger

Direct: `http://localhost:8082/api/swagger-ui.html`  
OpenAPI JSON: `http://localhost:8082/api/v3/api-docs`
