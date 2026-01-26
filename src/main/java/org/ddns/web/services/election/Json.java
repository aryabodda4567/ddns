package org.ddns.web.services.election;
import com.google.gson.Gson;
import spark.Request;

public class Json {
    private static final Gson gson = new Gson();

    public static <T> T body(Request req, Class<T> cls) {
        T obj = gson.fromJson(req.body(), cls);
        if (obj == null) throw new IllegalArgumentException("Invalid JSON");
        return obj;
    }
}

