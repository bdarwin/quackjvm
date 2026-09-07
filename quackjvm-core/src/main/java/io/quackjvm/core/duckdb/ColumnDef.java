package io.quackjvm.core.duckdb;

/**
 * One column of a DuckDB table which CQEngine maps to a Java value.
 */
public final class ColumnDef {

    private final String name;
    private final Class<?> javaType;
    private final String sqlType;

    public ColumnDef(String name, Class<?> javaType) {
        this.name = name;
        this.javaType = javaType;
        this.sqlType = DuckDBTypes.sqlTypeFor(javaType);
    }

    public String getName() {
        return name;
    }

    public Class<?> getJavaType() {
        return javaType;
    }

    public String getSqlType() {
        return sqlType;
    }

    public String toDdl() {
        return "\"" + name + "\" " + sqlType;
    }

    @Override
    public String toString() {
        return toDdl();
    }
}
