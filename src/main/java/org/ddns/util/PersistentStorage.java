package org.ddns.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * PersistentStorage provides simple key-value storage that supports both
 * String and Integer values. The data is automatically saved to a JSON file
 * so that it persists between program runs.
 */
public class PersistentStorage {

    private final Map<String, Object> storageMap;
    private final String filePath;
    private static final Gson gson = new Gson();

    /**
     * Creates a PersistentStorage instance backed by a given JSON file
     */
    public PersistentStorage( ) {
        this.filePath = "storage.json";
        this.storageMap = loadFromFile();
    }

    /**
     * Stores or updates a String value.
     *
     * @param key   The key for the value.
     * @param value The String value to store.
     */
    public  void put(String key, String value) {
        storageMap.put(key, value);
        saveToFile();
    }

    /**
     * Stores or updates an Integer value.
     *
     * @param key   The key for the value.
     * @param value The integer value to store.
     */
    public void put(String key, int value) {
        storageMap.put(key, value);
        saveToFile();
    }

    /**
     * Retrieves a String value for a given key.
     *
     * @param key The key to look up.
     * @return The String value, or null if not found.
     */
    public String getString(String key) {
        Object val = storageMap.get(key);
        return (val instanceof String) ? (String) val : null;
    }

    /**
     * Retrieves an integer value for a given key.
     *
     * @param key The key to look up.
     * @return The integer value, or 0 if not found or not an integer.
     */
    public int getInt(String key) {
        Object val = storageMap.get(key);
        return (val instanceof Number) ? ((Number) val).intValue() : 0;
    }

    /**
     * Deletes a key-value pair.
     *
     * @param key The key to remove.
     */
    public void delete(String key) {
        storageMap.remove(key);
        saveToFile();
    }

    /**
     * Saves the current storageMap to disk as JSON.
     */
    private void saveToFile() {
        try (FileWriter writer = new FileWriter(filePath)) {
            gson.toJson(storageMap, writer);
        } catch (IOException e) {
            System.err.println("Failed to save storage: " + e.getMessage());
        }
    }

    /**
     * Loads the data from disk if file exists, otherwise returns an empty map.
     *
     * @return The loaded map or a new empty one.
     */
    private Map<String, Object> loadFromFile() {
        try (FileReader reader = new FileReader(filePath)) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            return gson.fromJson(reader, type);
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    /**
     * Clears all stored data permanently.
     */
    public void clear() {
        storageMap.clear();
        saveToFile();
    }
}

