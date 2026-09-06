package com.duckcq.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import com.googlecode.cqengine.persistence.support.serialization.PersistenceConfig;
import com.googlecode.cqengine.persistence.support.serialization.PojoSerializer;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.ByteArrayOutputStream;

/**
 * The serializer used for BLOB storage.
 *
 * <p>It exists because CQEngine's own {@code KryoSerializer} does not work on a modern JVM out of
 * the box: it registers serializers from the {@code kryo-serializers} library which reflect into
 * {@code java.util} internals, so it throws {@code InaccessibleObjectException} on Java 17+ unless
 * the application is started with {@code --add-opens java.base/java.util=ALL-UNNAMED}. This
 * serializer configures Kryo directly and needs no such flags, and handles Java records.</p>
 *
 * <p>To use a different serializer, annotate the object type with
 * {@link PersistenceConfig @PersistenceConfig(serializer = ...)}; that is honoured as usual.</p>
 *
 * <p>Note that fields holding the JDK's internal collection wrappers - {@code Arrays.asList(...)},
 * {@code Collections.unmodifiableList(...)} and friends - still cannot be serialized without
 * {@code --add-opens}. Use plain {@code ArrayList}, {@code HashSet} etc., or store those objects
 * with a {@link com.duckcq.layout.ColumnarLayout} instead.</p>
 */
public class KryoPojoSerializer<O> implements PojoSerializer<O> {

    private final Class<O> objectType;
    private final boolean polymorphic;
    private final ThreadLocal<Kryo> kryoCache;

    public KryoPojoSerializer(Class<O> objectType, PersistenceConfig persistenceConfig) {
        this.objectType = objectType;
        this.polymorphic = persistenceConfig != null && persistenceConfig.polymorphic();
        this.kryoCache = ThreadLocal.withInitial(this::createKryo);
    }

    protected Kryo createKryo() {
        Kryo kryo = new Kryo();
        // Prefer a no-arg constructor, and fall back to Objenesis for classes which have none.
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true);
        kryo.register(objectType);
        return kryo;
    }

    @Override
    public byte[] serialize(O object) {
        if (object == null) {
            throw new NullPointerException("Object was null");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            try (Output output = new Output(bytes)) {
                Kryo kryo = kryoCache.get();
                if (polymorphic) {
                    kryo.writeClassAndObject(output, object);
                }
                else {
                    kryo.writeObject(output, object);
                }
            }
            return bytes.toByteArray();
        }
        catch (Throwable e) {
            throw new IllegalStateException("Failed to serialize an object of type " + objectType.getName()
                    + ". Set @PersistenceConfig(polymorphic = true) if the collection holds a mix of types, "
                    + "or store the collection with a ColumnarLayout to avoid serialization entirely.", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public O deserialize(byte[] bytes) {
        try (Input input = new Input(bytes)) {
            Kryo kryo = kryoCache.get();
            return polymorphic ? (O) kryo.readClassAndObject(input) : kryo.readObject(input, objectType);
        }
        catch (Throwable e) {
            throw new IllegalStateException("Failed to deserialize an object of type " + objectType.getName(), e);
        }
    }
}
