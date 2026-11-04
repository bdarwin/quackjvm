package io.quackjvm.core.journal;

import io.quackjvm.core.Quack;
import io.quackjvm.core.layout.ColumnarLayout;

import java.util.Map;

/**
 * Writes to a journal until it is killed: each record is appended, then grown a part at a time, its
 * text always as many characters as its parts say. Run by {@link JournalTest} in a process of its own.
 */
public final class JournalCrashChild {

    public record Streamed(Long id, Integer parts, String text) {
    }

    private JournalCrashChild() {
    }

    public static void main(String[] args) {
        Quack quack = Quack.open(args[0]);
        Journal<Streamed> journal = Journal.builder(quack, "streamed", ColumnarLayout.ofRecord(Streamed.class)).open();
        long id = 0;
        boolean announced = false;
        while (true) {
            journal.append(new Streamed(id, 0, ""));
            for (int part = 1; part <= 40; part++) {
                journal.update(id, Map.of("parts", part, "text", "x".repeat(part * 25)));
            }
            if (!announced) {
                System.out.println("ready");
                System.out.flush();
                announced = true;
            }
            id++;
        }
    }
}
