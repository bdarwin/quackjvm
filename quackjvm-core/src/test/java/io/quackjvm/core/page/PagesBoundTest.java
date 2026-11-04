package io.quackjvm.core.page;

import io.quackjvm.core.Quack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Pages bound each search by how far the last page reached. Here the rows come in clusters with
 * ties and gaps, so a bound often cuts off rows the page needs - and every walk must still read
 * each row once, in order, whatever the kind of the first order column.
 */
public class PagesBoundTest {

    private Quack db;

    @Before
    public void open() {
        db = Quack.inMemory();
    }

    @After
    public void close() {
        db.close();
    }

    /** A first order column of each kind the bound handles, and one it does not. */
    private static final String[][] KINDS = {
            {"BIGINT", "k"},
            {"DOUBLE", "k / 7.0"},
            {"TIMESTAMP", "TIMESTAMP '2026-01-01' + to_microseconds(k * 1000)"},
            {"TIMESTAMPTZ", "TIMESTAMPTZ '2026-01-01 00:00:00+00' + to_microseconds(k * 1000)"},
            {"DATE", "DATE '2000-01-01' + (k // 1000)::INTEGER"},
            {"INTEGER", "(k // 10)::INTEGER"},
            {"VARCHAR", "lpad(k::VARCHAR, 12, '0')"},
    };

    @Test
    public void everyWalkReadsEveryRowOnceInOrderWhateverTheGaps() {
        // k: dense runs, ties, then gaps a thousand times as wide - the bound misses across each gap.
        db.execute("CREATE TABLE base AS SELECT id, k FROM ("
                + " SELECT x AS id, (x // 3) + (x // 500) * 100000 + (CASE WHEN x % 97 = 0 THEN 5000000 ELSE 0 END) AS k"
                + " FROM range(1500) r(x))");
        for (String[] kind : KINDS) {
            db.execute("CREATE OR REPLACE TABLE t AS SELECT id, (" + kind[1] + ")::" + kind[0] + " AS c FROM base");
            List<Long> expected = db.query("SELECT id FROM t ORDER BY c, id").list(Long.class);
            for (int size : new int[] {1, 7, 50, 333}) {
                Pages pages = Pages.of(db, "SELECT * FROM t").orderBy("c", "id").size(size);
                assertEquals(kind[0] + " backwards, " + size, expected, backwards(pages));
                assertEquals(kind[0] + " forwards, " + size, expected, forwards(pages));
            }
        }
    }

    private static List<Long> backwards(Pages pages) {
        List<Long> seen = new ArrayList<>();
        Page page = pages.last();
        add(seen, page, true);
        while (page.hasBefore()) {
            page = pages.before(page.firstKey());
            add(seen, page, true);
        }
        Collections.reverse(seen);
        return seen;
    }

    private static List<Long> forwards(Pages pages) {
        List<Long> seen = new ArrayList<>();
        Page page = pages.first();
        add(seen, page, false);
        while (page.hasAfter()) {
            page = pages.after(page.lastKey());
            add(seen, page, false);
        }
        return seen;
    }

    private static void add(List<Long> seen, Page page, boolean backwards) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < page.size(); i++) {
            ids.add(((Number) page.get(i, "id")).longValue());
        }
        if (backwards) {
            Collections.reverse(ids);
        }
        seen.addAll(ids);
    }

    @Test
    public void aroundAfterLongJumpsStillFindsItsRows() {
        db.execute("CREATE TABLE t AS SELECT x AS id, (x // 3) + (x // 500) * 100000 AS c FROM range(3000) r(x)");
        List<Long> all = db.query("SELECT id FROM t ORDER BY c, id").list(Long.class);
        Pages pages = Pages.of(db, "SELECT * FROM t").orderBy("c", "id").size(20);
        pages.last();   // teaches it a small reach
        for (int at = 0; at < all.size(); at += 137) {
            long id = all.get(at);
            long c = db.query("SELECT c FROM t WHERE id = ?", id).scalar(Long.class);
            Page page = pages.around(PageKey.of(c, id), 10);
            List<Long> got = new ArrayList<>();
            for (int i = 0; i < page.size(); i++) {
                got.add(((Number) page.get(i, "id")).longValue());
            }
            assertEquals("around " + id, all.subList(Math.max(0, at - 10), Math.min(all.size(), at + 11)), got);
        }
    }
}
