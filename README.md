# dot-flows

A small workflow engine you embed in a Spring Boot application. Model a process as tasks joined by
connections, deploy it as an immutable version under a stable key, and run it. Think of a much smaller
Camunda: no BPMN XML, no separate server.

- **Processes** are JSON graphs: start, end, tasks and exclusive (if/else) gateways.
- **Tasks** call a Java delegate, call an HTTP API, or evaluate a decision table.
- **Versions**: every deploy creates version n+1. Running instances finish on the version they started on;
  rolling back is making an older version live again.
- **Execution** is persisted and asynchronous, with retries, and survives restarts.
- **Modeler**: a React UI served by your application at `/dotflows`.

> **The REST API and UI are not authenticated.** Anyone who can reach `/dotflows` can deploy processes that
> call your delegates and make HTTP requests from your server. Protect that path with your own Spring
> Security rules, or set `dotflows.web.enabled=false` where the UI is not needed.

## Quick start

Add the starter to a Spring Boot 4 application that has a datasource (PostgreSQL in production; H2 works
for development):

```xml
<dependency>
    <groupId>io.dotflows</groupId>
    <artifactId>dotflows-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Write a delegate:

```java
@Component
@FlowDelegate(value = "sendEmail", description = "Sends an email",
        inputs = {@DelegateInput(name = "to", required = true), @DelegateInput(name = "subject")})
class SendEmailDelegate implements TaskDelegate {

    @Override
    public void execute(TaskContext ctx) {
        String id = mailer.send(ctx.input("to", String.class), ctx.input("subject", String.class));
        ctx.setVariable("emailMessageId", id);
    }
}
```

Start the application, open `http://localhost:8080/dotflows`, create a process, drop a task on the canvas,
pick `sendEmail`, and deploy. Then start instances from your code:

```java
@Autowired FlowEngine engine;

String instanceId = engine.start("order-notification",
        Map.of("order", Map.of("id", "A-1", "total", 250), "customer", Map.of("email", "ada@example.com")),
        "A-1"); // optional business key to find the instance by
```

`start` joins the caller's transaction, so the instance exists only if your transaction commits.

To try it without writing anything: `./mvnw install && java -jar dotflows-sample-app/target/dotflows-sample-app-*.jar`.

## Concepts

| Concept | Meaning |
|---|---|
| Process | A graph of nodes and connections, identified by `processKey` |
| Draft | The one editable working copy per key; it may be invalid |
| Version | An immutable snapshot created by deploying the draft (1, 2, 3…) |
| Live version | The version new instances start on |
| Instance | One run, pinned to the version it started on |
| Decision | A versioned decision table with the same draft/deploy lifecycle |

### Task types

**delegate** runs a Spring bean that implements `TaskDelegate` and is annotated with `@FlowDelegate`. Its
inputs are expressions evaluated against the process variables; it stores results with `setVariable`.

**http** calls an API. `#{expression}` inserts process variables into the URL, headers and body.
`${name}` inserts a value from `dotflows.http.variables.<name>`, which is where API keys go so they never
appear in a definition; no other application property is reachable. The response is stored as
`{status, body}` in the variable you name.

**decision** evaluates a deployed decision table and stores the result in a variable, typically read by
the gateway that follows.

A new task type is one Spring bean implementing `TaskHandler`.

### Expressions

Gateway conditions, delegate inputs and decision inputs are [SpEL](https://docs.spring.io/spring-framework/reference/core/expressions.html):
`#order.total > 100`, `#customer.email != null`, `'Order ' + #order.id`. They run in a restricted context:
variables, operators, indexing and property access work; method calls, constructors, `T(...)` type
references and bean references do not, so a process author cannot run arbitrary code in your application.

### Decision tables

Each rule tests the input columns (`=`, `≠`, `<`, `≤`, `>`, `≥`, one of, between, or a free expression
where `#input` is the column's value) and sets the outputs. Hit policy `FIRST` returns the first match,
`UNIQUE` fails when more than one rule matches, `COLLECT` returns all matches as a list. With a single
output column the result is that value; with several it is an object.

## How execution works

1. `start` stores the instance and queues its first task as a job.
2. Workers claim due jobs with `SELECT … FOR UPDATE SKIP LOCKED`, so several application instances can
   share one database and each job goes to one of them.
3. The task runs outside any database transaction. Its result, the step record and the next job are then
   committed together.
4. A failed task is retried after a delay. When attempts run out the instance is marked `FAILED` and stays
   at that task until someone retries it (UI or `engine.retry(id)`) or cancels it.
5. A worker that dies mid-task loses its lock after `dotflows.worker.lock-duration`, and another worker
   takes the job over.

**Tasks are delivered at least once.** A delegate can run twice if the application dies after the side
effect but before the commit, or if a task outlives the lock duration. Make delegates idempotent;
`TaskContext.idempotencyKey()` is stable across retries of the same task in the same instance and can be
passed to providers that deduplicate.

Variables are stored as JSON, so they must be JSON-serializable, and they come back as maps, lists,
strings, numbers and booleans.

## Configuration

| Property | Default | |
|---|---|---|
| `dotflows.initialize-schema` | `true` | Create the `dflow_*` tables at startup. Turn off to run [`schema.sql`](dotflows-spring-boot-starter/src/main/resources/io/dotflows/schema/schema.sql) through Flyway or Liquibase yourself. |
| `dotflows.worker.enabled` | `true` | Execute jobs in this application. |
| `dotflows.worker.threads` | `4` | Tasks running at once per application instance. |
| `dotflows.worker.poll-interval` | `1s` | How often an idle worker looks for jobs. |
| `dotflows.worker.lock-duration` | `5m` | How long a job is reserved for its worker. Must exceed your slowest task. |
| `dotflows.worker.shutdown-grace` | `30s` | How long shutdown waits for running tasks. |
| `dotflows.retry.max-attempts` | `3` | Attempts per task, unless the task sets its own. |
| `dotflows.retry.backoff` | `10s` | Wait between attempts. |
| `dotflows.web.enabled` | `true` | Serve the REST API and UI. |
| `dotflows.web.base-path` | `/dotflows` | UI location; the API is under `{base-path}/api`. |
| `dotflows.http.variables.*` | | Values HTTP tasks may use as `${name}`. |
| `dotflows.http.allowed-hosts` | any | Restrict which hosts HTTP tasks may call. |
| `dotflows.http.default-timeout` | `30s` | For HTTP tasks without their own timeout. |

## Definitions in source control

Besides the UI, definitions can live in your repository and be deployed at startup. Deploying content
identical to the live version does nothing, so this is safe on every boot:

```java
@Bean
ApplicationRunner flows(DefinitionService definitions) {
    return args -> definitions.deploy(Kind.PROCESS, processJson);
}
```

See [`order-notification.process.json`](dotflows-sample-app/src/main/resources/flows/order-notification.process.json)
and [`notify-channel.decision.json`](dotflows-sample-app/src/main/resources/flows/notify-channel.decision.json)
for the format.

## REST API

All under `{base-path}/api`; `{kind}` is `processes` or `decisions`.

| | |
|---|---|
| `GET /{kind}` | List with live version and whether the draft has undeployed changes |
| `GET /{kind}/{key}` | The draft, or the live version if there is no draft |
| `PUT /{kind}/{key}/draft` | Save the draft |
| `POST /{kind}/validate` | Check a definition without saving it |
| `POST /{kind}/{key}/deploy` | Deploy the draft as a new version; `422` with the problems if invalid |
| `GET /{kind}/{key}/versions`, `GET …/versions/{v}` | History and one version |
| `POST /{kind}/{key}/versions/{v}/activate` | Make a version live |
| `DELETE /{kind}/{key}` | Delete; refused for a process that has instances |
| `POST /decisions/{key}/evaluate` | Try a table against sample variables |
| `POST /processes/{key}/start` | Start an instance: `{variables, businessKey, version}` |
| `GET /instances?processKey=&status=&businessKey=` | Find instances |
| `GET /instances/{id}` | Instance with its step history |
| `POST /instances/{id}/retry`, `…/cancel` | Retry a failed instance, or cancel |
| `GET /delegates` | Delegates registered in this application |

## Not in this version

Script tasks, parallel gateways, timers and waiting for external events, human tasks, sub-processes,
moving running instances to another version, databases other than PostgreSQL and H2.

## Building

```
./mvnw install          # builds everything, including the UI; PostgreSQL tests need Docker and skip without it
cd dotflows-ui && npm run dev   # UI with hot reload, proxying to a host app on localhost:8080
```

| Module | |
|---|---|
| `dotflows-core` | Model, validation, engine and SPIs. No web or JDBC; has in-memory stores for unit tests. |
| `dotflows-ui` | The React modeler, packaged as static resources. |
| `dotflows-spring-boot-starter` | Auto-configuration, JDBC stores, HTTP task, REST API. This is the dependency to add. |
| `dotflows-sample-app` | A host application with example delegates and flows. |

### Publishing to Maven Central

1. Register a namespace at [central.sonatype.com](https://central.sonatype.com). `io.github.<your-user>` is
   verified automatically from your GitHub account; change `groupId` in every `pom.xml` to match
   (the Java package names can stay).
2. Fill in the `OWNER` and developer placeholders in the root `pom.xml` and confirm the licence.
3. Put a Central Portal token in `~/.m2/settings.xml` as `<server><id>central</id>…</server>` and have a
   GPG key available.
4. Set a non-SNAPSHOT version, then `./mvnw -Prelease deploy`.
