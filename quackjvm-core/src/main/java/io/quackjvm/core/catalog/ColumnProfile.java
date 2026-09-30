package io.quackjvm.core.catalog;

/**
 * What is actually in a column, as {@code SUMMARIZE} reports it. The values come back as text
 * because they are whatever the column's type is.
 *
 * @param column        the column's name
 * @param sqlType       its DuckDB type
 * @param min           the smallest value, as text
 * @param max           the largest value, as text
 * @param approxUnique  roughly how many distinct values, or null
 * @param average       the mean, for a column that has one
 * @param standardDeviation the standard deviation, for a column that has one
 * @param nullPercentage what share of the rows are null, 0 to 100, or null
 */
public record ColumnProfile(String column, String sqlType, String min, String max, Long approxUnique,
                            String average, String standardDeviation, Double nullPercentage) {

    public String toText() {
        StringBuilder out = new StringBuilder(column).append(' ').append(sqlType)
                .append(": ").append(min).append(" to ").append(max);
        if (approxUnique != null) {
            out.append(", ~").append(String.format("%,d", approxUnique)).append(" distinct");
        }
        if (average != null) {
            out.append(", mean ").append(average);
        }
        if (nullPercentage != null && nullPercentage > 0) {
            out.append(", ").append(nullPercentage).append("% null");
        }
        return out.toString();
    }
}
