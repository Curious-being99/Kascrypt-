package com.example

import com.example.crypto.CryptoManager
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun cryptographicPasswordGenerator_createsStrongPassword() {
        val password = CryptoManager.generateCryptographicPassword(16)
        assertEquals(16, password.length)
        assertTrue("Must have at least 8 characters", password.length >= 8)
        
        val strength = CryptoManager.detectPasswordStrength(password)
        assertTrue("Must satisfy >= 8 characters", strength.isValidMinLength)
        assertTrue("Score must be >= 3", strength.score >= 3)
        assertTrue("Entropy must be high (>60 bits)", strength.entropyBits > 60.0)
    }

    @Test
    fun passwordStrengthDetector_identifiesWeakAndStrongPasswords() {
        val weak = CryptoManager.detectPasswordStrength("abc")
        assertEquals(0, weak.score)
        assertFalse(weak.isValidMinLength)

        val weak8 = CryptoManager.detectPasswordStrength("abcdefgh")
        assertTrue(weak8.isValidMinLength)
        assertTrue(weak8.score <= 2)

        val strong = CryptoManager.detectPasswordStrength("K@sp4!2026SecureKey")
        assertTrue(strong.isValidMinLength)
        assertTrue(strong.hasUppercase)
        assertTrue(strong.hasLowercase)
        assertTrue(strong.hasDigits)
        assertTrue(strong.hasSymbols)
        assertEquals(4, strong.score)
    }

    @Test
    fun schnorr_signingAndVerification_isBip340Compliant() {
        val wallet = com.example.crypto.KaspaWalletManager.createWallet()
        val msg = "Kaspa File System Test Sighash 2026".toByteArray(Charsets.UTF_8)
        val msgHash = CryptoManager.hashBlake2bPersonalized(msg, "TransactionSigningHash")
        
        val sig = com.example.crypto.KaspaWalletManager.signSchnorr(msgHash, wallet.privateKeyHex)
        assertEquals(64, sig.size)
        
        val pubKeyBytes = CryptoManager.hexToBytes(wallet.publicKeyHex)
        val isValid = com.example.crypto.KaspaWalletManager.verifySchnorr(msgHash, pubKeyBytes, sig)
        assertTrue("Schnorr BIP-340 signature must verify with public key", isValid)
    }

    @Test
    fun cryptoManager_keyPairGenerationAndVerification_works() {
        val keyPair = CryptoManager.generateSignKeyPairFallback()
        val data = "Hello Post-Quantum KasCrypt".toByteArray(Charsets.UTF_8)
        val sig = CryptoManager.sign(data, keyPair.private)
        val isValid = CryptoManager.verify(data, sig, keyPair.public)
        assertTrue("Signature verification must pass for generated keypair", isValid)

        val pubKey = CryptoManager.getPublicKey(keyPair.public.encoded, keyPair.public.algorithm)
        val privKey = CryptoManager.getPrivateKey(keyPair.private.encoded, keyPair.private.algorithm)
        val sig2 = CryptoManager.sign(data, privKey)
        val isValid2 = CryptoManager.verify(data, sig2, pubKey)
        assertTrue("Signature verification must pass after encoded key reconstruction", isValid2)
    }

    @Test
    fun backupArchive_encryptionSerializationAndRestoration_cycleWorks() {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val archiveAdapter = moshi.adapter(com.example.model.EncryptedVaultBackupArchive::class.java)
        val payloadAdapter = moshi.adapter(com.example.model.VaultBackupPayload::class.java)

        val testItems = listOf(
            com.example.model.VaultItem(
                id = "item-1",
                title = "Kaspa Seed Phrase Backup",
                content = "apple banana cherry dog elephant fox golf hotel",
                timestamp = 1700000000000L
            ),
            com.example.model.VaultItem(
                id = "item-2",
                title = "Exchange Password",
                content = "SuperSecurePass123!",
                timestamp = 1700000005000L
            )
        )
        val imageMap = mapOf("photo.jpg" to "dGVzdF9pbWFnZV9ieXRlcw==")

        val originalPayload = com.example.model.VaultBackupPayload(
            items = testItems,
            imageAssets = imageMap
        )

        val key = CryptoManager.deriveKey("masterPassword123", "randomWalletKeySalt456")
        val payloadJson = payloadAdapter.toJson(originalPayload)
        val encryptedPayload = CryptoManager.encryptXChaCha20Poly1305(payloadJson.toByteArray(Charsets.UTF_8), key)
        val b64Payload = java.util.Base64.getEncoder().encodeToString(encryptedPayload)

        val keyPair = CryptoManager.generateSignKeyPairFallback()
        val sig = CryptoManager.sign(encryptedPayload, keyPair.private)
        val sigHex = CryptoManager.bytesToHex(sig)

        val archive = com.example.model.EncryptedVaultBackupArchive(
            format = "KASCRYPT_ENCRYPTED_VAULT_BACKUP",
            version = 1,
            timestamp = System.currentTimeMillis(),
            itemCount = testItems.size,
            imageCount = imageMap.size,
            saltHex = CryptoManager.bytesToHex("randomWalletKeySalt456".toByteArray(Charsets.UTF_8)),
            encryptedPayloadBase64 = b64Payload,
            signatureHex = sigHex
        )

        val archiveJson = archiveAdapter.toJson(archive)
        assertNotNull(archiveJson)
        assertTrue(archiveJson.contains("KASCRYPT_ENCRYPTED_VAULT_BACKUP"))

        // Now simulate Restore
        val restoredArchive = archiveAdapter.fromJson(archiveJson)
        assertNotNull(restoredArchive)
        assertEquals(2, restoredArchive!!.itemCount)
        assertEquals(1, restoredArchive.imageCount)

        val restoredEncBytes = java.util.Base64.getDecoder().decode(restoredArchive.encryptedPayloadBase64)
        val decryptedPlaintext = CryptoManager.decryptXChaCha20Poly1305(restoredEncBytes, key)
        val restoredPayload = payloadAdapter.fromJson(String(decryptedPlaintext, Charsets.UTF_8))
        assertNotNull(restoredPayload)
        assertEquals(2, restoredPayload!!.items.size)
        assertEquals("Kaspa Seed Phrase Backup", restoredPayload.items[0].title)
        assertEquals("SuperSecurePass123!", restoredPayload.items[1].content)
        assertEquals("dGVzdF9pbWFnZV9ieXRlcw==", restoredPayload.imageAssets["photo.jpg"])
    }
}
