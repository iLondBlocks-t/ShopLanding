package com.ocpilot.app.core;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class Crypto {
    private static final String ALIAS = "ocpilot_token_key";
    private Crypto() { }
    private static SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
                generator.generateKey();
            }
            return (SecretKey) ks.getKey(ALIAS, null);
        } catch (Exception ex) { throw new GeneralSecurityException("keystore_unavailable"); }
    }
    public static String encrypt(String token) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] clear = token.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] encrypted = cipher.doFinal(clear), iv = cipher.getIV();
            if (iv.length != 12) throw new GeneralSecurityException("invalid_iv");
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length); System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.encodeToString(payload, Base64.NO_WRAP);
        } finally { Arrays.fill(clear, (byte) 0); }
    }
    public static String decrypt(String encoded) throws GeneralSecurityException {
        try {
            byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
            if (payload.length < 28) throw new GeneralSecurityException("invalid_ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, payload, 0, 12));
            byte[] clear = cipher.doFinal(payload, 12, payload.length - 12);
            try { return new String(clear, StandardCharsets.UTF_8); } finally { Arrays.fill(clear, (byte) 0); }
        } catch (Exception ex) { throw new GeneralSecurityException("keystore_decrypt_failed"); }
    }
}
