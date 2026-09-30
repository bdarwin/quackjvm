package io.quackjvm.core.duckdb;

/**
 * One column of a DuckDB table which CQEngine maps to a Java value.
 *
 * <p>A column may also carry what it means and what it is measured in. Those are written into the
 * database as comments when the table is created, so that anything reading the schema - a person, a
 * query, or something that has to decide what to ask - finds them there rather than in code. See
 * {@link io.quackjvm.core.catalog.Catalog}.</p>
 */
public final class ColumnDef {

    private final String name;
    private final Class<?> javaType;
    private final String sqlType;
    private final String description;
    private final String unit;

    public ColumnDef(String name, Class<?> javaType) {
        this(name, javaType, null, null);
    }

    public ColumnDef(String name, Class<?> javaType, String description, String unit) {
        this.name = name;
        this.javaType = javaType;
        this.sqlType = DuckDBTypes.sqlTypeFor(javaType);
        this.description = description;
        this.unit = unit;
    }

    /** The same column, with what it means. */
    public ColumnDef describedAs(String description) {
        return new ColumnDef(name, javaType, description, unit);
    }

    /** The same column, with what it is measured in - "USD", "kg", "ms". */
    public ColumnDef measuredIn(String unit) {
        return new ColumnDef(name, javaType, description, unit);
    }

    /** What this column means, or null. */
    public String getDescription() {
        return description;
    }

    /** What this column is measured in, or null. */
    public String getUnit() {
        return unit;
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
