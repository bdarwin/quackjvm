package io.quackjvm.dashboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Names the other programs using the machine's cores, when they are using enough of them to starve
 * DuckDB. The metrics can see that the machine is busy and this process is not; this says who is.
 *
 * <p>Two ways to read other processes' CPU, because neither works everywhere:</p>
 * <ul>
 *   <li><b>Linux and Windows:</b> {@link ProcessHandle} reports each process's total CPU time, so two
 *       readings a moment apart give its share. No other program is run.</li>
 *   <li><b>macOS:</b> {@link ProcessHandle} reports CPU time for this process alone - measured: of
 *       735 processes, one. {@code /bin/ps} reports every process's recent CPU, so it is run, with
 *       fixed arguments and no shell, in about 40 ms.</li>
 * </ul>
 *
 * <p>It only looks when other processes hold at least a quarter of the machine, and at most once
 * every five seconds, so an idle machine costs nothing. It records each program's name and process
 * id - never its arguments, which can hold passwords.</p>
 *
 * <p>Not thread-safe: the dashboard's sampling thread is its only caller.</p>
 */
final class OtherProcesses {

    /** Other processes' share of the machine at which it is worth finding out who they are. */
    static final double THRESHOLD = 0.25;
    /** Below this share of the machine a process is not worth naming. */
    static final double NOTABLE = 0.02;
    private static final long MIN_GAP_NANOS = 5_000_000_000L;
    private static final int TOP = 5;

    /** One program's share of the whole machine's cores, 0 to 1. */
    record Busy(String name, long pid, double share) {
    }

    enum Mode { PROCESS_HANDLE, PS, NONE }

    private final Mode mode;
    private final long ownPid = ProcessHandle.current().pid();
    private final int cores = Runtime.getRuntime().availableProcessors();
    private long lastLookedAt;
    /** For {@link Mode#PROCESS_HANDLE}: CPU nanoseconds per pid at the previous reading. */
    private Map<Long, Long> previousCpu;
    private long previousAt;

    OtherProcesses() {
        this(detect());
    }

    OtherProcesses(Mode mode) {
        this.mode = mode;
    }

    Mode mode() {
        return mode;
    }

    static Mode detect() {
        long visible = ProcessHandle.allProcesses().limit(200)
                .filter(p -> p.pid() != ProcessHandle.current().pid())
                .filter(p -> p.info().totalCpuDuration().isPresent())
                .limit(3).count();
        if (visible >= 3) {
            return Mode.PROCESS_HANDLE;
        }
        if (Files.isExecutable(Path.of("/bin/ps"))) {
            return Mode.PS;
        }
        return Mode.NONE;
    }

    /**
     * The busiest other programs, if other processes hold enough of the machine to be worth
     * naming and it is time to look again; otherwise null, meaning "not looked".
     *
     * @param othersShare the machine's CPU less this process's, 0 to 1
     */
    List<Busy> lookIfBusy(double othersShare) {
        if (mode == Mode.NONE || !(othersShare >= THRESHOLD)) {
            return null;
        }
        long now = System.nanoTime();
        // A ProcessHandle reading needs a second one a moment later, so it may look every second
        // while it is building its baseline; after that, and for ps, every five.
        boolean baselineOnly = mode == Mode.PROCESS_HANDLE && (previousCpu == null || now - previousAt > 3 * MIN_GAP_NANOS);
        if (!baselineOnly && lastLookedAt != 0 && now - lastLookedAt < MIN_GAP_NANOS) {
            return null;
        }
        List<Busy> busy = look(now);
        if (busy != null) {
            lastLookedAt = now;
        }
        return busy;
    }

    /** Looks now, whatever the load. Null while a ProcessHandle baseline is being taken. */
    List<Busy> look(long now) {
        try {
            List<Busy> all = mode == Mode.PS ? fromPs() : fromProcessHandles(now);
            if (all == null) {
                return null;
            }
            all.removeIf(b -> b.pid() == ownPid || b.share() < NOTABLE);
            all.sort(Comparator.comparingDouble(Busy::share).reversed());
            return new ArrayList<>(all.subList(0, Math.min(TOP, all.size())));
        }
        catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private List<Busy> fromProcessHandles(long now) {
        Map<Long, Long> cpu = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        ProcessHandle.allProcesses().forEach(p -> {
            ProcessHandle.Info info = p.info();
            info.totalCpuDuration().ifPresent(d -> {
                cpu.put(p.pid(), d.toNanos());
                names.put(p.pid(), nameOf(info.command().orElse("pid " + p.pid())));
            });
        });
        Map<Long, Long> before = previousCpu;
        long elapsed = now - previousAt;
        previousCpu = cpu;
        previousAt = now;
        if (before == null || elapsed <= 0) {
            return null;
        }
        List<Busy> busy = new ArrayList<>();
        cpu.forEach((pid, nanos) -> {
            Long earlier = before.get(pid);
            if (earlier != null && nanos > earlier) {
                busy.add(new Busy(names.get(pid), pid, (nanos - earlier) / (double) elapsed / cores));
            }
        });
        return busy;
    }

    private List<Busy> fromPs() throws IOException {
        Process ps = new ProcessBuilder("/bin/ps", "-Ao", "pid=,pcpu=,comm=")
                .redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                lines.add(line);
            }
        }
        try {
            if (!ps.waitFor(2, TimeUnit.SECONDS)) {
                ps.destroyForcibly();
                return List.of();
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ps.destroyForcibly();
            return List.of();
        }
        return parsePs(lines, cores);
    }

    /** {@code ps -Ao pid=,pcpu=,comm=} lines; pcpu is percent of one core. */
    static List<Busy> parsePs(List<String> lines, int cores) {
        List<Busy> busy = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+", 3);
            if (parts.length < 3) {
                continue;
            }
            try {
                long pid = Long.parseLong(parts[0]);
                double percentOfOneCore = Double.parseDouble(parts[1]);
                busy.add(new Busy(nameOf(parts[2]), pid, percentOfOneCore / 100.0 / cores));
            }
            catch (NumberFormatException notAProcessLine) {
                // A header or a truncated line.
            }
        }
        return busy;
    }

    /**
     * A program's name from its executable path: the application bundle on macOS
     * ({@code .../PyCharm.app/Contents/MacOS/pycharm} is PyCharm), otherwise the file name.
     */
    static String nameOf(String command) {
        String path = command.trim();
        int app = path.toLowerCase(Locale.ROOT).lastIndexOf(".app/");
        if (app > 0) {
            int start = path.lastIndexOf('/', app - 1) + 1;
            return path.substring(start, app);
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }
}
