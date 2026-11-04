package io.quackjvm.core.journal;

import io.quackjvm.core.Quack;
import io.quackjvm.core.layout.ColumnarLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends a chat of 50,000 messages, then deletes it, over and over, until it is killed - saying
 * when each delete starts and how long it took. Run by {@link JournalDeleteTest} in a process of its
 * own.
 */
public final class JournalDeleteChild {

    static final int MESSAGES = 50_000;

    public record Said(Long chat, Long seq, String text) {
    }

    private JournalDeleteChild() {
    }

    static Journal<Said> open(Quack quack) {
        return Journal.builder(quack, "said", ColumnarLayout.ofRecord(Said.class))
                .keyColumns("chat", "seq").sequence("seq").within("chat").open();
    }

    public static void main(String[] args) {
        Quack quack = Quack.open(args[0]);
        Journal<Said> journal = open(quack);
        long chat = journal.count() == 0 ? 0
                : quack.query("SELECT max(chat) + 1 FROM said").scalar(Long.class);
        while (true) {
            List<Said> messages = new ArrayList<>(MESSAGES);
            for (int i = 0; i < MESSAGES; i++) {
                messages.add(new Said(chat, null, "message " + i + " of chat " + chat));
            }
            journal.append(messages);
            // Kept: one chat whole, so a reopened file always has one to check is whole.
            if (chat % 2 == 1) {
                System.out.println("deleting " + chat);
                System.out.flush();
                long started = System.nanoTime();
                journal.deleteWhere("chat = ?", chat);
                System.out.println("deleted " + chat + " in " + (System.nanoTime() - started) / 1_000_000 + " ms");
                System.out.flush();
            }
            chat++;
        }
    }
}
