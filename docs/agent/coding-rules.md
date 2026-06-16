# Coding Rules

This file records AI-facing coding rules for backend implementation work.

## Function Rules

- Functions should have one clear responsibility.
- Keep functions within 30 lines when possible.
- Prefer readability over strict line limits for simple mapping code or test setup code.
- Minimize nested control-flow depth.
- Extract long conditions into methods with meaningful names.

## Naming Rules

- Use names with clear meaning.
- Avoid unnecessary abbreviations.
- Use `is` or `has` prefixes for boolean variables and methods.
- Use the `By` keyword only in Repository method names.
- Avoid `By` in Service and business method names.
- Query methods in `FindService` should start with `find`.
- Query methods in `ApplicationService` should start with `get`.

Example:

```java
userRepository.findByEmail(email);
accountFindService.findAccount(email);
accountApplicationService.getAccount(accountId);
```

## Entity Construction Rules

- Use the builder pattern for JPA entity construction.
- Prefer Lombok `@Builder` on a controlled constructor over public all-args constructors.
- Keep JPA no-args constructors protected.
- Do not introduce static factory methods such as `Entity.create(...)` when the project can use `Entity.builder()...build()`.
- Keep bidirectional relationship helper methods explicit, such as `registerOwnerProfile(...)` or `addAvailableTime(...)`.

## Controller and API Documentation Rules

- API endpoint paths should stay under `/api/**`.
- Internal service-to-service endpoints should also stay under `/api/**`, such as `/api/{domain}/internal/**`.
- Do not introduce top-level API paths such as `/internal/**` for controllers.
- Controllers should contain Spring MVC mapping annotations and request handling only.
- Put Swagger/OpenAPI documentation annotations in separate `ControllerDocs` interfaces.
- Controller classes should implement their matching `ControllerDocs` interface.
- If Swagger/OpenAPI annotations are added, Swagger UI must be available and verifiable, not only compile annotations.
- For Spring Boot 4 projects, prefer Springdoc 3.x starter dependencies that expose OpenAPI docs and Swagger UI.
- Permit Swagger/OpenAPI documentation paths in security configuration when security is enabled.
- Add or update verification that confirms `/v3/api-docs` and Swagger UI are accessible when Swagger UI is introduced.

## Error Handling Rules

- Use clear exception messages.
- Define domain-specific error-code enums by implementing `global/exception/ErrorCode`.
- Define custom exceptions for expected business errors.
- Do not overuse try/catch blocks that only hide or ignore errors.
- Do not swallow errors silently.
