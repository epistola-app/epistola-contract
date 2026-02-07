---
name: openapi
description: Help maintain the Epistola OpenAPI specification
---

# OpenAPI Spec Maintenance

This skill provides guidance for maintaining the Epistola API contract following established patterns and best practices.

## File Structure

The OpenAPI specification is modular:

```
epistola-contract/
├── epistola-api.yaml           # Main entry point - paths and schema refs
├── openapi.yaml                # Bundled output (generated, not edited)
├── redocly.yaml                # Validation configuration
└── spec/
    ├── paths/                  # Endpoint definitions
    │   ├── tenants.yaml
    │   ├── templates.yaml
    │   ├── themes.yaml
    │   ├── environments.yaml
    │   ├── variants.yaml
    │   ├── versions.yaml
    │   └── generation.yaml
    └── components/
        ├── schemas/            # Data models (DTOs, requests, responses)
        │   ├── tenants.yaml
        │   ├── templates.yaml
        │   ├── themes.yaml
        │   ├── environments.yaml
        │   ├── variants.yaml
        │   ├── versions.yaml
        │   └── generation.yaml
        └── responses/
            └── errors.yaml     # Shared error response schemas
```

### Which file to edit?

| Change Type | Edit This File |
|-------------|----------------|
| New endpoint | `spec/paths/<resource>.yaml` + register in `epistola-api.yaml` |
| New resource (CRUD) | Create files in both `spec/paths/` and `spec/components/schemas/` |
| New schema/DTO | `spec/components/schemas/<resource>.yaml` + register in `epistola-api.yaml` |
| Error responses | `spec/components/responses/errors.yaml` |
| API metadata | `epistola-api.yaml` (info section) |

## Adding New Endpoints

### 1. Create path definition in `spec/paths/<resource>.yaml`

Follow this pattern for a collection resource:

```yaml
# Resource collection (e.g., /widgets)
widgets:
  get:
    operationId: listWidgets       # camelCase, verb + noun
    summary: List all widgets       # Short imperative sentence
    description: Retrieve a paginated list of widgets.
    tags:
      - Widgets                     # PascalCase, plural
    parameters:
      - name: q
        in: query
        description: Search term
        schema:
          type: string
      - name: page
        in: query
        schema:
          type: integer
          default: 0
          minimum: 0
      - name: size
        in: query
        schema:
          type: integer
          default: 20
          minimum: 1
          maximum: 100
    responses:
      '200':
        description: List of widgets
        content:
          application/vnd.epistola.v1+json:
            schema:
              $ref: '../components/schemas/widgets.yaml#/WidgetListResponse'

  post:
    operationId: createWidget
    summary: Create a new widget
    tags:
      - Widgets
    requestBody:
      required: true
      content:
        application/vnd.epistola.v1+json:
          schema:
            $ref: '../components/schemas/widgets.yaml#/CreateWidgetRequest'
    responses:
      '201':
        description: Widget created successfully
        content:
          application/vnd.epistola.v1+json:
            schema:
              $ref: '../components/schemas/widgets.yaml#/WidgetDto'
      '400':
        description: Invalid request
        content:
          application/vnd.epistola.v1+json:
            schema:
              $ref: '../components/responses/errors.yaml#/ValidationErrorResponse'
```

### 2. Register the path in `epistola-api.yaml`

```yaml
paths:
  # Widgets
  /v1/tenants/{tenantId}/widgets:
    $ref: './spec/paths/widgets.yaml#/widgets'
  /v1/tenants/{tenantId}/widgets/{widgetId}:
    $ref: './spec/paths/widgets.yaml#/widgets-id'
```

### Path Naming Conventions

- Use kebab-case: `/document-templates`, not `/documentTemplates`
- Use plural nouns: `/widgets`, not `/widget`
- Nest under tenant: `/v1/tenants/{tenantId}/widgets`
- Action endpoints use verbs: `/widgets/{id}/publish`, `/widgets/{id}/archive`

### OperationId Conventions

| HTTP Method | Pattern | Example |
|-------------|---------|---------|
| GET (list) | `list{Resources}` | `listWidgets` |
| GET (single) | `get{Resource}` | `getWidget` |
| POST | `create{Resource}` | `createWidget` |
| PUT | `replace{Resource}` | `replaceWidget` |
| PATCH | `update{Resource}` | `updateWidget` |
| DELETE | `delete{Resource}` | `deleteWidget` |
| POST (action) | `{action}{Resource}` | `publishVersion`, `archiveVersion` |

## Adding New Schemas

### Create schema in `spec/components/schemas/<resource>.yaml`

Follow existing patterns:

```yaml
WidgetDto:
  type: object
  description: Represents a widget in the system
  required:
    - id
    - name
    - createdAt
  properties:
    id:
      type: string
      format: uuid
      description: Unique identifier
      example: 550e8400-e29b-41d4-a716-446655440000
    name:
      type: string
      description: Display name of the widget
      example: My Widget
    status:
      type: string
      enum: [draft, published, archived]
      description: Current status
    createdAt:
      type: string
      format: date-time
      description: Creation timestamp

CreateWidgetRequest:
  type: object
  description: Request body for creating a widget
  required:
    - name
  properties:
    name:
      type: string
      minLength: 1
      maxLength: 255
      description: Name for the new widget

UpdateWidgetRequest:
  type: object
  description: Request body for updating a widget
  properties:
    name:
      type: string
      minLength: 1
      maxLength: 255
      description: New name for the widget

WidgetListResponse:
  type: object
  description: Paginated list of widgets
  required:
    - items
    - page
    - size
    - totalElements
    - totalPages
  properties:
    items:
      type: array
      items:
        $ref: '#/WidgetDto'
    page:
      type: integer
    size:
      type: integer
    totalElements:
      type: integer
      format: int64
    totalPages:
      type: integer
```

### Register schemas in `epistola-api.yaml`

```yaml
components:
  schemas:
    # Widget schemas
    WidgetDto:
      $ref: './spec/components/schemas/widgets.yaml#/WidgetDto'
    CreateWidgetRequest:
      $ref: './spec/components/schemas/widgets.yaml#/CreateWidgetRequest'
    UpdateWidgetRequest:
      $ref: './spec/components/schemas/widgets.yaml#/UpdateWidgetRequest'
    WidgetListResponse:
      $ref: './spec/components/schemas/widgets.yaml#/WidgetListResponse'
```

### Schema Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Response DTO | `{Resource}Dto` | `WidgetDto` |
| Create request | `Create{Resource}Request` | `CreateWidgetRequest` |
| Update request | `Update{Resource}Request` | `UpdateWidgetRequest` |
| List response | `{Resource}ListResponse` | `WidgetListResponse` |
| Summary DTO | `{Resource}SummaryDto` | `WidgetSummaryDto` |

### Property Patterns

- **IDs**: Use `format: uuid` for auto-generated IDs, or string patterns for slugs
- **Dates**: Use `format: date-time` (ISO 8601)
- **Nullable**: Add `nullable: true` when a field can be null
- **Enums**: Use lowercase values: `[draft, published, archived]`
- **Examples**: Always include `example:` for clarity

## REST Conventions

### HTTP Methods

| Method | Purpose | Idempotent | Safe |
|--------|---------|------------|------|
| GET | Retrieve resource(s) | Yes | Yes |
| POST | Create resource or action | No | No |
| PUT | Replace entire resource | Yes | No |
| PATCH | Partial update | Yes | No |
| DELETE | Remove resource | Yes | No |

### Status Codes

| Code | When to Use |
|------|-------------|
| 200 | Success (GET, PATCH, PUT with body) |
| 201 | Resource created (POST) |
| 204 | Success with no body (DELETE, some POST actions) |
| 400 | Invalid request body (validation error) |
| 404 | Resource not found |
| 409 | Conflict (duplicate, state conflict) |
| 500 | Server error (internal error) |

### Error Responses

Use existing patterns from `spec/components/responses/errors.yaml`:

```yaml
# Simple error
'404':
  description: Widget not found
  content:
    application/vnd.epistola.v1+json:
      schema:
        $ref: '../components/responses/errors.yaml#/ErrorResponse'

# Validation error with field details
'400':
  description: Invalid request
  content:
    application/vnd.epistola.v1+json:
      schema:
        $ref: '../components/responses/errors.yaml#/ValidationErrorResponse'
```

## Versioning

This API uses header-based versioning:

```
Accept: application/vnd.epistola.v1+json
```

### When to bump versions

- **Patch (1.0.x → 1.0.y)**: Bug fixes, documentation updates, non-breaking additions
- **Minor (1.0 → 1.1)**: New optional fields, new endpoints, new query parameters
- **Major (v1 → v2)**: Breaking changes to existing endpoints or schemas

### Breaking Changes to Avoid

These require a major version bump:
- Removing or renaming endpoints
- Removing or renaming required fields
- Changing field types
- Adding new required fields
- Changing enum values
- Modifying validation (stricter constraints)

Use `make breaking` to check for breaking changes before merging.

## Common Tasks

### Add a new CRUD resource

1. Create `spec/paths/<resource>.yaml` with collection and item paths
2. Create `spec/components/schemas/<resource>.yaml` with DTO, requests, list response
3. Register paths in `epistola-api.yaml` under `paths:`
4. Register schemas in `epistola-api.yaml` under `components.schemas:`
5. Add a tag in `epistola-api.yaml` under `tags:`
6. Run `make lint` to validate
7. Run `make breaking` to check for breaking changes

### Add query parameters

Add to the `parameters` array in the path operation:

```yaml
parameters:
  - name: status
    in: query
    description: Filter by status
    schema:
      type: string
      enum: [draft, published, archived]
  - name: sortBy
    in: query
    schema:
      type: string
      enum: [name, createdAt]
      default: createdAt
```

### Add pagination

Follow the existing pattern (see `TenantListResponse`):

```yaml
# Parameters
- name: page
  in: query
  schema:
    type: integer
    default: 0
    minimum: 0
- name: size
  in: query
  schema:
    type: integer
    default: 20
    minimum: 1
    maximum: 100

# Response schema
items:
  type: array
  items:
    $ref: '#/ResourceDto'
page:
  type: integer
size:
  type: integer
totalElements:
  type: integer
  format: int64
totalPages:
  type: integer
```

### Reference existing schemas

Use relative `$ref` paths:

```yaml
# From paths file to schemas
$ref: '../components/schemas/tenants.yaml#/TenantDto'

# From paths file to errors
$ref: '../components/responses/errors.yaml#/ErrorResponse'

# Within same file
$ref: '#/WidgetDto'
```

## Validation Commands

Always validate after making changes:

```bash
# Validate the OpenAPI spec
make lint

# Check for breaking changes against main branch
make breaking

# Bundle into single file (for generators)
make bundle

# Start mock server for testing
make mock

# Validate implementation against spec
make validate-impl
```

## Checklist for New Endpoints

- [ ] Path follows REST conventions (kebab-case, plural nouns)
- [ ] OperationId is unique and follows naming pattern
- [ ] Tags reference an existing or new tag
- [ ] All required parameters have descriptions
- [ ] Request body schema is defined with validation
- [ ] Success response schema is defined
- [ ] Error responses (400, 404, etc.) are included
- [ ] Schemas are registered in `epistola-api.yaml`
- [ ] `make lint` passes
- [ ] `make breaking` shows no unexpected breaking changes