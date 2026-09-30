package io.quackjvm.core.udf;

import java.util.List;

/**
 * A registered function: what SQL calls it, and what it takes and returns.
 *
 * <p>DuckDB 1.5.5 has no way to unregister a function, so there is nothing here to close. Registering
 * the same name again replaces what it does.</p>
 */
public final class Udf {

    private final String name;
    private final Class<?> returnType;
    private final List<Class<?>> parameterTypes;

    Udf(String name, Class<?> returnType, List<Class<?>> parameterTypes) {
        this.name = name;
        this.returnType = returnType;
        this.parameterTypes = List.copyOf(parameterTypes);
    }

    /** What SQL calls it. */
    public String getName() {
        return name;
    }

    public Class<?> getReturnType() {
        return returnType;
    }

    public List<Class<?>> getParameterTypes() {
        return parameterTypes;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(name).append('(');
        for (int i = 0; i < parameterTypes.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(parameterTypes.get(i).getSimpleName());
        }
        return out.append(") -> ").append(returnType.getSimpleName()).toString();
    }
}
