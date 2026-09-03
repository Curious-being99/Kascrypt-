package com.example.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EncryptedVaultBackupArchive(
    val format: String = "KASCRYPT_ENCRYPTED_VAULT_BACKUP",
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val itemCount: Int,
    val imageCount: Int,
    val saltHex: String,
    val encryptedPayloadBase64: String,
    val signatureHex: String? = null
)

@JsonClass(generateAdapter = true)
data class VaultBackupPayload(
    val items: List<VaultItem>,
    val imageAssets: Map<String, String> = emptyMap() // filename -> Base64 ciphertext
)
