package bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the RESULT lines of one or more run.sh results files into the markdown tables of
 * docs/questdb.md, and checks that every engine returned the same rows and sum.
 *
 * <pre>java bench.Report results.txt [more.txt ...] &gt; tables.md</pre>
 *
 * A later line for the same engine, size and question replaces an earlier one, so a rerun of one
 * engine can be appended to a file.
 */
public final class Report {

    record Result(String median, String summary) {
        boolean failed() {
            return median.equals("FAILED");
        }

        double ms() {
            return Double.parseDouble(median);
        }
    }

    /** devices -> engine -> question -> result */
    static final Map<Integer, Map<String, Map<String, Result>>> results = new TreeMap<>();
    static final Map<String, String> loads = new LinkedHashMap<>();
    static final List<String> header = new ArrayList<>();
    static String sizes = "";

    public static void main(String[] args) throws Exception {
        for (String file : args) {
            for (String line : Files.readAllLines(Path.of(file))) {
                String[] f = line.split("\t", -1);
                if (line.startsWith("# ")) {
                    header.add(line.substring(2));
                }
                else if (f[0].equals("RESULT") && f.length >= 10) {
                    int d = Integer.parseInt(f[4]);
                    sizes = String.format("%,d readings, %,d probes", Long.parseLong(f[2]), Long.parseLong(f[3]));
                    results.computeIfAbsent(d, k -> new LinkedHashMap<>()).computeIfAbsent(f[1], k -> new LinkedHashMap<>())
                            .put(f[5], new Result(f[6], f[9]));
                }
                else if (f[0].equals("LOADAVG") && f.length >= 3) {
                    loads.put(f[1], f[2]);
                }
            }
        }
        for (String h : header) System.out.println("<!-- " + h + " -->");
        System.out.println();
        for (int d : results.keySet()) {
            perSize(d);
        }
        byDevices();
        otherForms();
        crossCheck();
        System.out.println("1-minute load average before each engine: " + String.join(", ",
                loads.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()) + ".");
    }

    static Map<String, Result> of(int d, String engine) {
        return results.getOrDefault(d, Map.of()).getOrDefault(engine, Map.of());
    }

    /** A time in ms as the docs write it: no decimals from 100 up, one below, "0.0" for nothing. */
    static String ms(Result r) {
        if (r == null) return "–";
        if (r.failed()) {
            java.util.regex.Matcher limit = java.util.regex.Pattern.compile("timeout=(\\d+)ms").matcher(r.summary());
            return limit.find() ? String.format("over %,d s: stopped by its query limit", Long.parseLong(limit.group(1)) / 1000)
                    : "failed: " + r.summary();
        }
        double v = r.ms();
        return v >= 100 ? String.format("%,.0f", v) : String.format("%.1f", v);
    }

    static String load(Result r) {
        return r == null ? "–" : String.format("%.1f s", r.ms() / 1000);
    }

    static String memory(Result r) {
        return r == null ? "–" : r.summary();
    }

    /** CQEngine answers a sample of the probes: its key is asof/K. */
    static String cqAsof(Map<String, Result> cq) {
        for (var e : cq.entrySet()) {
            if (e.getKey().startsWith("asof/")) {
                return "_" + ms(e.getValue()) + " per " + String.format("%,d", Integer.parseInt(e.getKey().substring(5)))
                        + " probes_";
            }
        }
        return cq.containsKey("asof") ? "_" + ms(cq.get("asof")) + "_" : "–";
    }

    /** QuestDB's faster hint, and which it was. */
    static String[] questBest(Map<String, Result> q) {
        Result dense = q.get("asof+dense");
        Result linear = q.get("asof+linear");
        if (dense == null && linear == null) return new String[] {"–", ""};
        if (linear == null || (dense != null && !dense.failed() && dense.ms() <= linear.ms())) {
            return new String[] {ms(dense), "asof_dense" + (linear == null ? "" : "; asof_linear " + ms(linear))};
        }
        return new String[] {ms(linear), "asof_linear; asof_dense " + ms(dense)};
    }

    static void perSize(int d) {
        Map<String, Result> cq = of(d, "cq");
        Map<String, Result> java = of(d, "java");
        Map<String, Result> duck = of(d, "duck");
        Map<String, Result> quest = of(d, "quest");
        System.out.printf("### %s, %,d devices%n%n", sizes, d);
        System.out.println("| | as-of totals | as-of rows back | rows back, `ORDER BY p.ts` | hourly average | latest per device"
                + " | last hour | load | memory / disk |");
        System.out.println("|---|---|---|---|---|---|---|---|---|");
        if (!cq.isEmpty()) {
            System.out.printf("| _CQEngine on heap_ | %s | – | – | _%s_ | _%s_ | _%s_ | _%s_ | _%s_ |%n", cqAsof(cq),
                    ms(cq.get("bucket")), cq.containsKey("latest") ? ms(cq.get("latest")) : "not run",
                    ms(cq.get("recent")), load(cq.get("load")), memory(cq.get("memory")));
        }
        if (!java.isEmpty()) {
            System.out.printf("| Java arrays | %s | – | – | %s | %s | %s | %s | %s |%n", ms(java.get("asof")),
                    ms(java.get("bucket")), ms(java.get("latest")), ms(java.get("recent")), load(java.get("load")),
                    memory(java.get("memory")));
        }
        if (!duck.isEmpty()) {
            System.out.printf("| DuckDB via quackjvm | %s | %s | %s | %s | %s | %s | %s | %s |%n", ms(duck.get("asof")),
                    ms(duck.get("asofRows")), ms(duck.get("asofRows+ord")), ms(duck.get("bucket")),
                    ms(duck.get("latest")), ms(duck.get("recent")), load(duck.get("load")), memory(duck.get("memory")));
        }
        if (!quest.isEmpty()) {
            String[] best = questBest(quest);
            System.out.printf("| QuestDB, best hint | %s (%s) | %s | %s | %s | %s | %s | %s | %s |%n", best[0], best[1],
                    ms(quest.get("asofRows+dense")), ms(quest.get("asofRows+dense+ord")), ms(quest.get("bucket")),
                    ms(quest.get("latest")), ms(quest.get("recent")), load(quest.get("load")), memory(quest.get("memory")));
            System.out.printf("| QuestDB, no hint | %s | | | | | | | |%n", ms(quest.get("asof")));
        }
        System.out.println();
    }

    static void byDevices() {
        System.out.println("### The as-of totals by number of devices");
        System.out.println();
        System.out.println("| devices | _CQEngine on heap_ | Java arrays | DuckDB | QuestDB, best hint | QuestDB, no hint |");
        System.out.println("|---|---|---|---|---|---|");
        for (int d : results.keySet()) {
            String[] best = questBest(of(d, "quest"));
            System.out.printf("| %,d | %s | %s | %s | %s | %s |%n", d, cqAsof(of(d, "cq")), ms(of(d, "java").get("asof")),
                    ms(of(d, "duck").get("asof")), best[0] + (best[1].isEmpty() ? "" : " (" + best[1] + ")"),
                    ms(of(d, "quest").get("asof")));
        }
        System.out.println();
    }

    /** Any DuckDB question beyond the standard ones: other read paths, other query forms. */
    static void otherForms() {
        List<String> standard = List.of("load", "memory", "asof", "asofRows", "asofRows+ord", "bucket", "latest", "recent");
        Map<String, Map<Integer, Result>> extra = new LinkedHashMap<>();
        for (int d : results.keySet()) {
            of(d, "duck").forEach((q, r) -> {
                if (!standard.contains(q)) extra.computeIfAbsent(q, k -> new TreeMap<>()).put(d, r);
            });
        }
        if (extra.isEmpty()) return;
        System.out.println("### Other DuckDB forms");
        System.out.println();
        StringBuilder head = new StringBuilder("| question |");
        StringBuilder rule = new StringBuilder("|---|");
        for (int d : results.keySet()) {
            head.append(String.format(" %,d devices |", d));
            rule.append("---|");
        }
        System.out.println(head);
        System.out.println(rule);
        extra.forEach((q, byD) -> {
            StringBuilder row = new StringBuilder("| `" + q + "` |");
            for (int d : results.keySet()) row.append(" ").append(ms(byD.get(d))).append(" |");
            System.out.println(row);
        });
        System.out.println();
    }

    /**
     * Every engine's answer to the same question must match. The keys that name the same question
     * on each engine; CQEngine's sampled as-of join is left out.
     */
    static void crossCheck() {
        Map<String, List<String>> same = new LinkedHashMap<>();
        same.put("as-of totals", List.of("java:asof", "duck:asof", "quest:asof", "quest:asof+dense", "quest:asof+linear",
                "cq:asof"));
        same.put("as-of rows back", List.of("duck:asofRows", "duck:asofRows+ord", "duck:asofRows/records", "duck:asofRows+ord/records", "quest:asofRows+dense",
                "quest:asofRows+dense+ord"));
        same.put("hourly average", List.of("java:bucket", "duck:bucket", "duck:bucket/records", "quest:bucket", "cq:bucket"));
        same.put("latest per device", List.of("java:latest", "duck:latest", "duck:latest/records", "quest:latest", "cq:latest"));
        same.put("last hour", List.of("java:recent", "duck:recent", "duck:recent/records", "quest:recent", "cq:recent"));
        List<String> problems = new ArrayList<>();
        for (int d : results.keySet()) {
            for (var e : same.entrySet()) {
                String expected = null;
                String from = null;
                for (String key : e.getValue()) {
                    String[] k = key.split(":");
                    Result r = of(d, k[0]).get(k[1]);
                    if (r == null || r.failed()) continue;
                    if (expected == null) {
                        expected = r.summary();
                        from = key;
                    }
                    else if (!expected.equals(r.summary())) {
                        problems.add(String.format("%,d devices, %s: %s gave %s, %s gave %s", d, e.getKey(), from,
                                expected, key, r.summary()));
                    }
                }
            }
        }
        if (problems.isEmpty()) {
            System.out.println("Every engine returned the same row count and sum for every question.");
        }
        else {
            System.out.println("**Results that do not match:**");
            System.out.println();
            problems.forEach(p -> System.out.println("- " + p));
        }
        System.out.println();
    }
}
