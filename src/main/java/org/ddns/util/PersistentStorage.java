package org.ddns.util;

import com.google.gson.Gson;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import org.ddns.bc.SignatureUtil;
import org.ddns.chain.Names;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe persistent key-value store that saves data to a JSON file.
 * <p>
 * Uses a ReadWriteLock to allow concurrent reads while ensuring exclusive writes.
 */
public class PersistentStorage {

    private static final String FILE_PATH = "storage.json";
    private static final Gson gson = new Gson();

    // Shared in-memory cache
    private static final Map<String, Object> storageMap = loadFromFileStatic();

    // Lock for thread safety (supports multiple readers, one writer)
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    // Load data from JSON file at startup
    private static Map<String, Object> loadFromFileStatic() {
        try (JsonReader reader = new JsonReader(new FileReader(FILE_PATH))) {
            reader.setStrictness(Strictness.LENIENT);
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> map = gson.fromJson(reader, type);
            return map != null ? map : new HashMap<>();
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    // Save the in-memory map to disk
    private static void saveToFileStatic() {
        try (FileWriter writer = new FileWriter(FILE_PATH)) {
            gson.toJson(storageMap, writer);
        } catch (IOException e) {
            System.err.println("[PersistentStorage] Failed to save storage: " + e.getMessage());
        }
    }

    /** Store or update a key-value pair */
    public static void put(String key, Object value) {
        lock.writeLock().lock();
        try {
            storageMap.put(key, value);
            saveToFileStatic();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Retrieve a string value */
    public static String getString(String key) {
        lock.readLock().lock();
        try {
            Object val = storageMap.get(key);
            return (val instanceof String) ? (String) val : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Retrieve an integer value */
    public static int getInt(String key) {
        lock.readLock().lock();
        try {
            Object val = storageMap.get(key);
            return (val instanceof Number) ? ((Number) val).intValue() : 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Delete a specific key */
    public static void delete(String key) {
        lock.writeLock().lock();
        try {
            storageMap.remove(key);
            saveToFileStatic();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Clear all data */
    public static void clear() {
        lock.writeLock().lock();
        try {
            storageMap.clear();
            saveToFileStatic();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Retrieve stored public key */
    public static PublicKey getPublicKey() throws Exception {
        String publicKeyJsonString = getString(Names.PUBLIC_KEY);
        return SignatureUtil.getPublicKeyFromString(publicKeyJsonString);
    }

    /** Retrieve stored private key */
    public static PrivateKey getPrivateKey() throws Exception {
        String privateKeyJsonString = getString(Names.PRIVATE_KEY);
        return SignatureUtil.getPrivateKeyFromString(privateKeyJsonString);
    }
}
