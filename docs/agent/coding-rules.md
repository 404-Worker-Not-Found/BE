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

## Error Handling Rules

- Use clear exception messages.
- Define domain-specific error-code enums by implementing `global/exception/ErrorCode`.
- Define custom exceptions for expected business errors.
- Do not overuse try/catch blocks that only hide or ignore errors.
- Do not swallow errors silently.
