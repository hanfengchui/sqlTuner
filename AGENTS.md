# SQL Tuning Assistant Guidelines

## Runtime

- Backend must compile and run on Java 8. Do not introduce Java 9+ APIs or Spring Boot 3 dependencies.
- Frontend is built on development or CI machines with Node 20.19+ and emitted as static files.
- Production servers only need JDK 8, database connectivity, and access to the Qwen or internal LLM gateway.
- Do not store API keys in source files. Use environment variables or external configuration.

## Project Layout

- `backend/`: Java 8 Spring Boot API and static hosting.
- `frontend/`: React/Vite static frontend.
- `docs/`: design, deployment, and operating notes.

Run commands inside the relevant module directory.

## Logging

Java service logs must make the key path reconstructable: input, downstream call, decision, and result.

- Use direct field-style logs:
  - `methodName param 入参: {}`
  - `methodName request 请求: {}`
  - `methodName response 响应: {}`
  - `methodName result 结果: {}`
  - `methodName error 异常: {}`
- Use `info` for normal paths, `warn` for business rejection or unexpected external responses, and `error` for exceptions.
- Controller entrypoints, Harness nodes, rule hits, skill version choice, LLM calls, JSON parsing, and task state transitions must log key fields.
- Production logs must not include raw API keys or full raw SQL. Log task id, user id, SQL hash/length, and sanitized SQL snippets when needed.
- Log exceptions with the business key and full stack: `log.error("methodName taskId: {} error 异常: {}", taskId, e.getMessage(), e)`.

## Comments

- Prefer short Chinese comments for business reasons and non-obvious constraints.
- Comment why the system avoids production DB execution, why SQL is sanitized before model calls, why deterministic rule facts are preserved, and why tasks bind to skill versions.
- Do not add empty comments for obvious assignments, getters, setters, or simple branches.

## Frontend Structure

- Pages must remain focused: Login, Chat Workspace, Task Detail, Skill Admin, Model Config, and Rule Admin stay separate.
- Extract sidebar, SQL input, context drawer, Harness progress, result tabs, code blocks, finding cards, index cards, and risk cards into components.
- API clients, types, state helpers, and utilities belong in separate modules.
- Avoid giant files. If a page grows beyond about 300 lines, split components or hooks before adding more logic.
- Use icons and tooltips for compact controls. Do not turn every action into text-only rounded buttons.

## Verification

- Backend: run `mvn test` in `backend/`.
- Frontend: run `npm run build` in `frontend/`.
- Full packaging: build frontend, replace `backend/src/main/resources/static` with `frontend/dist`, then run `mvn clean package` so deleted classes cannot survive in the deployable JAR.
