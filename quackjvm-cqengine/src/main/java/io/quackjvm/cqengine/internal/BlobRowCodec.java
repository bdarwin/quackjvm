package io.quackjvm.cqengine.internal;

import io.quackjvm.core.duckdb.ColumnDef;

import io.quackjvm.cqengine.serialization.KryoPojoSerializer;
import com.googlecode.cqengine.persistence.support.serialization.PersistenceConfig;
import com.googlecode.cqengine.persistence.support.serialization.PojoSerializer;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.lang.reflect.Constructor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * Stores each object as a single serialized BLOB, honouring the same {@link PersistenceConfig}
 * annotation as CQEngine's own off-heap and disk persistence. This works with any object type
 * without any mapping code.
 */
public final class BlobRowCodec<O> implements RowCodec<O> {

    public static final String VALUE_COLUMN = "value";

    private final Class<O> objectType;
    private final PojoSerializer<O> serializer;
    private final List<ColumnDef> columns;

    public BlobRowCodec(Class<O> objectType) {
        this.objectType = objectType;
        this.serializer = createSerializer(objectType);
        this.columns = Collections.singletonList(new ColumnDef(VALUE_COLUMN, byte[].class));
    }

    @Override
    public List<ColumnDef> columns() {
        return columns;
    }

    @Override
    public void writeRow(O object, Object[] row, int offset, QueryOptions queryOptions) {
        row[offset] = serializer.serialize(object);
    }

    @Override
    public O readObject(ResultSet resultSet, int startIndex) throws SQLException {
        byte[] bytes = resultSet.getBytes(startIndex);
        return bytes == null ? null : serializer.deserialize(bytes);
    }

    public Class<O> getObjectType() {
        return objectType;
    }

    /**
     * Instantiates the serializer configured by {@link PersistenceConfig} on the object type.
     *
     * <p>When the type carries no such annotation, {@link KryoPojoSerializer} is used rather than
     * CQEngine's default, because CQEngine's default cannot serialize records and requires
     * {@code --add-opens java.base/java.util=ALL-UNNAMED} on Java 17+.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <O> PojoSerializer<O> createSerializer(Class<O> objectType) {
        PersistenceConfig persistenceConfig = objectType.getAnnotation(PersistenceConfig.class);
        if (persistenceConfig == null) {
            return new KryoPojoSerializer<>(objectType, PersistenceConfig.DEFAULT_CONFIG);
        }
        Class<? extends PojoSerializer> serializerClass = null;
        try {
            serializerClass = persistenceConfig.serializer();
            Constructor<?> constructor = serializerClass.getConstructor(Class.class, PersistenceConfig.class);
            return (PojoSerializer<O>) constructor.newInstance(objectType, persistenceConfig);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate PojoSerializer: " + serializerClass, e);
        }
    }
}
