# Menu Service

Manages the FoodChain menu catalogue — categories and items. Exposes a public branch-menu endpoint for the customer-facing app and admin endpoints for back-office management.

**Base URL (via gateway):** `http://<gateway-host>/api`  
**Direct port:** `8082`

---

## Authentication

All write operations require a `HEAD_OFFICE_ADMIN` JWT passed as:

```
Authorization: Bearer <token>
```

Read operations (GET) are **public** — no token required.

---

## All Endpoints at a Glance

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/v1/menu/branch/{branchId}` | No | Full active menu for customer app |
| GET | `/v1/menu/categories` | No | List active categories |
| POST | `/v1/menu/categories` | Admin | Create a category |
| PUT | `/v1/menu/categories/{id}` | Admin | Update a category |
| GET | `/v1/menu/items` | No | Paginated list of items |
| GET | `/v1/menu/items/{id}` | No | Single item details |
| POST | `/v1/menu/items` | Admin | Create an item |
| PUT | `/v1/menu/items/{id}` | Admin | Update an item |
| PATCH | `/v1/menu/items/{id}/activate` | Admin | Set item active |
| PATCH | `/v1/menu/items/{id}/deactivate` | Admin | Set item inactive |
| PATCH | `/v1/menu/items/{id}/toggle` | Admin | Flip active flag |
| DELETE | `/v1/menu/items/{id}` | Admin | Delete item + S3 image |
| POST | `/v1/menu/items/{id}/image` | Admin | Upload item image to S3 |
| DELETE | `/v1/menu/items/{id}/image` | Admin | Remove item image |
| POST | `/v1/menu/suggestions` | No | AI-powered food suggestions |

---

## Branch Menu — Customer App

### GET `/v1/menu/branch/{branchId}`

Returns all **active** menu items for a branch. This is the primary endpoint for the customer-facing order screen.

**Path param:** `branchId` — branch UUID

**Response `200`:**
```json
[
  {
    "id": "3427b024-3584-4d08-91b9-1b175cc3e7f1",
    "name": "Hummus Classic",
    "description": "Creamy chickpea dip with olive oil and paprika",
    "price": 22.0,
    "category": "Starters",
    "available": true,
    "isActive": true,
    "imageUrl": "https://foodchain-images-bucket.s3.us-east-1.amazonaws.com/menu-items/uuid-filename.png",
    "image": "https://foodchain-images-bucket.s3.us-east-1.amazonaws.com/menu-items/uuid-filename.png"
  }
]
```

> `imageUrl` and `image` are the same value — both fields exist for compatibility. Use either one.

---

## Categories

### GET `/v1/menu/categories`

Returns all active categories sorted by `displayOrder`. Cached in Redis (10 min TTL).

**Query params:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `namesOnly` | boolean | `false` | If `true`, returns a plain string array instead of full objects |

**Response `200` — full objects:**
```json
[
  {
    "id": "c0f7be77-9589-4ca8-8d01-605cc2a87fc8",
    "name": "Starters",
    "displayOrder": 1,
    "active": true
  },
  {
    "id": "ab3a5f8a-6bb2-4546-a2a6-4420312c1a50",
    "name": "Mains",
    "displayOrder": 2,
    "active": true
  }
]
```

**Response `200` — `namesOnly=true`:**
```json
["Starters", "Mains", "Grills", "Soups", "Salads", "Drinks", "Desserts"]
```

---

### POST `/v1/menu/categories` — Admin only

**Request body:**
```json
{
  "name": "Chef Specials",
  "displayOrder": 11
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `name` | string | Yes | Must be unique across all categories |
| `displayOrder` | integer | Yes | Lower number appears first |

**Response `201`:**
```json
{
  "id": "generated-uuid",
  "name": "Chef Specials",
  "displayOrder": 11,
  "active": true
}
```

---

### PUT `/v1/menu/categories/{id}` — Admin only

Partial update — only include fields you want to change.

**Request body:**
```json
{
  "name": "Signature Dishes",
  "displayOrder": 3
}
```

**Response `200`:** same structure as `CategoryResponse` above.

---

## Menu Items

### GET `/v1/menu/items`

Paginated list of menu items. Results sorted alphabetically by name.

**Query params:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `categoryId` | string (UUID) | — | Filter to one category |
| `active` | boolean | — | `true` = visible items only, `false` = hidden items only |
| `page` | integer | `0` | Zero-based page index |
| `size` | integer | `20` | Items per page |

**Response `200`:**
```json
{
  "content": [
    {
      "id": "3427b024-3584-4d08-91b9-1b175cc3e7f1",
      "name": "Hummus Classic",
      "categoryId": "c0f7be77-9589-4ca8-8d01-605cc2a87fc8",
      "categoryName": "Starters",
      "basePrice": 22.00,
      "active": true
    }
  ],
  "totalElements": 85,
  "totalPages": 5,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false
}
```

---

### GET `/v1/menu/items/{id}`

Full item details. Cached in Redis (10 min TTL).

**Response `200`:**
```json
{
  "id": "3427b024-3584-4d08-91b9-1b175cc3e7f1",
  "name": "Hummus Classic",
  "description": "Creamy chickpea dip with olive oil and paprika",
  "categoryId": "c0f7be77-9589-4ca8-8d01-605cc2a87fc8",
  "categoryName": "Starters",
  "basePrice": 22.00,
  "imageUrl": "https://foodchain-images-bucket.s3.us-east-1.amazonaws.com/menu-items/uuid-filename.png",
  "active": true,
  "createdAt": "2026-05-09T08:38:53.922526",
  "updatedAt": "2026-05-09T08:38:53.922611"
}
```

**Response `404`:** item not found.

---

### POST `/v1/menu/items` — Admin only

**Request body:**
```json
{
  "name": "Lamb Ouzi",
  "description": "Slow-roasted lamb on fragrant rice with nuts",
  "categoryId": "ab3a5f8a-6bb2-4546-a2a6-4420312c1a50",
  "basePrice": 120.00,
  "imageUrl": null
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `name` | string | Yes | |
| `description` | string | No | |
| `categoryId` | string (UUID) | No | Category must exist if provided |
| `basePrice` | decimal | Yes | |
| `imageUrl` | string | No | Prefer using the image upload endpoint |

**Response `201`:** full `MenuItemResponse` (same as GET by ID).

---

### PUT `/v1/menu/items/{id}` — Admin only

Partial update — only include fields to change.

**Request body:**
```json
{
  "basePrice": 125.00,
  "description": "Updated description here"
}
```

**Response `200`:** full `MenuItemResponse`.

---

### PATCH `/v1/menu/items/{id}/activate` — Admin only

Sets `active = true`. No request body.

**Response `200`:** full `MenuItemResponse` with `"active": true`.

---

### PATCH `/v1/menu/items/{id}/deactivate` — Admin only

Sets `active = false`. No request body.

**Response `200`:** full `MenuItemResponse` with `"active": false`.

---

### PATCH `/v1/menu/items/{id}/toggle` — Admin only

Flips the `active` flag. No request body.

**Response `200`:** full `MenuItemResponse` with the new `active` value.

---

### DELETE `/v1/menu/items/{id}` — Admin only

Permanently deletes the item and removes its S3 image if present.

**Response `204`:** no body.

---

## Image Upload

### POST `/v1/menu/items/{id}/image` — Admin only

Uploads or replaces the image for a menu item. Stores the file in S3 and saves the public URL on the item.

**Content-Type:** `multipart/form-data`  
**Form field name:** `image`  
**Max file size:** 10 MB

**Example (JavaScript fetch):**
```js
const form = new FormData();
form.append('image', file); // file is a File object

const res = await fetch(`/api/v1/menu/items/${itemId}/image`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token}` },
  body: form,
});
const item = await res.json();
console.log(item.imageUrl); // full S3 public URL
```

**Response `200`:** full `MenuItemResponse` with the updated `imageUrl`:
```json
{
  "id": "...",
  "name": "Hummus Classic",
  "imageUrl": "https://foodchain-images-bucket.s3.us-east-1.amazonaws.com/menu-items/9cc1e752-test.png",
  "active": true
}
```

> If the item already has an image, the old one is deleted from S3 before uploading the new one.

---

### DELETE `/v1/menu/items/{id}/image` — Admin only

Removes the image from S3 and clears `imageUrl` on the item.

**Response `200`:** full `MenuItemResponse` with `"imageUrl": null`.

---

## AI Food Suggestions

### POST `/v1/menu/suggestions`

Conversational AI endpoint. On the first call with sparse context the AI returns clarifying questions. Once it has enough info it returns item suggestions with reasons.

**Request body:**
```json
{
  "branchId": "00e03993-6425-4703-a38f-cc661ceedf44",
  "branchName": "Downtown Branch",
  "budget": 150.00,
  "mealType": "lunch",
  "appetite": "normal",
  "dietaryPreferences": ["vegetarian"],
  "peopleCount": 3,
  "fulfillmentType": "DINE_IN",
  "limit": 5
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `branchId` | string | Yes | |
| `branchName` | string | No | |
| `budget` | decimal | No | |
| `mealType` | string | No | `"breakfast"` / `"lunch"` / `"dinner"` |
| `appetite` | string | No | `"light"` / `"normal"` / `"hearty"` |
| `dietaryPreferences` | string[] | No | e.g. `["vegetarian", "gluten-free"]` |
| `peopleCount` | integer | No | |
| `fulfillmentType` | string | No | `DINE_IN` / `TAKEAWAY` / `DELIVERY` |
| `limit` | integer | No | Max suggestions (default 5) |

**Response `200` — AI needs more info:**
```json
{
  "message": "I'd love to help! Could you tell me a bit more?",
  "readyForSuggestions": false,
  "questions": ["How many people are you ordering for?", "Any dietary restrictions?"],
  "suggestions": [],
  "estimatedTotalCost": null
}
```

**Response `200` — with suggestions:**
```json
{
  "message": "Here are my top picks for your group!",
  "readyForSuggestions": true,
  "questions": [],
  "suggestions": [
    {
      "menuItemId": "item-uuid",
      "menuItemName": "Lamb Ouzi",
      "price": 120.00,
      "reason": "A crowd-pleasing centrepiece, perfect for 3 people",
      "branchId": "branch-uuid",
      "branchName": "Downtown Branch",
      "estimatedTotalCost": 150.00,
      "optionalAddOns": ["Add Fattoush salad", "Add Lemon Mint drinks"]
    }
  ],
  "estimatedTotalCost": 150.00
}
```

---

## Error Responses

All errors follow this structure:

```json
{
  "success": false,
  "status": 403,
  "message": "Only HEAD_OFFICE_ADMIN can perform this action",
  "error": "Forbidden",
  "path": "/api/v1/menu/items",
  "timestamp": "2026-05-13T10:00:00Z"
}
```

| Status | When |
|--------|------|
| `400` | Validation failed — check `fields` map in response body |
| `403` | Missing or wrong role |
| `404` | Item or category not found |
| `500` | Unexpected server error |
