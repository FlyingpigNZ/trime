# Repository Rules for AI Assistants

These rules are binding when working in this repository. If a data definition
cannot be interpreted by the code, do not patch the code around the data —
fix the data definition or the schema first.

## Rules

1. **Never use specific literals in code to fix a data definition problem.**
   - Do not hard-code a particular value, key name, or path just to make a
     broken definition load.
   - Data problems belong in the data/schema layer, not in Kotlin/Python logic.

2. **No magic numbers.**
   - Do not introduce unexplained numeric literals (e.g. `0`, `0x00`,
     `4278190080`, `30`) into validation or parsing logic.
   - If a number has meaning, give it a named constant or express it through
     the schema/definition.

3. **If a definition cannot be interpreted by the code, go back to the drawing
   board.**
   - Re-examine the YAML schema and the actual definition data.
   - Fix the schema, the component files, or the generated assets.
   - Only after the data is correct should the code consume it.

## Working style

- Validate definitions early and fail loudly with clear messages.
- Prefer schema-first validation over defensive code.
- Keep generated assets consistent with their source schemas.
- Do not push unverified or unfinished work to remote.
