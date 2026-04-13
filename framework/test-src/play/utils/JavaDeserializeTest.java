package play.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;

@DisplayName("Java.serialize/deserialize whitelist tests")
public class JavaDeserializeTest {

    @Test
    @DisplayName("HashMap<Integer,Integer> round-trips through serialize/deserialize")
    void hashMapRoundTrip() throws Exception {
        HashMap<Integer, Integer> original = new HashMap<>();
        original.put(1, 100);
        original.put(2, 200);
        original.put(3, 300);

        byte[] serialized = Java.serialize(original);
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        Object deserialized = Java.deserialize(serialized);
        assertNotNull(deserialized);
        assertInstanceOf(HashMap.class, deserialized);
        @SuppressWarnings("unchecked")
        HashMap<Integer, Integer> result = (HashMap<Integer, Integer>) deserialized;
        assertEquals(original, result);
    }

    @Test
    @DisplayName("HashSet<Integer> round-trips through serialize/deserialize")
    void hashSetRoundTrip() throws Exception {
        HashSet<Integer> original = new HashSet<>();
        original.add(10);
        original.add(20);
        original.add(30);

        byte[] serialized = Java.serialize(original);
        assertNotNull(serialized);

        Object deserialized = Java.deserialize(serialized);
        assertNotNull(deserialized);
        assertInstanceOf(HashSet.class, deserialized);
        @SuppressWarnings("unchecked")
        HashSet<Integer> result = (HashSet<Integer>) deserialized;
        assertEquals(original, result);
    }

    @Test
    @DisplayName("deserialize rejects classes not in the whitelist")
    void rejectsNonWhitelistedClass() throws Exception {
        // Serialize a java.io.File using raw ObjectOutputStream (bypasses Play's whitelist)
        java.io.File file = new java.io.File("/tmp/test");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(file);
        }
        byte[] rawSerialized = baos.toByteArray();

        // Java.deserialize should reject this (java.io.File is not in the whitelist)
        // It may return null or throw an exception — either is acceptable security behavior
        try {
            Object result = Java.deserialize(rawSerialized);
            // If it didn't throw, it must have returned null (filtered out)
            assertNull(result, "Expected null for non-whitelisted class java.io.File");
        } catch (Exception e) {
            // Any exception (InvalidClassException, etc.) is also acceptable — the point is rejection
            // Just verify it's not a ClassCastException sneaking through
            assertFalse(e instanceof ClassCastException,
                "Should not throw ClassCastException for filtered class");
        }
    }

    @Test
    @DisplayName("deserialize handles null/empty input gracefully")
    void handlesNullOrEmptyInput() {
        // null input
        assertDoesNotThrow(() -> {
            Object result = Java.deserialize(null);
            // null input should return null or throw a clear exception, not NPE
        }, "null input should not cause unexpected NPE");

        // empty byte array
        assertDoesNotThrow(() -> {
            Object result = Java.deserialize(new byte[0]);
        }, "empty byte array should be handled gracefully");
    }
}
