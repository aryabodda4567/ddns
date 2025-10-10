package org.ddns.bc;


import com.google.gson.*;

import java.lang.reflect.Type;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class PrivateKeyAdapter implements JsonSerializer<PrivateKey>, JsonDeserializer<PrivateKey> {

    @Override
    public JsonElement serialize(PrivateKey src, Type typeOfSrc, JsonSerializationContext context) {
        // Convert the PrivateKey to a Base64 string
        return new JsonPrimitive(Base64.getEncoder().encodeToString(src.getEncoded()));
    }

    @Override
    public PrivateKey deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        try {
            // Get the string from the JSON element
            String keyStr = json.getAsString();
            // Decode the Base64 string back into bytes
            byte[] keyBytes = Base64.getDecoder().decode(keyStr);
            // Use a KeyFactory to reconstruct the PrivateKey object
            KeyFactory keyFactory = KeyFactory.getInstance("ECDSA", "BC");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new JsonParseException(e);
        }
    }
}