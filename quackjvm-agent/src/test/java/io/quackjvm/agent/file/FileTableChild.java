package io.quackjvm.agent.file;

import java.nio.file.Path;

/**
 * Holds a {@link FileTable} open in a process of its own until its input closes - the other
 * process in {@link FileTableSharingTest}. Prints its cache file once it is open and loaded.
 */
public final class FileTableChild {

    private FileTableChild() {
    }

    public static void main(String[] args) throws Exception {
        Path log = Path.of(args[0]);
        if (args.length > 2 && args[2].equals("load")) {
            // Loads slowly, in small batches, until it is killed.
            FileTable table = FileTables.builder(log, FileTableTest.COLUMNS, LineSource.of(log), FileTableTest.PARSER)
                    .cacheDir(Path.of(args[1])).version("test-1").threads(2).blockSize(1000).open();
            table.update(null, done -> {
                System.out.println("stored " + done);
                System.out.flush();
            });
            System.out.println("finished");
            return;
        }
        try (FileTable table = FileTables.builder(log, FileTableTest.COLUMNS, LineSource.of(log), FileTableTest.PARSER)
                .cacheDir(Path.of(args[1])).version("test-1").open()) {
            table.update();
            System.out.println("ready " + table.cacheFile());
            System.out.flush();
            while (System.in.read() >= 0) {
                // until the parent closes our input
            }
        }
    }
}
