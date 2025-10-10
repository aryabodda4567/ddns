package org.ddns.util;

import com.google.gson.Gson;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class PersistentStorage {

    private static final String FILE_PATH = "storage.json";
    private static final Gson gson = new Gson();
    private static final Map<String, Object> storageMap = loadFromFileStatic();

    private static Map<String, Object> loadFromFileStatic() {
        try (JsonReader reader = new JsonReader(new FileReader(FILE_PATH))) {
            reader.setStrictness(Strictness.LENIENT);
            Type type = new TypeToken<Map<String, Object>>() {
            }.getType();
            Map<String, Object> map = gson.fromJson(reader, type);
            return map != null ? map : new HashMap<>();
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private static void saveToFileStatic() {
        try (FileWriter writer = new FileWriter(FILE_PATH)) {
            gson.toJson(storageMap, writer);
        } catch (IOException e) {
            System.err.println("Failed to save storage: " + e.getMessage());
        }
    }

    public static synchronized void put(String key, Object value) {
        storageMap.put(key, value);
        saveToFileStatic();
    }

    public static synchronized String getString(String key) {
        Object val = storageMap.get(key);
        return (val instanceof String) ? (String) val : null;
    }

    public static synchronized int getInt(String key) {
        Object val = storageMap.get(key);
        return (val instanceof Number) ? ((Number) val).intValue() : 0;
    }

    public static synchronized void delete(String key) {
        storageMap.remove(key);
        saveToFileStatic();
    }

    public static synchronized void clear() {
        storageMap.clear();
        saveToFileStatic();
    }
}
