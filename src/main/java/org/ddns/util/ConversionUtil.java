package org.ddns.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.ddns.bc.PublicKeyAdapter;

import java.lang.reflect.Type;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for converting between Java objects and JSON strings.
 * <p>
 * This class uses Gson for serialization and deserialization.
 * It provides simple static helper methods for converting any object
 * to JSON and vice versa.
 * </p>
 */
public class ConversionUtil {

    // Use a single shared Gson instance for efficiency
    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(PublicKey.class, new PublicKeyAdapter())
            .create();

    /**
     * Converts any Java object into its JSON string representation.
     *
     * @param object The object to serialize.
     * @return The JSON string representation of the object.
     */
    public static String toJson(Object object) {
        if (object == null) {
            return null;
        }
        return gson.toJson(object);
    }

    /**
     * Converts a JSON string into an object of the specified class type.
     *
     * @param jsonString The JSON string to convert.
     * @param tClass     The class type to deserialize into.
     * @param <T>        The generic type parameter representing the object type.
     * @return An instance of the specified class type, or {@code null} if input is invalid.
     */
    public static <T> T fromJson(String jsonString, Class<T> tClass) {
        if (jsonString == null || jsonString.isEmpty() || tClass == null) {
            return null;
        }
        return gson.fromJson(jsonString, tClass);
    }


    /**
     * Converts a JSON string into a generic object using a {@link TypeToken}.
     * <p>
     * This method is useful when working with parameterized types such as
     * {@code HashMap<String, String>}, {@code List<Block>}, etc.,
     * where {@link Class<T>} is insufficient due to Java type erasure.
     * </p>
     *
     * <pre>
     * Example usage:
     * Type type = new TypeToken<HashMap<String, String>>() {}.getType();
     * HashMap<String, String> map = ConversionUtil.fromJson(jsonString, type);
     * </pre>
     *
     * @param jsonString The JSON string to deserialize.
     * @param type       The {@link Type} describing the object structure (e.g., from {@link TypeToken}).
     * @param <T>        The generic type parameter representing the target object type.
     * @return The deserialized object of the specified type, or {@code null} if input is invalid.
     */
    public static <T> T fromJson(String jsonString, Type type) {
        if (jsonString == null || jsonString.isEmpty() || type == null) {
            return null;
        }
        return gson.fromJson(jsonString, type);
    }

    /**
     * Converts a JSON string into a {@code Map<String, String>}.
     *
     * <p>
     * This is a convenience method for quick conversion of flat JSON
     * objects like {@code {"key":"value","domain":"example.com"}}.
     * </p>
     *
     * @param jsonString The JSON string to convert.
     * @return A {@code Map<String, String>} representation of the JSON,
     * or {@code null} if input is invalid.
     */
    public static Map<String, String> jsonToMap(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return null;
        }
        Type type = new TypeToken<HashMap<String, String>>() {
        }.getType();
        return gson.fromJson(jsonString, type);
    }

    /**
     * Converts a JSON string into a {@code List<T>} of objects.
     *
     * <p>
     * Example usage:
     * <pre>
     * List<Block> blocks = ConversionUtil.jsonToList(jsonString, Block.class);
     * </pre>
     * </p>
     *
     * @param jsonString The JSON string to convert.
     * @param tClass     The class of elements in the list.
     * @param <T>        The type of the list elements.
     * @return A list of objects of the given type, or {@code null} if input is invalid.
     */
    public static <T> List<T> jsonToList(String jsonString, Class<T> tClass) {
        if (jsonString == null || jsonString.isEmpty() || tClass == null) {
            return null;
        }
        Type type = TypeToken.getParameterized(List.class, tClass).getType();
        return gson.fromJson(jsonString, type);
    }

    /**
     * Converts a JSON string into a {@code Set<T>} of objects.
     *
     * <p>
     * Example usage:
     * <pre>
     * Set<String> names = ConversionUtil.jsonToSet(jsonString, String.class);
     * </pre>
     * </p>
     *
     * @param jsonString The JSON string to convert.
     * @param tClass     The class of elements in the set.
     * @param <T>        The type of the set elements.
     * @return A set of objects of the given type, or {@code null} if input is invalid.
     */
    public static <T> Set<T> jsonToSet(String jsonString, Class<T> tClass) {
        if (jsonString == null || jsonString.isEmpty() || tClass == null) {
            return null;
        }
        Type type = TypeToken.getParameterized(Set.class, tClass).getType();
        return gson.fromJson(jsonString, type);
    }

    /**
     * Converts a JSON string into a {@link Map} with {@link String} keys and {@link Object} values.
     * <p>
     * This method uses Google's Gson library to parse a JSON object string into a generic
     * Java Map. The map's values will be of type {@link Object}, which could represent
     * strings, numbers, booleans, {@link java.util.List}s (for JSON arrays), or
     * nested {@link Map}s (for nested JSON objects).
     * <p>
     * Note: This method assumes a {@code Gson} instance named {@code gson} is available
     * in the class scope where this method is defined.
     *
     * @param jsonString The JSON string to be parsed. Must represent a valid JSON object.
     * @return A {@code Map<String, Object>} representation of the JSON data.
     * Returns {@code null} if the input {@code jsonString} is null or empty.
     */
    public static Map<String, Object> jsonToMapObject(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return null;
        }
        // Defines the specific type (HashMap<String, Object>) for Gson's deserialization
        Type type = new TypeToken<HashMap<String, Object>>() {
        }.getType();
        // 'gson' is assumed to be an initialized Gson object (e.g., private static Gson gson = new Gson();)
        return gson.fromJson(jsonString, type);
    }

    /**
     * Converts a Java object into its JSON string representation.
     *
     * @param object the object to convert
     * @return JSON string, or null if object is null
     */
    public static String toJsonString(Object object) {
        if (object == null) {
            return null;
        }
        return gson.toJson(object);
    }


}
