# Dashboard

The dashboard is a live web page for a running application. It shows where quackjvm is spending
its time, and whether that time is spent working or waiting. It reads the
[metrics](metrics.md) and runs the same `Diagnosis` on the last ten seconds, once a second.

![The dashboard while eight threads queue for one collection's write lock](assets/dashboard-write-lock.png)

## Starting it

Add the module:

```xml
<dependency>
    <groupId>io.github.bdarwin</groupId>
    <artifactId>quackjvm-dashboard</artifactId>
    <version>1.1.0</version>
</dependency>
```

Then start it with one line:

```java
QuackDashboard dashboard = QuackDashboard.start(database.metrics(), 8090);
System.out.println(dashboard.url());        // http://127.0.0.1:8090/
```

The module depends only on `quackjvm-core`. It uses the JDK's own HTTP server with two daemon
request threads and one sampling thread, so it won't keep the JVM alive. Call `close()` to stop it.

`QuackDashboard.builder(metrics)` also takes these options:
- `title("orders-service")`: shown in the header
- `history(Duration.ofMinutes(15))`: how far back the charts go (5 minutes by default)
- `port(0)`: pick a free port
- `bindAddress(...)`: see below

## What it shows

**Where it is choking.** The findings from `Diagnosis` for the last ten seconds, most severe
first. Each finding says what was seen and what to change. When nothing is found, the page says
so.

**Now.** The headline numbers for the last ten seconds, each with five minutes of history:
- reads and writes per second, and their p50 and p99 times
- the share of write time spent queueing for a write lock
- CPU, including DuckDB's native threads
- how many heavy statements (1 ms or more) were running at once
- DuckDB's memory against its limit, and bytes spilled to disk
- conflicts per second
- connections in use, and the statement-cache hit rate

A tile turns red when its number is in the range that triggers a diagnosis.

**Wait against work, by collection.** For each collection: reads per second with their times,
writes per second with their times, the p99 wait for the write lock, and the share of write time
spent waiting. This table shows *which* collection is choking. In the screenshot above, writes to
`orders` spend 88% of their time waiting, while `refunds`, in the same database, waits 0%.

**Where DuckDB's time went.** Statements from the last minute, ranked by their share of total
statement time. Statements that differ only in their values count as one: `IN (?, ?, ?)` and
`IN (?, ?)` are the same statement.

![The dashboard while twenty users run heavy aggregates](assets/dashboard-cpu.png)

With twenty users running heavy aggregates on ten cores, it reports 92% CPU with 19.9 heavy
statements running at once. It names the statement responsible, and says that setting `threads`
to 5 would stop the queries asking for 199 cores between them.

## Security

The defaults are safe for a production process:

- **Loopback only.** It listens on `127.0.0.1`, so only processes on the same machine can reach
  it. Use an SSH tunnel to look from elsewhere: `ssh -L 8090:127.0.0.1:8090 host`.
- **DNS rebinding is refused.** A web page on another site could point a name it controls at
  `127.0.0.1` and read the dashboard through your browser. To prevent that, requests are answered
  only when addressed to `localhost`, `127.x.x.x` or `[::1]`. Anything else gets a 403.
- **Read-only.** It only answers `GET` and `HEAD`. There is no endpoint that runs SQL, changes a
  setting or triggers any work.
- **No data values.** Numbers and quoted strings are removed from statements before they are
  recorded. The page shows `WHERE id = ?`, never `WHERE id = 42`.
- **A strict page.** `Content-Security-Policy: default-src 'self'` and `X-Frame-Options: DENY`
  are set. Script and styles are served from the dashboard itself, and every value is inserted
  as text, never as markup.

Calling `bindAddress(...)` with a non-loopback address makes the page reachable from that network,
and turns off the host check. Only do that behind whatever already protects your internal tools.

## Trying it

`examples/src/main/java/DashboardDemo.java` changes its load every half minute:
1. a quiet period
2. eight threads queueing for one collection's write lock
3. twenty dashboard users competing for CPU
4. SQL built with the values pasted in

Open the printed address and watch the diagnosis change with each phase.

```
cd examples
mvn -q generate-resources
java --enable-native-access=ALL-UNNAMED -Xmx4g -cp "$(cat target/classpath.txt)" \
     src/main/java/DashboardDemo.java
```
