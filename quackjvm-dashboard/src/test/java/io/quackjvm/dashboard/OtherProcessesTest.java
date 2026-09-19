package io.quackjvm.dashboard;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class OtherProcessesTest {

    @Test
    public void programsAreNamedByTheirApplicationOrFile() {
        assertEquals("PyCharm", OtherProcesses.nameOf("/Users/x/Applications/PyCharm.app/Contents/MacOS/pycharm"));
        assertEquals("Google Chrome Helper (Renderer)", OtherProcesses.nameOf("/Applications/Google Chrome.app/Contents/"
                + "Frameworks/Google Chrome Framework.framework/Versions/1/Helpers/Google Chrome Helper (Renderer).app/"
                + "Contents/MacOS/Google Chrome Helper (Renderer)"));
        assertEquals("java", OtherProcesses.nameOf("/opt/homebrew/opt/java/libexec/bin/java"));
        assertEquals("claude", OtherProcesses.nameOf("claude"));
    }

    @Test
    public void psOutputIsReadAsAShareOfTheWholeMachine() {
        List<OtherProcesses.Busy> busy = OtherProcesses.parsePs(List.of(
                "  402   5.0 /System/Library/PrivateFrameworks/SkyLight.framework/Resources/WindowServer",
                "15816  250.0 /Users/x/Applications/PyCharm.app/Contents/MacOS/pycharm",
                "garbage",
                "  77   1.5 /usr/bin/some program with spaces"), 10);
        assertEquals(3, busy.size());
        assertEquals("PyCharm", busy.get(1).name());
        assertEquals(0.25, busy.get(1).share(), 1e-9);    // 2.5 cores of 10
        assertEquals("some program with spaces", busy.get(2).name());
    }

    @Test
    public void aQuietMachineIsNotLookedAt() {
        OtherProcesses watcher = new OtherProcesses();
        assertNull(watcher.lookIfBusy(0.10));
        assertNull(watcher.lookIfBusy(Double.NaN));
    }

    /** A real busy program, started here, must be named - by whichever method this platform has. */
    @Test(timeout = 60_000)
    public void aBusyProgramIsNamed() throws Exception {
        OtherProcesses watcher = new OtherProcesses();
        assumeTrue("no way to read other processes here", watcher.mode() != OtherProcesses.Mode.NONE);
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/yes")));
        Process yes = new ProcessBuilder("/usr/bin/yes").redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            Thread.sleep(2_000);
            List<OtherProcesses.Busy> busy = watcher.look(System.nanoTime());
            if (busy == null) {          // ProcessHandle needs a second reading to compare with
                Thread.sleep(1_000);
                busy = watcher.look(System.nanoTime());
            }
            assertNotNull(busy);
            assertTrue("yes should be among " + busy, busy.stream().anyMatch(b -> b.pid() == yes.pid()
                    && b.name().equals("yes") && b.share() >= OtherProcesses.NOTABLE));
            assertTrue("this process is never named", busy.stream().noneMatch(b -> b.pid() == ProcessHandle.current().pid()));
        }
        finally {
            yes.destroyForcibly();
        }
    }
}
