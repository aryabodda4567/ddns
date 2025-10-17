package org.ddns.chain;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.ddns.bc.SignatureUtil;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;

/**
 * A simple class to hold and manage a node's cryptographic identity (key pair).
 */
public class Wallet {


    public static KeyPair getKeyPair() {
        return SignatureUtil.generateKeyPair();
    }

    /**
     * Derives the corresponding PublicKey from a given PrivateKey.
     *
     * <p>This method reconstructs the public key using elliptic curve parameters
     * from the given private key. It performs the elliptic curve point
     * multiplication (Q = d × G), where:</p>
     *
     * <ul>
     *   <li>d = private scalar (from the private key)</li>
     *   <li>G = base point on the elliptic curve</li>
     * </ul>
     *
     * <p>This ensures that the resulting PublicKey is exactly the one that would
     * have been originally generated with the same private key.</p>
     *
     * @param privateKey The existing PrivateKey from which to derive the PublicKey.
     * @return The derived PublicKey corresponding to the provided PrivateKey.
     * @throws Exception If the key reconstruction fails or the provider is unavailable.
     */
    public static PublicKey getPublicKeyFromPrivateKey(PrivateKey privateKey) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("ECDSA", "BC");

        // Extract private key parameters (curve and scalar)
        ECPrivateKey ecPrivateKey = (ECPrivateKey) privateKey;
        ECParameterSpec params = ecPrivateKey.getParams();

        // Compute the public EC point Q = d * G
        ECPoint generator = params.getGenerator();
        BigInteger privateValue = ecPrivateKey.getS();

        // Multiply private scalar (d) with generator (G)
        ECPoint w = multiplyECPoint(generator, privateValue, params);

        // Build public key spec
        ECPublicKeySpec pubSpec = new ECPublicKeySpec(w, params);
        return keyFactory.generatePublic(pubSpec);
    }

    /**
     * Helper method to multiply ECPoint (G) by private scalar (d) on a given curve.
     * This uses Bouncy Castle EC math to perform point multiplication.
     */
    private static ECPoint multiplyECPoint(
            ECPoint generator,
            BigInteger scalar,
            ECParameterSpec params) throws Exception {

        // Use BouncyCastle EC math for point multiplication
        ECNamedCurveParameterSpec bcSpec =
                ECNamedCurveTable.getParameterSpec("prime256v1");

        org.bouncycastle.math.ec.ECPoint g = bcSpec.getG();
        org.bouncycastle.math.ec.ECPoint q = g.multiply(scalar).normalize();

        return new ECPoint(
                q.getAffineXCoord().toBigInteger(),
                q.getAffineYCoord().toBigInteger());
    }

}