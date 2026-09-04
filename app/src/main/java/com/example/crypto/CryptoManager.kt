package com.example.crypto

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object CryptoManager {
    init {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        Security.removeProvider(BouncyCastlePQCProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastlePQCProvider())
    }

    // Argon2id
    fun deriveKey(password: String, walletKey: String): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(2)
            .withMemoryAsKB(16384) // 16MB standard mobile secure Argon2id
            .withParallelism(2)
            .withSalt(walletKey.toByteArray(Charsets.UTF_8))
            .build()
        
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val result = ByteArray(32) // 256-bit key
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), result, 0, result.size)
        return result
    }

    // XChaCha20-Poly1305
    fun encryptXChaCha20Poly1305(plaintext: ByteArray, key: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val aead = XChaCha20Poly1305(key)
        return aead.encrypt(plaintext, associatedData)
    }

    fun decryptXChaCha20Poly1305(ciphertext: ByteArray, key: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val aead = XChaCha20Poly1305(key)
        return aead.decrypt(ciphertext, associatedData)
    }

    // BLAKE2b (Merkle Tree / hashing)
    fun hashBlake2b(data: ByteArray): ByteArray {
        val digest = Blake2bDigest(256)
        digest.update(data, 0, data.size)
        val result = ByteArray(digest.digestSize)
        digest.doFinal(result, 0)
        return result
    }

    fun hashBlake2bPersonalized(data: ByteArray, personalizationTag: String = "TransactionSigningHash"): ByteArray {
        val keyBytes = personalizationTag.toByteArray(Charsets.UTF_8)
        val digest = Blake2bDigest(keyBytes, 32, null, null)
        digest.update(data, 0, data.size)
        val result = ByteArray(digest.digestSize)
        digest.doFinal(result, 0)
        return result
    }

    // Compute complete Blake2b Merkle Tree Root for a list of chunk hashes
    fun computeMerkleRoot(chunkHashes: List<ByteArray>): ByteArray {
        if (chunkHashes.isEmpty()) return hashBlake2b(ByteArray(0))
        var currentLevel = chunkHashes
        while (currentLevel.size > 1) {
            val nextLevel = mutableListOf<ByteArray>()
            for (i in currentLevel.indices step 2) {
                if (i + 1 < currentLevel.size) {
                    val combined = currentLevel[i] + currentLevel[i + 1]
                    nextLevel.add(hashBlake2b(combined))
                } else {
                    // Duplicate last odd node according to Kaspa / standard Merkle tree rules
                    val combined = currentLevel[i] + currentLevel[i]
                    nextLevel.add(hashBlake2b(combined))
                }
            }
            currentLevel = nextLevel
        }
        return currentLevel.first()
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // ML-DSA-87 (Dilithium) Signatures
    fun generateMLDSAKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Dilithium", BouncyCastlePQCProvider.PROVIDER_NAME)
        return kpg.generateKeyPair()
    }
    
    // Primary: NIST Post-Quantum ML-DSA-65 / Dilithium3 / ML-DSA; Secondary: Ed25519 fallback
    fun generateSignKeyPairFallback(): KeyPair {
        val pqcAlgos = listOf("ML-DSA-65", "ML-DSA", "ML-DSA-87", "ML-DSA-44", "Dilithium3", "Dilithium", "Dilithium2", "Dilithium5", "Falcon-512")
        for (algo in pqcAlgos) {
            try {
                val kpg = KeyPairGenerator.getInstance(algo, BouncyCastlePQCProvider.PROVIDER_NAME)
                return kpg.generateKeyPair()
            } catch (e: Throwable) {
                // Continue trying next post-quantum algorithm
            }
        }
        // Fallback to Ed25519 if runtime environment has no native PQC bytecode registered
        val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        return kpg.generateKeyPair()
    }

    fun sign(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val candidateProviders = listOf(
            BouncyCastlePQCProvider.PROVIDER_NAME,
            BouncyCastleProvider.PROVIDER_NAME,
            null
        )
        for (provider in candidateProviders) {
            try {
                val sig = if (provider != null) Signature.getInstance(privateKey.algorithm, provider) else Signature.getInstance(privateKey.algorithm)
                sig.initSign(privateKey)
                sig.update(data)
                return sig.sign()
            } catch (e: Throwable) {
                // Continue trying next provider
            }
        }
        val sig = Signature.getInstance(privateKey.algorithm)
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    fun verify(data: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        val candidateProviders = listOf(
            BouncyCastlePQCProvider.PROVIDER_NAME,
            BouncyCastleProvider.PROVIDER_NAME,
            null
        )
        for (provider in candidateProviders) {
            try {
                val sig = if (provider != null) Signature.getInstance(publicKey.algorithm, provider) else Signature.getInstance(publicKey.algorithm)
                sig.initVerify(publicKey)
                sig.update(data)
                return sig.verify(signature)
            } catch (e: Throwable) {
                // Continue trying next provider
            }
        }
        val sig = Signature.getInstance(publicKey.algorithm)
        sig.initVerify(publicKey)
        sig.update(data)
        return sig.verify(signature)
    }

    fun getPublicKey(encoded: ByteArray, algorithm: String = "Ed25519"): PublicKey {
        val spec = X509EncodedKeySpec(encoded)
        val candidateAlgos = listOf(algorithm, "ML-DSA-65", "ML-DSA", "ML-DSA-87", "ML-DSA-44", "Dilithium3", "Dilithium", "Dilithium2", "Falcon-512", "Ed25519")
        val candidateProviders = listOf(
            BouncyCastleProvider.PROVIDER_NAME,
            BouncyCastlePQCProvider.PROVIDER_NAME,
            null
        )
        for (algo in candidateAlgos) {
            for (provider in candidateProviders) {
                try {
                    val kf = if (provider != null) KeyFactory.getInstance(algo, provider) else KeyFactory.getInstance(algo)
                    return kf.generatePublic(spec)
                } catch (e: Throwable) {
                    // Try next
                }
            }
        }
        throw IllegalArgumentException("Unable to decode public key specification")
    }

    fun getPrivateKey(encoded: ByteArray, algorithm: String = "Ed25519"): PrivateKey {
        val spec = PKCS8EncodedKeySpec(encoded)
        val candidateAlgos = listOf(algorithm, "ML-DSA-65", "ML-DSA", "ML-DSA-87", "ML-DSA-44", "Dilithium3", "Dilithium", "Dilithium2", "Falcon-512", "Ed25519")
        val candidateProviders = listOf(
            BouncyCastleProvider.PROVIDER_NAME,
            BouncyCastlePQCProvider.PROVIDER_NAME,
            null
        )
        for (algo in candidateAlgos) {
            for (provider in candidateProviders) {
                try {
                    val kf = if (provider != null) KeyFactory.getInstance(algo, provider) else KeyFactory.getInstance(algo)
                    return kf.generatePrivate(spec)
                } catch (e: Throwable) {
                    // Try next
                }
            }
        }
        throw IllegalArgumentException("Unable to decode private key specification")
    }

    // --- Open Source Cryptographic Password Generator & Strength Detector ---

    data class PasswordStrength(
        val score: Int, // 0 to 4
        val label: String,
        val entropyBits: Double,
        val isValidMinLength: Boolean, // >= 8 chars
        val hasUppercase: Boolean,
        val hasLowercase: Boolean,
        val hasDigits: Boolean,
        val hasSymbols: Boolean
    )

    private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?"

    /**
     * Generate a cryptographically secure random password (minimum 8 characters)
     */
    fun generateCryptographicPassword(
        length: Int = 16,
        useUpper: Boolean = true,
        useLower: Boolean = true,
        useDigits: Boolean = true,
        useSymbols: Boolean = true
    ): String {
        val actualLength = length.coerceAtLeast(8)
        val charPool = StringBuilder()
        val guaranteedChars = mutableListOf<Char>()
        val random = SecureRandom()

        if (useUpper) {
            charPool.append(UPPERCASE)
            guaranteedChars.add(UPPERCASE[random.nextInt(UPPERCASE.length)])
        }
        if (useLower) {
            charPool.append(LOWERCASE)
            guaranteedChars.add(LOWERCASE[random.nextInt(LOWERCASE.length)])
        }
        if (useDigits) {
            charPool.append(DIGITS)
            guaranteedChars.add(DIGITS[random.nextInt(DIGITS.length)])
        }
        if (useSymbols) {
            charPool.append(SYMBOLS)
            guaranteedChars.add(SYMBOLS[random.nextInt(SYMBOLS.length)])
        }

        if (charPool.isEmpty()) {
            charPool.append(LOWERCASE).append(DIGITS)
        }

        val pool = charPool.toString()
        val resultChars = mutableListOf<Char>()
        resultChars.addAll(guaranteedChars)

        while (resultChars.size < actualLength) {
            resultChars.add(pool[random.nextInt(pool.length)])
        }

        // Shuffle securely
        for (i in resultChars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val temp = resultChars[i]
            resultChars[i] = resultChars[j]
            resultChars[j] = temp
        }

        return resultChars.joinToString("")
    }

    /**
     * Detect password cryptographic strength and Shannon / pool entropy
     */
    fun detectPasswordStrength(password: String): PasswordStrength {
        val len = password.length
        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasDigits = password.any { it.isDigit() }
        val hasSymbols = password.any { !it.isLetterOrDigit() }

        var poolSize = 0
        if (hasLower) poolSize += 26
        if (hasUpper) poolSize += 26
        if (hasDigits) poolSize += 10
        if (hasSymbols) poolSize += 32

        val entropy = if (len > 0 && poolSize > 0) {
            len * (Math.log(poolSize.toDouble()) / Math.log(2.0))
        } else 0.0

        val isValidMin = len >= 8

        val score = when {
            len == 0 -> 0
            len < 8 -> 0
            entropy < 40 -> 1
            entropy < 60 -> 2
            entropy < 80 -> 3
            else -> 4
        }

        val label = when (score) {
            0 -> if (len < 8) "Minimum 8 characters required" else "Very Weak"
            1 -> "Weak"
            2 -> "Moderate"
            3 -> "Strong"
            else -> "Cryptographic Grade (8+ chars)"
        }

        return PasswordStrength(
            score = score,
            label = label,
            entropyBits = entropy,
            isValidMinLength = isValidMin,
            hasUppercase = hasUpper,
            hasLowercase = hasLower,
            hasDigits = hasDigits,
            hasSymbols = hasSymbols
        )
    }
}
