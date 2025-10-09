package org.ddns.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final Gson gson = new Gson();

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
     * @param tClass The class type to deserialize into.
     * @param <T> The generic type parameter representing the object type.
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
     * @param type The {@link Type} describing the object structure (e.g., from {@link TypeToken}).
     * @param <T> The generic type parameter representing the target object type.
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
     *         or {@code null} if input is invalid.
     */
    public static Map<String, String> jsonToMap(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return null;
        }
        Type type = new TypeToken<HashMap<String, String>>() {}.getType();
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
     * @param tClass The class of elements in the list.
     * @param <T> The type of the list elements.
     * @return A list of objects of the given type, or {@code null} if input is invalid.
     */
    public static <T> List<T> jsonToList(String jsonString, Class<T> tClass) {
        if (jsonString == null || jsonString.isEmpty() || tClass == null) {
            return null;
        }
        Type type = TypeToken.getParameterized(List.class, tClass).getType();
        return gson.fromJson(jsonString, type);
    }

}
