package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.crypto.BiometricAuthManager
import com.example.crypto.CryptoManager
import com.example.db.AppConfigEntity
import com.example.db.VaultDatabase
import com.example.db.VaultEntryEntity
import com.example.model.EncryptedVaultBackupArchive
import com.example.model.VaultBackupPayload
import com.example.model.VaultItem
import com.example.network.KaspaAddressBalanceResponse
import com.example.network.KaspaBlockDagResponse
import com.example.network.KaspaNetwork
import com.example.network.KaspaNetworkInfoResponse
import com.example.network.KaspaTransactionDetailResponse
import com.example.network.KaspaUtxoEntry
import com.example.network.KfsBroadcastResult
import com.example.network.KfsChunk
import com.example.network.KfsEngine
import com.example.network.KfsManifest
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.PrivateKey
import java.security.PublicKey
import java.util.UUID

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(
        application,
        VaultDatabase::class.java, "vault-db"
    ).build()

    private val moshi = Moshi.Builder().build()
    private val vaultItemAdapter = moshi.adapter(VaultItem::class.java)
    private val vaultItemListType = Types.newParameterizedType(List::class.java, VaultItem::class.java)
    private val vaultItemListAdapter = moshi.adapter<List<VaultItem>>(vaultItemListType)
    private val manifestAdapter = moshi.adapter(KfsManifest::class.java)
    private val chunkListType = Types.newParameterizedType(List::class.java, KfsChunk::class.java)
    private val chunkListAdapter = moshi.adapter<List<KfsChunk>>(chunkListType)
    private val backupArchiveAdapter = moshi.adapter(EncryptedVaultBackupArchive::class.java)
    private val backupPayloadAdapter = moshi.adapter(VaultBackupPayload::class.java)

    private val _uiState = MutableStateFlow<VaultUiState>(VaultUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _unlockErrorMessage = MutableStateFlow<String?>(null)
    val unlockErrorMessage = _unlockErrorMessage.asStateFlow()

    private val _isUnlocking = MutableStateFlow(false)
    val isUnlocking = _isUnlocking.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(false)
    val isBiometricEnabled = _isBiometricEnabled.asStateFlow()

    private val _isAutoLockEnabled = MutableStateFlow(true)
    val isAutoLockEnabled = _isAutoLockEnabled.asStateFlow()

    private val _biometricStatus = MutableStateFlow(BiometricAuthManager.BiometricStatus.UNAVAILABLE)
    val biometricStatus = _biometricStatus.asStateFlow()

    private val _vaultItems = MutableStateFlow<List<VaultItem>>(emptyList())
    val rawVaultItems = _vaultItems.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val filteredVaultItems = combine(_vaultItems, _searchQuery) { items, query ->
        if (query.isBlank()) {
            items
        } else {
            items.filter {
                it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
            }
        }
    }

    private val _kaspaNetworkStatus = MutableStateFlow<String>("Checking network...")
    val kaspaNetworkStatus = _kaspaNetworkStatus.asStateFlow()

    private val _dagInfo = MutableStateFlow<KaspaBlockDagResponse?>(null)
    val dagInfo = _dagInfo.asStateFlow()

    private val _addressLookupResult = MutableStateFlow<String?>(null)
    val addressLookupResult = _addressLookupResult.asStateFlow()

    private val _txLookupResult = MutableStateFlow<String?>(null)
    val txLookupResult = _txLookupResult.asStateFlow()

    private val _kaspaWallet = MutableStateFlow<com.example.crypto.KaspaWalletManager.KaspaWallet?>(null)
    val kaspaWallet = _kaspaWallet.asStateFlow()

    private val _kaspaWalletBalance = MutableStateFlow<Double>(0.0)
    val kaspaWalletBalance = _kaspaWalletBalance.asStateFlow()

    private val _kaspaWalletSompis = MutableStateFlow<Long>(0L)
    val kaspaWalletSompis = _kaspaWalletSompis.asStateFlow()

    private val _kaspaWalletUtxos = MutableStateFlow<List<KaspaUtxoEntry>>(emptyList())
    val kaspaWalletUtxos = _kaspaWalletUtxos.asStateFlow()

    private val _isRefreshingBalance = MutableStateFlow<Boolean>(false)
    val isRefreshingBalance = _isRefreshingBalance.asStateFlow()

    val kfsUploadProgress = KfsEngine.uploadProgress
    val kfsUploadStatus = KfsEngine.uploadStatus
    val kfsLastResult = KfsEngine.lastResult

    private var derivedKey: ByteArray? = null
    private var privateKey: PrivateKey? = null
    private var publicKey: PublicKey? = null
    private var walletKey: String = ""
    private var activeSessionPassword: String? = null

    private var autoLockJob: Job? = null
    private val AUTO_LOCK_TIMEOUT = 5 * 60 * 1000L // 5 minutes

    init {
        checkSetup()
        checkKaspaNetwork()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun checkKaspaNetwork() {
        viewModelScope.launch {
            try {
                val info = KaspaNetwork.api.getNetworkInfo()
                val dag = KaspaNetwork.api.getBlockDagInfo()
                _dagInfo.value = dag
                _kaspaNetworkStatus.value = "Node: ${KaspaNetwork.getCurrentUrl()}\nNetwork: ${info.networkId ?: "kaspa-mainnet"}\nBlocks: ${dag.blockCount ?: 0} | DAA Score: ${dag.virtualDaaScore ?: 0} | Difficulty: ${String.format("%.2f", dag.difficulty ?: 0.0)}"
            } catch (e: Exception) {
                _kaspaNetworkStatus.value = "Kaspa Network Notice: ${e.message}"
            }
        }
    }

    fun updateKaspaNodeUrl(url: String) {
        KaspaNetwork.setCustomNodeUrl(url)
        _kaspaNetworkStatus.value = "Connecting to $url..."
        checkKaspaNetwork()
    }

    fun checkKaspaAddressBalance(address: String) {
        if (address.isBlank()) return
        viewModelScope.launch {
            _addressLookupResult.value = "Querying BlockDAG for $address..."
            try {
                val cleanAddress = address.trim()
                val balanceResp = KaspaNetwork.api.getAddressBalance(cleanAddress)
                val utxos = KaspaNetwork.api.getAddressUtxos(cleanAddress)
                val sompis = balanceResp.balance ?: 0L
                val kasAmount = sompis.toDouble() / 100_000_000.0
                _addressLookupResult.value = "Address: $cleanAddress\nBalance: $kasAmount KAS ($sompis sompis)\nUTXOs available: ${utxos.size}"
            } catch (e: Exception) {
                _addressLookupResult.value = "Lookup Error: ${e.message}"
            }
        }
    }

    fun checkKaspaTransaction(txId: String) {
        if (txId.isBlank()) return
        viewModelScope.launch {
            _txLookupResult.value = "Querying Kaspa BlockDAG transaction $txId..."
            try {
                val cleanTxId = txId.trim()
                val tx = KaspaNetwork.api.getTransaction(cleanTxId)
                val status = if (tx.isAccepted == true) "Accepted on DAG" else "Pending / In Mempool"
                _txLookupResult.value = "TxId: ${tx.transactionId ?: cleanTxId}\nStatus: $status\nMass: ${tx.mass ?: 0}\nPayload size: ${tx.payload?.length ?: 0} chars"
            } catch (e: Exception) {
                _txLookupResult.value = "Transaction Query Info: ${e.message}"
            }
        }
    }

    fun broadcastVaultToKaspa() {
        viewModelScope.launch {
            val key = derivedKey ?: return@launch
            val priv = privateKey ?: return@launch

            val currentItems = _vaultItems.value
            val vaultJson = vaultItemListAdapter.toJson(currentItems)
            val vaultBytes = vaultJson.toByteArray(Charsets.UTF_8)
            
            // Refresh wallet balance and UTXOs in real-time first
            val currentWallet = _kaspaWallet.value
            var utxoList = _kaspaWalletUtxos.value
            if (currentWallet != null) {
                try {
                    val balanceResp = KaspaNetwork.api.getAddressBalance(currentWallet.address)
                    val freshUtxos = KaspaNetwork.api.getAddressUtxos(currentWallet.address)
                    val sompis = balanceResp.balance ?: 0L
                    _kaspaWalletSompis.value = sompis
                    _kaspaWalletBalance.value = sompis.toDouble() / 100_000_000.0
                    _kaspaWalletUtxos.value = freshUtxos
                    utxoList = freshUtxos
                } catch (e: Exception) {
                    // fallback to cached utxos
                }
            }

            // Real XChaCha20-Poly1305 encryption of complete backup archive
            val ciphertext = CryptoManager.encryptXChaCha20Poly1305(vaultBytes, key)
            
            val result = KfsEngine.uploadToKaspa(
                data = ciphertext,
                fileId = UUID.randomUUID().toString(),
                wallet = currentWallet,
                utxos = utxoList
            )

            if (result.success && currentWallet != null) {
                try {
                    val balanceResp = KaspaNetwork.api.getAddressBalance(currentWallet.address)
                    val freshUtxos = KaspaNetwork.api.getAddressUtxos(currentWallet.address)
                    val sompis = balanceResp.balance ?: 0L
                    _kaspaWalletSompis.value = sompis
                    _kaspaWalletBalance.value = sompis.toDouble() / 100_000_000.0
                    _kaspaWalletUtxos.value = freshUtxos
                } catch (ignored: Exception) {
                    // Non-critical background refresh failure
                }
            }
        }
    }

    suspend fun getDecryptedBitmap(context: Context, imagePath: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = derivedKey ?: return@withContext null
        try {
            // Path traversal prevention: sanitize filename
            val sanitizedName = File(imagePath).name
            val file = File(context.filesDir, sanitizedName)
            if (!file.canonicalPath.startsWith(context.filesDir.canonicalPath)) return@withContext null
            if (!file.exists()) return@withContext null
            
            val ciphertext = file.readBytes()
            val plaintext = CryptoManager.decryptXChaCha20Poly1305(ciphertext, key)
            
            // Safe decoding with bounds checking to prevent OOM
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size, boundsOptions)
            
            var sampleSize = 1
            val maxDimension = 1920
            while (boundsOptions.outWidth / sampleSize > maxDimension || boundsOptions.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size, decodeOptions)
            plaintext.fill(0) // Zeroize decrypted plaintext memory
            bitmap
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    fun deleteEntry(context: Context, id: String, imagePath: String? = null) {
        viewModelScope.launch {
            if (imagePath != null) {
                try {
                    val sanitizedName = File(imagePath).name
                    val file = File(context.filesDir, sanitizedName)
                    if (file.canonicalPath.startsWith(context.filesDir.canonicalPath) && file.exists()) {
                        // Secure file erasure: overwrite with random/zeros before deletion
                        try {
                            val length = file.length()
                            if (length > 0) {
                                java.io.RandomAccessFile(file, "rws").use { raf ->
                                    val randomBytes = ByteArray(minOf(length, 4096).toInt())
                                    java.security.SecureRandom().nextBytes(randomBytes)
                                    var remaining = length
                                    while (remaining > 0) {
                                        val toWrite = minOf(remaining, randomBytes.size.toLong()).toInt()
                                        raf.write(randomBytes, 0, toWrite)
                                        remaining -= toWrite
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Proceed to delete
                        }
                        file.delete()
                    }
                } catch (e: Exception) {
                    // Ignore file delete error
                }
            }
            db.vaultDao().deleteEntry(id)
            loadItems()
            resetAutoLockTimer()
        }
    }

    private fun checkSetup() {
        viewModelScope.launch {
            val existingWalletKey = db.vaultDao().getConfig("wallet_key")
            val bioEnabled = db.vaultDao().getConfig("biometric_enabled") == "true"
            val hasBioPayload = db.vaultDao().getConfig("biometric_payload") != null
            _isBiometricEnabled.value = bioEnabled && hasBioPayload
            val autoLockConfig = db.vaultDao().getConfig("auto_lock_enabled")
            _isAutoLockEnabled.value = autoLockConfig != "false"

            if (existingWalletKey == null) {
                _uiState.value = VaultUiState.Setup
            } else {
                walletKey = existingWalletKey
                _uiState.value = VaultUiState.Locked
            }
        }
    }

    fun updateBiometricHardwareStatus(context: Context) {
        _biometricStatus.value = BiometricAuthManager.checkBiometricStatus(context)
    }

    fun verifyMasterPassword(password: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                if (password.isBlank()) {
                    onResult(false, "Password cannot be empty.")
                    return@launch
                }
                val testDerived = withContext(Dispatchers.Default) {
                    CryptoManager.deriveKey(password, walletKey)
                }
                val authVerifier = db.vaultDao().getConfig("auth_verifier")
                if (authVerifier != null) {
                    val verifierBytes = kotlin.io.encoding.Base64.Default.decode(authVerifier)
                    val decrypted = CryptoManager.decryptXChaCha20Poly1305(verifierBytes, testDerived)
                    val isValid = String(decrypted, Charsets.UTF_8) == "KASCRYPT_VALID_VAULT_TOKEN"
                    if (isValid) {
                        onResult(true, null)
                    } else {
                        onResult(false, "Incorrect master password.")
                    }
                } else {
                    // Fallback comparison with active derivedKey if available
                    val isValid = derivedKey != null && testDerived.contentEquals(derivedKey)
                    if (isValid) {
                        onResult(true, null)
                    } else {
                        onResult(false, "Incorrect master password.")
                    }
                }
            } catch (e: Exception) {
                onResult(false, "Invalid master password.")
            }
        }
    }

    fun enableBiometric(password: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                // Verify password against auth_verifier first
                val testDerived = withContext(Dispatchers.Default) {
                    CryptoManager.deriveKey(password, walletKey)
                }
                val authVerifier = db.vaultDao().getConfig("auth_verifier")
                if (authVerifier != null) {
                    val verifierBytes = kotlin.io.encoding.Base64.Default.decode(authVerifier)
                    val decrypted = CryptoManager.decryptXChaCha20Poly1305(verifierBytes, testDerived)
                    if (String(decrypted, Charsets.UTF_8) != "KASCRYPT_VALID_VAULT_TOKEN") {
                        onResult(false, "Incorrect master password.")
                        return@launch
                    }
                }

                val (payload, iv) = BiometricAuthManager.encryptMasterSecret(password)
                db.vaultDao().insertConfig(AppConfigEntity("biometric_enabled", "true"))
                db.vaultDao().insertConfig(AppConfigEntity("biometric_payload", payload))
                db.vaultDao().insertConfig(AppConfigEntity("biometric_iv", iv))
                _isBiometricEnabled.value = true
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "Failed to enable biometric security.")
            }
        }
    }

    fun disableBiometric(onResult: () -> Unit) {
        viewModelScope.launch {
            db.vaultDao().deleteConfig("biometric_enabled")
            db.vaultDao().deleteConfig("biometric_payload")
            db.vaultDao().deleteConfig("biometric_iv")
            BiometricAuthManager.clearBiometricKey()
            _isBiometricEnabled.value = false
            onResult()
        }
    }

    fun unlockWithBiometricCredentials(onFailure: (String) -> Unit) {
        viewModelScope.launch {
            _isUnlocking.value = true
            _unlockErrorMessage.value = null
            try {
                val payload = db.vaultDao().getConfig("biometric_payload")
                val iv = db.vaultDao().getConfig("biometric_iv")
                if (payload.isNullOrEmpty() || iv.isNullOrEmpty()) {
                    _isUnlocking.value = false
                    _unlockErrorMessage.value = "Biometrics not configured for this vault."
                    onFailure("Biometrics not configured.")
                    return@launch
                }

                val decryptedPassword = BiometricAuthManager.decryptMasterSecret(payload, iv)
                unlock(decryptedPassword)
            } catch (e: Exception) {
                _isUnlocking.value = false
                _unlockErrorMessage.value = "Biometric unlock failed: ${e.localizedMessage}"
                onFailure("Biometric decryption failed. Please use your master password.")
            }
        }
    }

    fun setup(password: String, enableBiometrics: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = VaultUiState.Loading
            _unlockErrorMessage.value = null
            
            withContext(Dispatchers.Default) {
                // 1. Generate new Wallet Key (32-byte secure salt)
                val newWalletKey = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
                walletKey = newWalletKey
                db.vaultDao().insertConfig(AppConfigEntity("wallet_key", newWalletKey))

                // 2. Key derivation for vault root
                val derived = CryptoManager.deriveKey(password, newWalletKey)

                // 3. Generate signature keys and encrypt private key with derivedKey
                val keyPair = CryptoManager.generateSignKeyPairFallback()
                val privEncrypted = CryptoManager.encryptXChaCha20Poly1305(keyPair.private.encoded, derived)
                val privEncoded = kotlin.io.encoding.Base64.Default.encode(privEncrypted)
                val pubEncoded = kotlin.io.encoding.Base64.Default.encode(keyPair.public.encoded)
                
                db.vaultDao().insertConfig(AppConfigEntity("ml_dsa_priv", privEncoded))
                db.vaultDao().insertConfig(AppConfigEntity("ml_dsa_pub", pubEncoded))
                db.vaultDao().insertConfig(AppConfigEntity("ml_dsa_algo", keyPair.private.algorithm))

                // 4. Store auth verifier token for precise password verification
                val verifierPlain = "KASCRYPT_VALID_VAULT_TOKEN".toByteArray(Charsets.UTF_8)
                val verifierEnc = CryptoManager.encryptXChaCha20Poly1305(verifierPlain, derived)
                val verifierBase64 = kotlin.io.encoding.Base64.Default.encode(verifierEnc)
                db.vaultDao().insertConfig(AppConfigEntity("auth_verifier", verifierBase64))

                if (enableBiometrics) {
                    try {
                        val (payload, iv) = BiometricAuthManager.encryptMasterSecret(password)
                        db.vaultDao().insertConfig(AppConfigEntity("biometric_enabled", "true"))
                        db.vaultDao().insertConfig(AppConfigEntity("biometric_payload", payload))
                        db.vaultDao().insertConfig(AppConfigEntity("biometric_iv", iv))
                        _isBiometricEnabled.value = true
                    } catch (e: Exception) {
                        // If biometric key enrollment fails, proceed with standard password setup
                    }
                }
            }

            // Unlock immediately
            unlock(password)
        }
    }

    fun unlock(password: String) {
        viewModelScope.launch {
            _isUnlocking.value = true
            _unlockErrorMessage.value = null
            try {
                // Compute Argon2id on background thread
                val derived = withContext(Dispatchers.Default) {
                    CryptoManager.deriveKey(password, walletKey)
                }

                // Check auth verifier if stored
                val authVerifier = db.vaultDao().getConfig("auth_verifier")
                if (authVerifier != null) {
                    try {
                        val verifierBytes = kotlin.io.encoding.Base64.Default.decode(authVerifier)
                        val decrypted = CryptoManager.decryptXChaCha20Poly1305(verifierBytes, derived)
                        val token = String(decrypted, Charsets.UTF_8)
                        if (token != "KASCRYPT_VALID_VAULT_TOKEN") {
                            _unlockErrorMessage.value = "Incorrect master password. Please try again."
                            _isUnlocking.value = false
                            _uiState.value = VaultUiState.Locked
                            return@launch
                        }
                    } catch (e: Exception) {
                        _unlockErrorMessage.value = "Incorrect master password. Please verify and try again."
                        _isUnlocking.value = false
                        _uiState.value = VaultUiState.Locked
                        return@launch
                    }
                }

                derivedKey = derived
                activeSessionPassword = password
                
                // Load ML-DSA / Ed25519 keys safely
                try {
                    val privStr = db.vaultDao().getConfig("ml_dsa_priv")
                    val pubStr = db.vaultDao().getConfig("ml_dsa_pub")
                    val algo = db.vaultDao().getConfig("ml_dsa_algo") ?: "Ed25519"
                    if (privStr != null && pubStr != null) {
                        val privBytes = kotlin.io.encoding.Base64.Default.decode(privStr)
                        val pubBytes = kotlin.io.encoding.Base64.Default.decode(pubStr)
                        val decryptedPriv = try {
                            CryptoManager.decryptXChaCha20Poly1305(privBytes, derived)
                        } catch (e: Exception) {
                            // Fallback for legacy unencrypted private key entries
                            privBytes
                        }
                        privateKey = CryptoManager.getPrivateKey(decryptedPriv, algo)
                        publicKey = CryptoManager.getPublicKey(pubBytes, algo)
                    } else {
                        val keyPair = CryptoManager.generateSignKeyPairFallback()
                        privateKey = keyPair.private
                        publicKey = keyPair.public
                    }
                } catch (e: Exception) {
                    val keyPair = CryptoManager.generateSignKeyPairFallback()
                    privateKey = keyPair.private
                    publicKey = keyPair.public
                }

                loadItems()
                loadOrInitKaspaWallet(derivedKey!!)
                _uiState.value = VaultUiState.Unlocked
                resetAutoLockTimer()
            } catch (e: Exception) {
                _unlockErrorMessage.value = "Unlock failed: ${e.localizedMessage ?: "Cryptographic error"}"
                _uiState.value = VaultUiState.Locked
            } finally {
                _isUnlocking.value = false
            }
        }
    }

    fun resetVault() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.clearAllTables()
            }
            BiometricAuthManager.clearBiometricKey()
            _isBiometricEnabled.value = false
            walletKey = ""
            lock()
            _uiState.value = VaultUiState.Setup
        }
    }

    private suspend fun loadOrInitKaspaWallet(key: ByteArray) {
        try {
            val encMnemonic = db.vaultDao().getConfig("kaspa_mnemonic_enc")
            val mnemonic: String
            if (encMnemonic != null) {
                val encBytes = kotlin.io.encoding.Base64.Default.decode(encMnemonic)
                val decBytes = CryptoManager.decryptXChaCha20Poly1305(encBytes, key)
                mnemonic = String(decBytes, Charsets.UTF_8)
                decBytes.fill(0) // Zeroize decrypted mnemonic buffer
            } else {
                val newWallet = com.example.crypto.KaspaWalletManager.createWallet(12)
                mnemonic = newWallet.mnemonic
                val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
                val encBytes = CryptoManager.encryptXChaCha20Poly1305(mnemonicBytes, key)
                mnemonicBytes.fill(0) // Zeroize plaintext bytes
                val encBase64 = kotlin.io.encoding.Base64.Default.encode(encBytes)
                db.vaultDao().insertConfig(AppConfigEntity("kaspa_mnemonic_enc", encBase64))
            }
            val wallet = com.example.crypto.KaspaWalletManager.deriveWalletFromMnemonic(mnemonic)
            _kaspaWallet.value = wallet
            refreshKaspaWalletBalance()
        } catch (e: Exception) {
            val wallet = com.example.crypto.KaspaWalletManager.deriveWalletFromPrivateKey(key)
            _kaspaWallet.value = wallet
            refreshKaspaWalletBalance()
        }
    }

    fun refreshKaspaWalletBalance() {
        val address = _kaspaWallet.value?.address ?: return
        viewModelScope.launch {
            _isRefreshingBalance.value = true
            try {
                val balanceResp = KaspaNetwork.api.getAddressBalance(address)
                val utxos = KaspaNetwork.api.getAddressUtxos(address)
                val sompis = balanceResp.balance ?: 0L
                _kaspaWalletSompis.value = sompis
                _kaspaWalletBalance.value = sompis.toDouble() / 100_000_000.0
                _kaspaWalletUtxos.value = utxos
            } catch (e: Exception) {
                // Network notice handled gracefully
            } finally {
                _isRefreshingBalance.value = false
            }
        }
    }

    fun importKaspaSeed(mnemonic: String, passphrase: String = ""): Pair<Boolean, String?> {
        val key = derivedKey ?: return Pair(false, "Vault is locked.")
        return try {
            val clean = mnemonic.trim().lowercase()
            val validation = com.example.crypto.KaspaWalletManager.validateMnemonic(clean)
            if (validation is com.example.crypto.MnemonicValidationResult.Invalid) {
                return Pair(false, validation.error)
            }
            val wallet = com.example.crypto.KaspaWalletManager.deriveWalletFromMnemonic(clean, passphrase)
            val cleanBytes = clean.toByteArray(Charsets.UTF_8)
            val encBytes = CryptoManager.encryptXChaCha20Poly1305(cleanBytes, key)
            cleanBytes.fill(0) // Zeroize plaintext bytes
            val encBase64 = kotlin.io.encoding.Base64.Default.encode(encBytes)
            viewModelScope.launch {
                db.vaultDao().insertConfig(AppConfigEntity("kaspa_mnemonic_enc", encBase64))
                _kaspaWallet.value = wallet
                refreshKaspaWalletBalance()
            }
            resetAutoLockTimer()
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Failed to import seed phrase.")
        }
    }

    fun generateNewKaspaSeed(wordCount: Int = 12) {
        val key = derivedKey ?: return
        viewModelScope.launch {
            val newWallet = com.example.crypto.KaspaWalletManager.createWallet(wordCount)
            val mnemonicBytes = newWallet.mnemonic.toByteArray(Charsets.UTF_8)
            val encBytes = CryptoManager.encryptXChaCha20Poly1305(mnemonicBytes, key)
            mnemonicBytes.fill(0)
            val encBase64 = kotlin.io.encoding.Base64.Default.encode(encBytes)
            db.vaultDao().insertConfig(AppConfigEntity("kaspa_mnemonic_enc", encBase64))
            _kaspaWallet.value = newWallet
            refreshKaspaWalletBalance()
        }
    }

    fun onAppBackgrounded() {
        if (_uiState.value is VaultUiState.Unlocked) {
            lock()
        }
    }

    fun lock() {
        derivedKey?.fill(0) // Erase from memory
        derivedKey = null
        activeSessionPassword = null
        privateKey = null
        publicKey = null
        _kaspaWallet.value = null
        _kaspaWalletBalance.value = 0.0
        _kaspaWalletSompis.value = 0L
        _kaspaWalletUtxos.value = emptyList()
        _vaultItems.value = emptyList()
        autoLockJob?.cancel()
        _uiState.value = VaultUiState.Locked
    }

    fun toggleAutoLock(enabled: Boolean) {
        _isAutoLockEnabled.value = enabled
        viewModelScope.launch {
            db.vaultDao().insertConfig(AppConfigEntity("auto_lock_enabled", enabled.toString()))
            if (enabled) {
                resetAutoLockTimer()
            } else {
                autoLockJob?.cancel()
            }
        }
    }

    fun resetAutoLockTimer() {
        autoLockJob?.cancel()
        if (!_isAutoLockEnabled.value) return
        autoLockJob = viewModelScope.launch {
            delay(AUTO_LOCK_TIMEOUT)
            lock()
        }
    }

    private suspend fun loadItems() {
        val key = derivedKey ?: return
        val pub = publicKey
        val priv = privateKey
        
        val entities = db.vaultDao().getAllEntries()
        val items = mutableListOf<VaultItem>()

        for (entity in entities) {
            try {
                // Decrypt (XChaCha20-Poly1305 authenticated encryption)
                val decryptedBytes = CryptoManager.decryptXChaCha20Poly1305(entity.ciphertext, key)
                val json = String(decryptedBytes, Charsets.UTF_8)
                
                val item = vaultItemAdapter.fromJson(json)
                if (item != null) {
                    if (pub != null && entity.signature.isNotEmpty()) {
                        val isSigValid = try {
                            CryptoManager.verify(entity.ciphertext, entity.signature, pub)
                        } catch (e: Exception) {
                            false
                        }
                        if (!isSigValid && priv != null) {
                            try {
                                val newSig = CryptoManager.sign(entity.ciphertext, priv)
                                db.vaultDao().insertEntry(entity.copy(signature = newSig))
                            } catch (e: Exception) {
                                // non-fatal
                            }
                        }
                    } else if (priv != null && entity.signature.isEmpty()) {
                        try {
                            val newSig = CryptoManager.sign(entity.ciphertext, priv)
                            db.vaultDao().insertEntry(entity.copy(signature = newSig))
                        } catch (e: Exception) {
                            // non-fatal
                        }
                    }
                    items.add(item)
                }
            } catch (e: Exception) {
                // Decryption failed: key does not match this entry
            }
        }
        _vaultItems.value = items.sortedByDescending { it.timestamp }
    }

    fun addEntry(title: String, content: String) {
        val key = derivedKey ?: return
        val priv = privateKey ?: return
        
        viewModelScope.launch {
            val item = VaultItem(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                content = content.trim(),
                timestamp = System.currentTimeMillis()
            )
            val json = vaultItemAdapter.toJson(item)
            val plaintext = json.toByteArray(Charsets.UTF_8)

            // Encrypt with XChaCha20-Poly1305
            val ciphertext = CryptoManager.encryptXChaCha20Poly1305(plaintext, key)
            
            // Sign ciphertext with ML-DSA Post-Quantum Key
            val signature = CryptoManager.sign(ciphertext, priv)

            val entity = VaultEntryEntity(
                id = item.id,
                ciphertext = ciphertext,
                signature = signature
            )
            
            db.vaultDao().insertEntry(entity)
            loadItems()
            resetAutoLockTimer()
        }
    }

    fun addImageEntry(
        context: Context, 
        uri: android.net.Uri, 
        title: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val key = derivedKey ?: run {
            onError?.invoke("Vault is locked. Please unlock first.")
            return
        }
        val priv = privateKey ?: run {
            onError?.invoke("Vault keys not initialized.")
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Safeguard against unbounded memory consumption (limit raw ingestion to 25MB)
                val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val data = ByteArray(16384)
                    var totalRead = 0
                    var n: Int
                    while (stream.read(data, 0, data.size).also { n = it } != -1) {
                        totalRead += n
                        if (totalRead > 25 * 1024 * 1024) {
                            throw IllegalArgumentException("Image exceeds 25MB safety threshold")
                        }
                        buffer.write(data, 0, n)
                    }
                    buffer.toByteArray()
                } ?: run {
                    withContext(Dispatchers.Main) {
                        onError?.invoke("Could not open selected image")
                    }
                    return@launch
                }
                
                if (rawBytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onError?.invoke("Selected image is empty")
                    }
                    return@launch
                }
                
                val id = UUID.randomUUID().toString()
                val filename = "enc_img_$id.dat"
                
                val ciphertext = CryptoManager.encryptXChaCha20Poly1305(rawBytes, key)
                val byteCount = rawBytes.size
                rawBytes.fill(0) // Zeroize raw plaintext bytes in memory
                
                context.openFileOutput(filename, Context.MODE_PRIVATE).use {
                    it.write(ciphertext)
                }
                
                val item = VaultItem(
                    id = id,
                    title = if (title.isBlank()) "Secured Image Asset" else title.trim(),
                    content = "[Protected Image: ${byteCount / 1024} KB encrypted with XChaCha20-Poly1305]",
                    timestamp = System.currentTimeMillis(),
                    imagePath = filename
                )
                
                val json = vaultItemAdapter.toJson(item)
                val plaintext = json.toByteArray(Charsets.UTF_8)
                val jsonCipher = CryptoManager.encryptXChaCha20Poly1305(plaintext, key)
                val signature = CryptoManager.sign(jsonCipher, priv)
                
                val entity = VaultEntryEntity(
                    id = id,
                    ciphertext = jsonCipher,
                    signature = signature
                )
                
                db.vaultDao().insertEntry(entity)
                loadItems()
                resetAutoLockTimer()
                withContext(Dispatchers.Main) {
                    onSuccess?.invoke()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.localizedMessage ?: "Failed to encrypt image")
                }
            }
        }
    }

    fun exportEncryptedBackup(
        context: Context,
        outputUri: android.net.Uri,
        onSuccess: (itemCount: Int, imageCount: Int, byteCount: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val key = derivedKey ?: run {
            onError("Vault must be unlocked to export backup")
            return
        }
        val priv = privateKey
        val salt = walletKey

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var currentItems = _vaultItems.value
                if (currentItems.isEmpty()) {
                    val entities = db.vaultDao().getAllEntries()
                    val loaded = mutableListOf<VaultItem>()
                    for (entity in entities) {
                        try {
                            val decryptedBytes = CryptoManager.decryptXChaCha20Poly1305(entity.ciphertext, key)
                            val json = String(decryptedBytes, Charsets.UTF_8)
                            val item = vaultItemAdapter.fromJson(json)
                            if (item != null) loaded.add(item)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    currentItems = loaded
                }

                if (currentItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onError("Cannot export backup: Your vault is empty. Please add items or seed phrases before exporting.")
                    }
                    return@launch
                }

                val imageMap = mutableMapOf<String, String>()
                for (item in currentItems) {
                    if (!item.imagePath.isNullOrBlank()) {
                        val sanitizedName = File(item.imagePath).name
                        val file = File(context.filesDir, sanitizedName)
                        if (file.exists()) {
                            val imgBytes = file.readBytes()
                            val base64 = android.util.Base64.encodeToString(imgBytes, android.util.Base64.NO_WRAP)
                            imageMap[sanitizedName] = base64
                        }
                    }
                }

                val payload = VaultBackupPayload(
                    items = currentItems,
                    imageAssets = imageMap
                )
                val payloadJson = backupPayloadAdapter.toJson(payload)
                val payloadPlaintext = payloadJson.toByteArray(Charsets.UTF_8)
                val encryptedPayload = CryptoManager.encryptXChaCha20Poly1305(payloadPlaintext, key)
                val encryptedPayloadBase64 = android.util.Base64.encodeToString(encryptedPayload, android.util.Base64.NO_WRAP)

                val signatureHex = if (priv != null) {
                    try {
                        val sig = CryptoManager.sign(encryptedPayload, priv)
                        CryptoManager.bytesToHex(sig)
                    } catch (e: Exception) {
                        null
                    }
                } else null

                val archive = EncryptedVaultBackupArchive(
                    format = "KASCRYPT_ENCRYPTED_VAULT_BACKUP",
                    version = 1,
                    timestamp = System.currentTimeMillis(),
                    itemCount = currentItems.size,
                    imageCount = imageMap.size,
                    saltHex = CryptoManager.bytesToHex(salt.toByteArray(Charsets.UTF_8)),
                    encryptedPayloadBase64 = encryptedPayloadBase64,
                    signatureHex = signatureHex
                )

                val archiveJson = backupArchiveAdapter.toJson(archive)
                val archiveBytes = archiveJson.toByteArray(Charsets.UTF_8)

                // 1. Primary standard SAF write: openOutputStream with truncate mode "wt"
                var verifiedBytes: ByteArray? = null

                try {
                    context.contentResolver.openOutputStream(outputUri, "wt")?.use { stream ->
                        stream.write(archiveBytes)
                        stream.flush()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Verify write immediately by reading back from content resolver
                verifiedBytes = try {
                    context.contentResolver.openInputStream(outputUri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }

                // 2. Fallback: default openOutputStream
                if (verifiedBytes == null || verifiedBytes.isEmpty()) {
                    try {
                        context.contentResolver.openOutputStream(outputUri)?.use { stream ->
                            stream.write(archiveBytes)
                            stream.flush()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    verifiedBytes = try {
                        context.contentResolver.openInputStream(outputUri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        null
                    }
                }

                // 3. Fallback: ParcelFileDescriptor with AutoCloseOutputStream
                if (verifiedBytes == null || verifiedBytes.isEmpty()) {
                    try {
                        context.contentResolver.openFileDescriptor(outputUri, "rwt")?.let { pfd ->
                            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { stream ->
                                stream.write(archiveBytes)
                                stream.flush()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    verifiedBytes = try {
                        context.contentResolver.openInputStream(outputUri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        null
                    }
                }

                if (verifiedBytes == null || verifiedBytes.isEmpty()) {
                    throw IllegalStateException("Storage provider failed to write backup data (resulted in 0 bytes). Please choose another storage directory or folder.")
                }

                val finalByteCount = verifiedBytes.size.toLong()

                withContext(Dispatchers.Main) {
                    onSuccess(currentItems.size, imageMap.size, finalByteCount)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Failed to export backup")
                }
            }
        }
    }

    fun createShareableBackupFile(context: Context): android.net.Uri? {
        val key = derivedKey ?: return null
        val priv = privateKey
        val salt = walletKey
        
        var currentItems = _vaultItems.value
        if (currentItems.isEmpty()) {
            val entities = kotlinx.coroutines.runBlocking(Dispatchers.IO) { db.vaultDao().getAllEntries() }
            val loaded = mutableListOf<VaultItem>()
            for (entity in entities) {
                try {
                    val decryptedBytes = CryptoManager.decryptXChaCha20Poly1305(entity.ciphertext, key)
                    val json = String(decryptedBytes, Charsets.UTF_8)
                    val item = vaultItemAdapter.fromJson(json)
                    if (item != null) loaded.add(item)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            currentItems = loaded
        }

        if (currentItems.isEmpty()) {
            return null
        }

        val imageMap = mutableMapOf<String, String>()
        for (item in currentItems) {
            if (!item.imagePath.isNullOrBlank()) {
                val sanitizedName = File(item.imagePath).name
                val file = File(context.filesDir, sanitizedName)
                if (file.exists()) {
                    val imgBytes = file.readBytes()
                    val base64 = android.util.Base64.encodeToString(imgBytes, android.util.Base64.NO_WRAP)
                    imageMap[sanitizedName] = base64
                }
            }
        }

        val payload = VaultBackupPayload(
            items = currentItems,
            imageAssets = imageMap
        )
        val payloadJson = backupPayloadAdapter.toJson(payload)
        val payloadPlaintext = payloadJson.toByteArray(Charsets.UTF_8)
        val encryptedPayload = CryptoManager.encryptXChaCha20Poly1305(payloadPlaintext, key)
        val encryptedPayloadBase64 = android.util.Base64.encodeToString(encryptedPayload, android.util.Base64.NO_WRAP)

        val signatureHex = if (priv != null) {
            val sig = CryptoManager.sign(encryptedPayload, priv)
            CryptoManager.bytesToHex(sig)
        } else null

        val archive = EncryptedVaultBackupArchive(
            format = "KASCRYPT_ENCRYPTED_VAULT_BACKUP",
            version = 1,
            timestamp = System.currentTimeMillis(),
            itemCount = currentItems.size,
            imageCount = imageMap.size,
            saltHex = CryptoManager.bytesToHex(salt.toByteArray(Charsets.UTF_8)),
            encryptedPayloadBase64 = encryptedPayloadBase64,
            signatureHex = signatureHex
        )

        val archiveJson = backupArchiveAdapter.toJson(archive)
        val backupFile = File(context.cacheDir, "kascrypt_backup_${System.currentTimeMillis()}.json")
        backupFile.writeText(archiveJson, Charsets.UTF_8)

        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            backupFile
        )
    }

    fun importEncryptedBackup(
        context: Context,
        inputUri: android.net.Uri,
        onSuccess: (itemCount: Int, imageCount: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val key = derivedKey ?: run {
            onError("Vault must be unlocked to import and decrypt backup")
            return
        }
        val priv = privateKey ?: run {
            onError("Vault keys not initialized")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rawBytes = context.contentResolver.openInputStream(inputUri)?.use { stream ->
                    stream.readBytes()
                } ?: throw IllegalArgumentException("Could not read selected file from storage")

                if (rawBytes.isEmpty()) {
                    throw IllegalArgumentException("Selected backup file is empty (0 bytes). Please ensure you selected a valid .json or .kascrypt backup file.")
                }

                val rawText = String(rawBytes, Charsets.UTF_8)
                val cleanJson = rawText.trim().removePrefix("\uFEFF").trim()

                var payload: VaultBackupPayload? = null

                // 1. Try parsing as EncryptedVaultBackupArchive (.json or .kascrypt)
                val archive = try {
                    backupArchiveAdapter.fromJson(cleanJson)
                } catch (e: Exception) {
                    null
                }

                if (archive != null && !archive.encryptedPayloadBase64.isNullOrBlank()) {
                    val encryptedBytes = try {
                        android.util.Base64.decode(archive.encryptedPayloadBase64, android.util.Base64.NO_WRAP)
                    } catch (e: Exception) {
                        android.util.Base64.decode(archive.encryptedPayloadBase64, android.util.Base64.DEFAULT)
                    }

                    // Try decrypting with active vault key
                    var decryptedBytes: ByteArray? = try {
                        CryptoManager.decryptXChaCha20Poly1305(encryptedBytes, key)
                    } catch (e: Exception) {
                        null
                    }

                    // Fallback: If decryption failed with active key, try deriving key using archive saltHex and session password
                    if (decryptedBytes == null && activeSessionPassword != null && !archive.saltHex.isNullOrBlank()) {
                        try {
                            val archiveSalt = String(CryptoManager.hexToBytes(archive.saltHex), Charsets.UTF_8)
                            val altKey = CryptoManager.deriveKey(activeSessionPassword!!, archiveSalt)
                            decryptedBytes = CryptoManager.decryptXChaCha20Poly1305(encryptedBytes, altKey)
                        } catch (e: Exception) {
                            // password or salt mismatch
                        }
                    }

                    if (decryptedBytes == null) {
                        throw IllegalStateException("Decryption failed: Key mismatch. Ensure you are logged into the vault using the master password that created this backup.")
                    }

                    val payloadJson = String(decryptedBytes, Charsets.UTF_8)
                    payload = try {
                        backupPayloadAdapter.fromJson(payloadJson)
                    } catch (e: Exception) {
                        null
                    }
                }

                // 2. Fallback: Try parsing directly as VaultBackupPayload (unencrypted JSON)
                if (payload == null) {
                    payload = try {
                        backupPayloadAdapter.fromJson(cleanJson)
                    } catch (e: Exception) {
                        null
                    }
                }

                // 3. Fallback: Try parsing as List<VaultItem> (raw list of items)
                if (payload == null) {
                    try {
                        val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, VaultItem::class.java)
                        val listAdapter = moshi.adapter<List<VaultItem>>(listType)
                        val itemsList = listAdapter.fromJson(cleanJson)
                        if (!itemsList.isNullOrEmpty()) {
                            payload = VaultBackupPayload(items = itemsList, imageAssets = emptyMap())
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                if (payload == null) {
                    throw IllegalArgumentException("Invalid or unrecognized backup file format")
                }

                if (payload.items.isEmpty() && payload.imageAssets.isEmpty()) {
                    throw IllegalArgumentException("Backup archive was decrypted, but contains no items or images to restore.")
                }

                var restoredImages = 0
                for ((filename, b64Data) in payload.imageAssets) {
                    try {
                        if (b64Data.isNotBlank()) {
                            val sanitizedName = File(filename).name
                            val imgFile = File(context.filesDir, sanitizedName)
                            val data = try {
                                android.util.Base64.decode(b64Data, android.util.Base64.NO_WRAP)
                            } catch (e: Exception) {
                                android.util.Base64.decode(b64Data, android.util.Base64.DEFAULT)
                            }
                            imgFile.writeBytes(data)
                            restoredImages++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                var restoredItems = 0
                for (item in payload.items) {
                    val itemId = if (item.id.isBlank()) java.util.UUID.randomUUID().toString() else item.id
                    val itemToSave = item.copy(id = itemId)
                    val itemJson = vaultItemAdapter.toJson(itemToSave)
                    val plaintext = itemJson.toByteArray(Charsets.UTF_8)
                    val ciphertext = CryptoManager.encryptXChaCha20Poly1305(plaintext, key)
                    val signature = try {
                        CryptoManager.sign(ciphertext, priv)
                    } catch (e: Exception) {
                        ByteArray(0)
                    }
                    val entity = VaultEntryEntity(
                        id = itemId,
                        ciphertext = ciphertext,
                        signature = signature
                    )
                    db.vaultDao().insertEntry(entity)
                    restoredItems++
                }

                loadItems()
                resetAutoLockTimer()

                withContext(Dispatchers.Main) {
                    onSuccess(restoredItems, restoredImages)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Failed to restore backup")
                }
            }
        }
    }
}

sealed class VaultUiState {
    object Loading : VaultUiState()
    object Setup : VaultUiState()
    object Locked : VaultUiState()
    object Unlocked : VaultUiState()
}
