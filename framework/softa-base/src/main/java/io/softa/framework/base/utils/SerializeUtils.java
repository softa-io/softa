package io.softa.framework.base.utils;

import java.io.*;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.exception.IllegalArgumentException;

@Slf4j
public class SerializeUtils {

    /**
     * Classes a payload may contain. Java deserialization instantiates whatever the stream names
     * before the caller ever sees the object, so the target-type check after {@code readObject}
     * comes too late to keep an unexpected class from running its {@code readObject} hook. The
     * framework only ships its own types, JDK values and collections, and JSON field values
     * (Jackson tree nodes, which serialize as their JSON text) across this boundary, so everything
     * else is rejected while the stream is being read. Subpackages of {@code java.*} are
     * deliberately absent ({@code java.lang.invoke}, {@code java.rmi}, ...), as is any other
     * third-party library. Depth and size limits bound what a single payload can allocate.
     */
    static final String DESERIALIZATION_FILTER = String.join(";",
            "maxdepth=64",
            "maxarray=1000000",
            "maxrefs=1000000",
            "maxbytes=67108864",
            "java.lang.*",
            "java.util.*",
            "java.time.*",
            "java.math.*",
            "tools.jackson.databind.node.*",
            "io.softa.**",
            "!*");

    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(DESERIALIZATION_FILTER);

    public static String serialize(Object object) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(object);
            oos.flush();
            byte[] serializedParams = bos.toByteArray();
            return Base64.getEncoder().encodeToString(serializedParams);
        } catch (IOException e) {
            log.error("Failed to serialize object: {}", object);
            throw new RuntimeException(e);
        }
    }

    public static <T> T deserialize(String serializedBase64, Class<T> targetClass) {
        byte[] serializedBytes = Base64.getDecoder().decode(serializedBase64);
        try (ByteArrayInputStream bis = new ByteArrayInputStream(serializedBytes);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            ois.setObjectInputFilter(FILTER);
            Object obj = ois.readObject();
            if (targetClass.isInstance(obj)) {
                return targetClass.cast(obj);
            } else {
                throw new IllegalArgumentException("Deserialized object is not an instance of {0}, object: {1}",
                        targetClass.getName(), obj);
            }
        } catch (InvalidClassException e) {
            throw new IllegalArgumentException("Deserialization rejected: {0}", e.getMessage());
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}
