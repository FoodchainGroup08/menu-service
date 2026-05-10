# menu-service

Spring Boot microservice that manages the FoodChain menu catalogue — categories and individual menu items. It serves both an internal admin API (used by office staff) and a public-facing branch menu endpoint (used by the frontend ordering flow).

## Port

| Environment | Port |
|-------------|------|
| Local / Docker | **8082** |

Base path: `/api` (configured via `server.servlet.context-path`)

Full local base URL: `http://localhost:8082/api`

---

## Endpoints

### Branch Menu (Frontend)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/menu/branch/{branchId}` | None | Returns all **active** menu items for the branch |

**Important notes on field names:**
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

---

### Menu Items

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/menu/items` | None | Paginated list — filter by `categoryId`, `active`, `page`, `size` |
| `GET` | `/menu/items/{id}` | None | Full item detail (Redis cached, 10 min TTL) |
| `POST` | `/menu/items` | `OFFICE_ADMIN` | Create a new menu item |
| `PUT` | `/menu/items/{id}` | `OFFICE_ADMIN` | Partial update (only supplied fields are changed) |
| `PATCH` | `/menu/items/{id}/activate` | `OFFICE_ADMIN` | Set `active = true` |
| `PATCH` | `/menu/items/{id}/deactivate` | `OFFICE_ADMIN` | Set `active = false` |
| `PATCH` | `/menu/items/{id}/toggle` | `OFFICE_ADMIN` | Flip active state |
| `DELETE` | `/menu/items/{id}` | `OFFICE_ADMIN` | Permanently delete item |

Auth is enforced via the `X-User-Role` header (injected by the API Gateway). Write operations require `OFFICE_ADMIN`.

**MenuItemResponse fields:** `id`, `name`, `description`, `categoryId`, `categoryName`, `basePrice`, `imageUrl`, `active`, `createdAt`, `updatedAt`

---

### Menu Categories

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/menu/categories` | None | List active categories (full objects, Redis cached) |
| `GET` | `/menu/categories?namesOnly=true` | None | List active category names as `string[]` (frontend-compatible) |
| `POST` | `/menu/categories` | `OFFICE_ADMIN` | Create a category |
| `PUT` | `/menu/categories/{id}` | `OFFICE_ADMIN` | Partial update |

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
