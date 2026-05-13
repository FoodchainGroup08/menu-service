# menu-service

Spring Boot microservice that manages the FoodChain menu catalogue — categories and individual menu items. It serves both an internal admin API (used by office staff) and a public-facing branch menu endpoint (used by the frontend ordering flow).

## Port

| Environment | Port |
|-------------|------|
| Local / Docker | **8082** |

Base path: `/api` (configured via `server.servlet.context-path`)

Full local base URL: `http://localhost:8082/api`

All endpoints are versioned under `/v1/`.

---

## Endpoints

### Branch Menu (Frontend)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/v1/menu/branch/{branchId}` | None | Returns all **active** menu items for the branch |
| `POST` | `/v1/menu/suggestions` | None | AI-powered food suggestions based on user preferences |

**Important notes on field names (`GET /v1/menu/branch/{branchId}`):**
- Response uses `price` (not `basePrice`), `available` (not `active`), and `category` as a plain string name (not an object or ID).
- The `image` field is an alias for `imageUrl` — both are present in the response for compatibility.
- Since the menu catalogue is shared across branches, `branchId` is accepted and logged but currently returns all active items. Branch-specific filtering is reserved for a future release.

**Response shape (array):**
```json
[
  {
    "id": "3fa85f64-...",
    "name": "Jollof Rice",
    "description": "Nigerian classic slow-cooked rice",
    "price": 1500.00,
    "category": "Mains",
    "available": true,
    "isActive": true,
    "imageUrl": "https://cdn.example.com/jollof.jpg",
    "image":    "https://cdn.example.com/jollof.jpg"
  }
]
```

**`POST /v1/menu/suggestions`** — guides the caller through preference questions (budget, meal type, appetite, dietary preference, party size, fulfilment type) then returns ranked menu items.

---

### Menu Items

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/v1/menu/items` | None | Paginated list — filter by `categoryId`, `active`, `page`, `size` |
| `GET` | `/v1/menu/items/{id}` | None | Full item detail (Redis cached, 10 min TTL) |
| `POST` | `/v1/menu/items` | `HEAD_OFFICE_ADMIN` | Create a new menu item |
| `PUT` | `/v1/menu/items/{id}` | `HEAD_OFFICE_ADMIN` | Partial update (only supplied fields are changed) |
| `PATCH` | `/v1/menu/items/{id}/activate` | `HEAD_OFFICE_ADMIN` | Set `active = true` |
| `PATCH` | `/v1/menu/items/{id}/deactivate` | `HEAD_OFFICE_ADMIN` | Set `active = false` |
| `PATCH` | `/v1/menu/items/{id}/toggle` | `HEAD_OFFICE_ADMIN` | Flip active state |
| `DELETE` | `/v1/menu/items/{id}` | `HEAD_OFFICE_ADMIN` | Permanently delete item |
| `POST` | `/v1/menu/items/{id}/image` | `HEAD_OFFICE_ADMIN` | Upload or replace the item's image (multipart/form-data, field: `image`) |
| `DELETE` | `/v1/menu/items/{id}/image` | `HEAD_OFFICE_ADMIN` | Remove the item's image from S3 and clear the `imageUrl` field |

Auth is enforced via the `X-User-Role` header (injected by the API Gateway). Write operations require `HEAD_OFFICE_ADMIN`.

**MenuItemResponse fields:** `id`, `name`, `description`, `categoryId`, `categoryName`, `basePrice`, `imageUrl`, `active`, `createdAt`, `updatedAt`

---

### Menu Categories

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/v1/menu/categories` | None | List active categories (full objects, Redis cached) |
| `GET` | `/v1/menu/categories?namesOnly=true` | None | List active category names as `string[]` (frontend-compatible) |
| `POST` | `/v1/menu/categories` | `HEAD_OFFICE_ADMIN` | Create a category |
| `PUT` | `/v1/menu/categories/{id}` | `HEAD_OFFICE_ADMIN` | Partial update |

**CategoryResponse fields:** `id`, `name`, `displayOrder`, `active`

---

## Caching (Redis)

| Cache key | Content | TTL |
|-----------|---------|-----|
| `menu:item:{id}` | `MenuItemResponse` JSON | 10 min |
| `menu:categories` | `List<CategoryResponse>` JSON | 10 min |

Cache is evicted on every write operation (create / update / delete / activate / deactivate). Redis failures are caught and logged — the service degrades gracefully to direct DB reads.

---

## Kafka Events

Topic: **`menu-item-events`**

| Event type | Trigger |
|------------|---------|
| `CREATED` | Item created |
| `UPDATED` | Item updated |
| `ACTIVATED` | Item activated |
| `DEACTIVATED` | Item deactivated |
| `DELETED` | Item deleted |

Event payload:
```json
{
  "menuItemId": "3fa85f64-...",
  "name": "Jollof Rice",
  "basePrice": 1500.00,
  "active": true,
  "eventType": "CREATED"
}
```

Kafka publish failures are caught and logged — they do not roll back the database transaction.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `RDS_ENDPOINT` | `localhost` | MySQL host |
| `RDS_PORT` | `3306` | MySQL port |
| `RDS_USERNAME` | `root` | MySQL username |
| `RDS_PASSWORD` | `password` | MySQL password |
| `REDIS_HOST` | `localhost` | Redis host |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka broker(s) |

Config server: `http://localhost:8888` (optional — service starts without it).

---

## Running Locally

### Prerequisites
- Java 17+
- MySQL database `menu_db`
- Redis (default port 6379)
- Kafka broker (optional — publish errors are swallowed)

### With Maven
```bash
cd menu-service
./mvnw spring-boot:run
```

### With Docker Compose (full stack)
```bash
cd foodchain-deployment
docker-compose up menu-service
```

### Tests
```bash
./mvnw test
```

Tests use an in-memory H2 database and Mockito mocks for Redis and Kafka — no external services required.

---

## Swagger UI

Available at: `http://localhost:8082/api/swagger-ui.html`

API docs JSON: `http://localhost:8082/api/v3/api-docs`

All controller paths in Swagger are prefixed `/v1/` (e.g. `/v1/menu/items`, `/v1/menu/categories`, `/v1/menu/branch/{branchId}`).
