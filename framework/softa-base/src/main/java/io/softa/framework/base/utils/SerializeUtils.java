package io.softa.framework.base.utils;

import io.softa.framework.base.exception.IllegalArgumentException;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.Base64;

@Slf4j
public class SerializeUtils {

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
            Object obj = ois.readObject();
            if (targetClass.isInstance(obj)) {
                return targetClass.cast(obj);
            } else {
                throw new IllegalArgumentException("Deserialized object is not an instance of {0}, object: {1}",
                        targetClass.getName(), obj);
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}
