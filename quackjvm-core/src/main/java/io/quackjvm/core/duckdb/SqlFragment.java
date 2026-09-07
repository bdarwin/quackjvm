package io.quackjvm.core.duckdb;

import java.util.ArrayList;
import java.util.List;

/** A piece of SQL together with the values to bind to it, in order. */
public final class SqlFragment {

    private final String sql;
    private final List<Object> parameters;

    public SqlFragment(String sql, List<Object> parameters) {
        this.sql = sql;
        this.parameters = parameters;
    }

    public String getSql() {
        return sql;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    /** Combines fragments in order, so that their bound values stay aligned with their placeholders. */
    public static SqlFragment join(String separator, List<SqlFragment> fragments) {
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            if (i > 0) {
                sql.append(separator);
            }
            sql.append(fragments.get(i).sql);
            parameters.addAll(fragments.get(i).parameters);
        }
        return new SqlFragment(sql.toString(), parameters);
    }

    public SqlFragment wrap(String prefix, String suffix) {
        return new SqlFragment(prefix + sql + suffix, parameters);
    }

    @Override
    public String toString() {
        return sql + " " + parameters;
    }
}
