package org.ddns.bc;


import com.google.gson.*;

import java.lang.reflect.Type;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class PublicKeyAdapter implements JsonSerializer<PublicKey>, JsonDeserializer<PublicKey> {

    @Override
    public JsonElement serialize(PublicKey src, Type typeOfSrc, JsonSerializationContext context) {
        // Convert the PublicKey to a Base64 string
        return new JsonPrimitive(Base64.getEncoder().encodeToString(src.getEncoded()));
    }

    @Override
    public PublicKey deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        try {
            // Get the string from the JSON element
            String keyStr = json.getAsString();
            // Decode the Base64 string back into bytes
            byte[] keyBytes = Base64.getDecoder().decode(keyStr);
            // Use a KeyFactory to reconstruct the PublicKey object
            KeyFactory keyFactory = KeyFactory.getInstance("ECDSA", "BC");
            return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new JsonParseException(e);
        }
    }
}
