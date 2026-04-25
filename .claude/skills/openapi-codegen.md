---
name: openapi-codegen
description: Regenerate code from OpenAPI spec
---
BFF types: same API shape as Business Service (proxied transparently)
Frontend: `cd frontend && npx openapi-typescript-codegen --input ../contracts/openapi.yaml --output src/services/api-client --client axios`
