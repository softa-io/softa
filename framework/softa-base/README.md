# Softa Base

## Debug: Use Cases and Usage

`debug` is used to enable debug context (`Context.debug=true`) during a request or a specific method execution. A common use is printing SQL logs and returning more detailed exception information.

### 1. Use Cases

- Troubleshoot whether ORM filters, sorting, and pagination behave as expected.
- Troubleshoot actual SQL statements and parameters used in batch writes (`create/update/delete`).
- Initial performance checks by observing SQL execution time per call.
- Faster issue diagnosis during integration/debugging (client-side error details are richer in debug mode).

### 2. How to Enable

1. Request parameter (Web API)
- Add `?debug=true` or `?debug=1` to any API URL.
- Examples:
  - `GET /api/demo/User/searchList?debug=true`
  - `POST /api/demo/User/searchPage?debug=1`

2. Annotation-based (local to code path)
- Use `@io.softa.framework.orm.annotation.Debug` on a method.
- It temporarily enables debug during method execution and restores the previous value afterward.

3. Global enablement (Spring Boot configuration)
- Set `debug: true` in `application.yml` / `application-dev.yml`.
- This is Spring Boot's global debug switch and increases framework-level debug output (for example, auto-configuration report and some component debug logs).
- It does not set `Context.debug` directly, so it is not the same mechanism as `?debug=` / `@Debug`.
- Therefore, it does not automatically trigger this framework's `@ExecuteSql` aspect timing output (`Time: xxx ms`).

### 3. What Debug SQL Logs Include

- SQL (human-readable statement with placeholders replaced).
- Batch parameters (by default, up to the first 3 rows in batch scenarios).
- Result summary (for list results, by default up to the first 3 rows).
- Execution time (milliseconds).
- Current datasource key in multi-datasource mode.

### 4. Notes

- `?debug=` and `@Debug` are request/method-level switches for the current execution context.
- `debug: true` in `application*.yml` is an application-level switch that affects global logging behavior.
- Debug logs may contain sensitive data; use only in development/testing or temporary troubleshooting.
