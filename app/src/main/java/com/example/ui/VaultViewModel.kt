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
import com.example.db.KfsBroadcastRecordEntity
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
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
    )
    .addMigrations(VaultDatabase.MIGRATION_1_2)
    .fallbackToDestructiveMigrationOnDowngrade()
    .build()

    private val moshi = Moshi.Builder().build()
    private val vaultItemAdapter = moshi.adapter(VaultItem::class.java)
    private val vaultItemListType = Types.newParameterizedType(List::class.java, VaultItem::class.java)
    private val vaultItemListAdapter = moshi.adapter<List<VaultItem>>(vaultItemListType)
    private val manifestAdapter = moshi.adapter(KfsManifest::class.java)
    private val chunkListType = Types.newParameterizedType(List::class.java, KfsChunk::class.java)
    private val chunkListAdapter = moshi.adapter<List<KfsChunk>>(chunkListType)
    private val backupArchiveAdapter = moshi.adapter(EncryptedVaultBackupArchive::class.java)
    private val backupPayloadAdapter = moshi.adapter(VaultBackupPayload::class.java)
    private val stringListType = Types.newParameterizedType(List::class.java, String::class.java)
    private val stringListAdapter = moshi.adapter<List<String>>(stringListType)

    val kfsBroadcastRecords = db.kfsDao().getAllRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRestoringFromKfs = MutableStateFlow(false)
    val isRestoringFromKfs = _isRestoringFromKfs.asStateFlow()

    private val _kfsRestoreProgress = MutableStateFlow(0f)
    val kfsRestoreProgress = _kfsRestoreProgress.asStateFlow()

    private val _kfsRestoreStatus = MutableStateFlow("")
    val kfsRestoreStatus = _kfsRestoreStatus.asStateFlow()

    private val _isSyncingKfsHistory = MutableStateFlow(false)
    val isSyncingKfsHistory = _isSyncingKfsHistory.asStateFlow()

    private val _syncKfsHistoryStatus = MutableStateFlow("")
    val syncKfsHistoryStatus = _syncKfsHistoryStatus.asStateFlow()

    private val _discoveredKfsCount = MutableStateFlow(0)
    val discoveredKfsCount = _discoveredKfsCount.asStateFlow()

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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.kfsDao().purgeFailedRecords()
            } catch (_: Exception) {}
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            val key = derivedKey ?: return@launch
            val priv = privateKey ?: return@launch
            val context = getApplication<Application>()

            val currentItems = _vaultItems.value
            val imagesMap = mutableMapOf<String, String>()
            for (item in currentItems) {
                if (!item.imagePath.isNullOrBlank()) {
                    try {
                        val sanitizedName = File(item.imagePath).name
                        val imgFile = File(context.filesDir, sanitizedName)
                        if (imgFile.exists()) {
                            val imgCiphertext = imgFile.readBytes()
                            val imgPlaintext = CryptoManager.decryptXChaCha20Poly1305(imgCiphertext, key)
                            val b64 = android.util.Base64.encodeToString(imgPlaintext, android.util.Base64.NO_WRAP)
                            imagesMap[sanitizedName] = b64
                        }
                    } catch (e: Exception) {
                        // ignore image read error
                    }
                }
            }

            val payload = VaultBackupPayload(items = currentItems, imageAssets = imagesMap)
            val payloadJson = backupPayloadAdapter.toJson(payload)
            val vaultBytes = payloadJson.toByteArray(Charsets.UTF_8)
            
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

            // Persist local cache files for reliable offline/fallback recovery
            try {
                val context = getApplication<Application>()
                File(context.cacheDir, "kfs_cache_${result.manifest.fileId}.dat").writeBytes(ciphertext)
                File(context.cacheDir, "kfs_cache_${result.manifest.merkleRoot}.dat").writeBytes(ciphertext)
                if (result.rootTxId != null) {
                    File(context.cacheDir, "kfs_cache_${result.rootTxId}.dat").writeBytes(ciphertext)
                }
            } catch (_: Exception) {}

            // Persist the broadcast record to the Room database ONLY if the broadcast succeeded on-chain
            if (result.success) {
                try {
                    val recordEntity = com.example.db.KfsBroadcastRecordEntity(
                        id = result.manifest.fileId,
                        title = "Vault Sync (${currentItems.size} items, ${imagesMap.size} images)",
                        manifestTxId = result.rootTxId ?: result.manifest.merkleRoot,
                        merkleRoot = result.manifest.merkleRoot,
                        chunkTxIdsJson = stringListAdapter.toJson(result.chunkTxIds),
                        totalChunks = result.manifest.totalChunks,
                        totalBytes = result.manifest.totalBytes,
                        totalFeeSompis = result.totalFeeSompis,
                        timestamp = result.manifest.timestamp,
                        status = "CONFIRMED",
                        errorMessage = null
                    )
                    db.kfsDao().insertRecord(recordEntity)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

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
                    onResult(false, "Authentication verifier not configured.")
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
                val keyPair = CryptoManager.generateMLDSAKeyPair()
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
                
                // Load ML-DSA keys safely
                try {
                    val privStr = db.vaultDao().getConfig("ml_dsa_priv")
                    val pubStr = db.vaultDao().getConfig("ml_dsa_pub")
                    val algo = db.vaultDao().getConfig("ml_dsa_algo") ?: "ML-DSA-65"
                    if (privStr != null && pubStr != null) {
                        val privBytes = kotlin.io.encoding.Base64.Default.decode(privStr)
                        val pubBytes = kotlin.io.encoding.Base64.Default.decode(pubStr)
                        val decryptedPriv = CryptoManager.decryptXChaCha20Poly1305(privBytes, derived)
                        privateKey = CryptoManager.getPrivateKey(decryptedPriv, algo)
                        publicKey = CryptoManager.getPublicKey(pubBytes, algo)
                    } else {
                        privateKey = null
                        publicKey = null
                    }
                } catch (e: Exception) {
                    privateKey = null
                    publicKey = null
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
            syncAddressKfsHistory(wallet.address, autoRestoreIfEmpty = true)
        } catch (e: Exception) {
            val wallet = com.example.crypto.KaspaWalletManager.deriveWalletFromPrivateKey(key)
            _kaspaWallet.value = wallet
            refreshKaspaWalletBalance()
            syncAddressKfsHistory(wallet.address, autoRestoreIfEmpty = true)
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
                syncAddressKfsHistory(wallet.address, autoRestoreIfEmpty = true)
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

                // 1. Primary standard SAF write: openOutputStream with "w" mode or default
                var writeSuccess = false
                var writeError: Exception? = null

                try {
                    context.contentResolver.openOutputStream(outputUri, "w")?.use { stream ->
                        stream.write(archiveBytes)
                        stream.flush()
                        writeSuccess = true
                    }
                } catch (e: Exception) {
                    writeError = e
                    try {
                        context.contentResolver.openOutputStream(outputUri)?.use { stream ->
                            stream.write(archiveBytes)
                            stream.flush()
                            writeSuccess = true
                        }
                    } catch (e2: Exception) {
                        writeError = e2
                    }
                }

                if (!writeSuccess) {
                    throw writeError ?: IllegalStateException("Storage provider failed to write backup data to selected location.")
                }

                withContext(Dispatchers.Main) {
                    onSuccess(currentItems.size, imageMap.size, archiveBytes.size.toLong())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Failed to export backup")
                }
            }
        }
    }

    fun saveBackupToDownloads(
        context: Context,
        onSuccess: (fileName: String, itemCount: Int, imageCount: Int, byteCount: Long) -> Unit,
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
                        onError("Cannot export backup: Your vault is empty. Please add items or seed phrases first.")
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
                val fileName = "kascrypt_vault_backup_${System.currentTimeMillis()}.json"

                var savedPath = ""
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(archiveBytes)
                            stream.flush()
                        }
                        savedPath = "Downloads/$fileName"
                    }
                }

                if (savedPath.isBlank()) {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val targetFile = File(downloadsDir, fileName)
                    targetFile.writeBytes(archiveBytes)
                    savedPath = targetFile.name
                }

                withContext(Dispatchers.Main) {
                    onSuccess(savedPath, currentItems.size, imageMap.size, archiveBytes.size.toLong())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Failed to save backup to Downloads folder")
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

    fun downloadVaultItemToDevice(
        context: Context,
        item: VaultItem,
        onSuccess: (savedPath: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val key = derivedKey ?: run {
            onError("Vault must be unlocked to download files")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sanitizedTitle = item.title.replace(Regex("[^a-zA-Z0-9_.-]"), "_").trim('_').ifBlank { "kascrypt_item" }
                var savedPath = ""

                if (!item.imagePath.isNullOrBlank()) {
                    val decryptedBitmap = getDecryptedBitmap(context, item.imagePath)
                        ?: run {
                            withContext(Dispatchers.Main) { onError("Could not decrypt image asset.") }
                            return@launch
                        }

                    val stream = java.io.ByteArrayOutputStream()
                    decryptedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    val imgBytes = stream.toByteArray()
                    val fileName = "${sanitizedTitle}_${System.currentTimeMillis()}.jpg"

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { os ->
                                os.write(imgBytes)
                                os.flush()
                            }
                            savedPath = "Downloads/$fileName"
                        }
                    }

                    if (savedPath.isBlank()) {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val targetFile = File(downloadsDir, fileName)
                        targetFile.writeBytes(imgBytes)
                        savedPath = "Downloads/${targetFile.name}"
                    }
                } else {
                    val fileName = "${sanitizedTitle}_${System.currentTimeMillis()}.txt"
                    val fileContent = "=== KASCRYPT SECURE ENTRY ===\nTitle: ${item.title}\nID: ${item.id}\nTimestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(item.timestamp))}\n\nContent:\n${item.content}\n"
                    val bytes = fileContent.toByteArray(Charsets.UTF_8)

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { os ->
                                os.write(bytes)
                                os.flush()
                            }
                            savedPath = "Downloads/$fileName"
                        }
                    }

                    if (savedPath.isBlank()) {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val targetFile = File(downloadsDir, fileName)
                        targetFile.writeBytes(bytes)
                        savedPath = "Downloads/${targetFile.name}"
                    }
                }

                withContext(Dispatchers.Main) {
                    onSuccess(savedPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Failed to save file to device")
                }
            }
        }
    }

    fun downloadKfsBackupToDevice(
        context: Context,
        manifestTxId: String,
        onSuccess: (savedPath: String, itemsCount: Int, imagesCount: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val key = derivedKey ?: run {
            onError("Vault must be unlocked to decrypt and download files")
            return
        }
        val priv = privateKey
        val salt = walletKey

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cleanId = manifestTxId.trim()
                var manifestAndCiphertext: Pair<KfsManifest, ByteArray>? = null
                try {
                    manifestAndCiphertext = KfsEngine.fetchAndReconstructFromKaspa(cleanId) { _, _ -> }
                } catch (e: Exception) {
                    val cacheFile1 = File(context.cacheDir, "kfs_cache_$cleanId.dat")
                    val cacheFile2 = File(context.filesDir, "kfs_cache_$cleanId.dat")
                    val allRecords = db.kfsDao().getAllRecords().firstOrNull() ?: emptyList()
                    val localRecord = db.kfsDao().getRecordById(cleanId)
                        ?: allRecords.find { it.manifestTxId == cleanId || it.merkleRoot == cleanId || it.id == cleanId }

                    val targetCacheFile = when {
                        cacheFile1.exists() -> cacheFile1
                        cacheFile2.exists() -> cacheFile2
                        localRecord != null && File(context.cacheDir, "kfs_cache_${localRecord.id}.dat").exists() -> File(context.cacheDir, "kfs_cache_${localRecord.id}.dat")
                        localRecord != null && File(context.cacheDir, "kfs_cache_${localRecord.merkleRoot}.dat").exists() -> File(context.cacheDir, "kfs_cache_${localRecord.merkleRoot}.dat")
                        localRecord != null && File(context.cacheDir, "kfs_cache_${localRecord.manifestTxId}.dat").exists() -> File(context.cacheDir, "kfs_cache_${localRecord.manifestTxId}.dat")
                        else -> null
                    }

                    if (targetCacheFile != null && targetCacheFile.exists()) {
                        val cachedBytes = targetCacheFile.readBytes()
                        val dummyManifest = KfsManifest(
                            fileId = localRecord?.id ?: cleanId,
                            timestamp = localRecord?.timestamp ?: System.currentTimeMillis(),
                            totalBytes = cachedBytes.size,
                            totalChunks = localRecord?.totalChunks ?: 1,
                            merkleRoot = localRecord?.merkleRoot ?: cleanId,
                            chunkHashes = emptyList(),
                            chunkTxIds = emptyList(),
                            algorithm = "XChaCha20-Poly1305+BLAKE2b",
                            signature = null
                        )
                        manifestAndCiphertext = Pair(dummyManifest, cachedBytes)
                    } else {
                        throw e
                    }
                }

                val (manifest, ciphertext) = manifestAndCiphertext!!
                val decryptedBytes = try {
                    CryptoManager.decryptXChaCha20Poly1305(ciphertext, key)
                } catch (e: Exception) {
                    throw IllegalStateException("Decryption failed. Please check master password.")
                }

                val decryptedText = String(decryptedBytes, Charsets.UTF_8).trim()
                val payload = parseVaultPayloadFlexible(decryptedText)
                    ?: throw IllegalStateException("Could not parse vault payload.")

                // Save as an encrypted portable backup file on device
                val payloadJson = backupPayloadAdapter.toJson(payload)
                val payloadPlaintext = payloadJson.toByteArray(Charsets.UTF_8)
                val encryptedPayload = CryptoManager.encryptXChaCha20Poly1305(payloadPlaintext, key)
                val encryptedPayloadBase64 = android.util.Base64.encodeToString(encryptedPayload, android.util.Base64.NO_WRAP)

                val signatureHex = if (priv != null) {
                    try {
                        val sig = CryptoManager.sign(encryptedPayload, priv)
                        CryptoManager.bytesToHex(sig)
                    } catch (_: Exception) { null }
                } else null

                val archive = EncryptedVaultBackupArchive(
                    format = "KASCRYPT_ENCRYPTED_VAULT_BACKUP",
                    version = 1,
                    timestamp = System.currentTimeMillis(),
                    itemCount = payload.items.size,
                    imageCount = payload.imageAssets.size,
                    saltHex = CryptoManager.bytesToHex(salt.toByteArray(Charsets.UTF_8)),
                    encryptedPayloadBase64 = encryptedPayloadBase64,
                    signatureHex = signatureHex
                )

                val archiveJson = backupArchiveAdapter.toJson(archive)
                val archiveBytes = archiveJson.toByteArray(Charsets.UTF_8)
                val fileName = "kascrypt_kfs_restored_${manifest.fileId.take(8)}_${System.currentTimeMillis()}.json"
                var savedPath = ""

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(archiveBytes)
                            os.flush()
                        }
                        savedPath = "Downloads/$fileName"
                    }
                }

                if (savedPath.isBlank()) {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val targetFile = File(downloadsDir, fileName)
                    targetFile.writeBytes(archiveBytes)
                    savedPath = "Downloads/${targetFile.name}"
                }

                withContext(Dispatchers.Main) {
                    onSuccess(savedPath, payload.items.size, payload.imageAssets.size)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Failed to download restored backup")
                }
            }
        }
    }

    private fun parseVaultPayloadFlexible(jsonString: String): VaultBackupPayload? {
        val clean = jsonString.trim().removePrefix("\uFEFF").trim()
        if (clean.isEmpty()) return null

        // 1. Try Moshi adapter
        try {
            val payload = backupPayloadAdapter.fromJson(clean)
            if (payload != null && (payload.items.isNotEmpty() || payload.imageAssets.isNotEmpty())) {
                return payload
            }
        } catch (_: Exception) {}

        // 2. Try Moshi List<VaultItem> adapter
        try {
            val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, VaultItem::class.java)
            val listAdapter = moshi.adapter<List<VaultItem>>(listType)
            val itemsList = listAdapter.fromJson(clean)
            if (!itemsList.isNullOrEmpty()) {
                return VaultBackupPayload(items = itemsList, imageAssets = emptyMap())
            }
        } catch (_: Exception) {}

        // 3. Try org.json.JSONObject / org.json.JSONArray
        try {
            if (clean.startsWith("[")) {
                val jsonArray = org.json.JSONArray(clean)
                val items = mutableListOf<VaultItem>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { obj.optString("uuid", java.util.UUID.randomUUID().toString()) }
                    val title = obj.optString("title").ifBlank { obj.optString("account", obj.optString("name", obj.optString("label", "Restored Entry"))) }
                    val content = obj.optString("content").ifBlank { obj.optString("secret", obj.optString("password", obj.optString("notes", obj.optString("value", "")))) }
                    val timestamp = if (obj.has("timestamp")) obj.optLong("timestamp") else if (obj.has("created")) obj.optLong("created") else System.currentTimeMillis()
                    val imgPath = obj.optString("imagePath").ifBlank { obj.optString("image", "").ifBlank { null } }
                    items.add(VaultItem(id = id, title = title, content = content, timestamp = timestamp, imagePath = imgPath))
                }
                if (items.isNotEmpty()) {
                    return VaultBackupPayload(items = items, imageAssets = emptyMap())
                }
            } else if (clean.startsWith("{")) {
                val jsonObj = org.json.JSONObject(clean)
                val items = mutableListOf<VaultItem>()
                val imageMap = mutableMapOf<String, String>()

                // Extract imageAssets map
                val imgObj = jsonObj.optJSONObject("imageAssets") ?: jsonObj.optJSONObject("images") ?: jsonObj.optJSONObject("assets")
                if (imgObj != null) {
                    val keys = imgObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = imgObj.optString(k)
                        if (v.isNotBlank()) {
                            imageMap[k] = v
                        }
                    }
                }

                // Extract items array
                val itemsArray = jsonObj.optJSONArray("items") ?: jsonObj.optJSONArray("entries") ?: jsonObj.optJSONArray("vault") ?: jsonObj.optJSONArray("records") ?: jsonObj.optJSONArray("data")
                if (itemsArray != null) {
                    for (i in 0 until itemsArray.length()) {
                        val obj = itemsArray.optJSONObject(i) ?: continue
                        val id = obj.optString("id").ifBlank { obj.optString("uuid", java.util.UUID.randomUUID().toString()) }
                        val title = obj.optString("title").ifBlank { obj.optString("account", obj.optString("name", obj.optString("label", "Restored Entry"))) }
                        val content = obj.optString("content").ifBlank { obj.optString("secret", obj.optString("password", obj.optString("notes", obj.optString("value", "")))) }
                        val timestamp = if (obj.has("timestamp")) obj.optLong("timestamp") else if (obj.has("created")) obj.optLong("created") else System.currentTimeMillis()
                        val imgPath = obj.optString("imagePath").ifBlank { obj.optString("image", "").ifBlank { null } }
                        items.add(VaultItem(id = id, title = title, content = content, timestamp = timestamp, imagePath = imgPath))
                    }
                } else {
                    // Check if object itself represents a single item
                    val title = jsonObj.optString("title").ifBlank { jsonObj.optString("account", jsonObj.optString("name", "")) }
                    val content = jsonObj.optString("content").ifBlank { jsonObj.optString("secret", jsonObj.optString("password", jsonObj.optString("notes", ""))) }
                    if (title.isNotBlank() || content.isNotBlank()) {
                        val id = jsonObj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                        val timestamp = if (jsonObj.has("timestamp")) jsonObj.optLong("timestamp") else System.currentTimeMillis()
                        val imgPath = jsonObj.optString("imagePath").ifBlank { null }
                        items.add(VaultItem(id = id, title = title, content = content, timestamp = timestamp, imagePath = imgPath))
                    }
                }

                if (items.isNotEmpty() || imageMap.isNotEmpty()) {
                    return VaultBackupPayload(items = items, imageAssets = imageMap)
                }
            }
        } catch (_: Exception) {}

        return null
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

                val rawText = String(rawBytes, Charsets.UTF_8).trim().removePrefix("\uFEFF").trim()

                var payload: VaultBackupPayload? = null

                // 1. Try extracting encrypted payload Base64 string from JSON object if encrypted
                var encPayloadBase64: String? = null
                var saltHex: String? = null

                // Try Moshi
                val archive = try { backupArchiveAdapter.fromJson(rawText) } catch (_: Exception) { null }
                if (archive != null && !archive.encryptedPayloadBase64.isNullOrBlank()) {
                    encPayloadBase64 = archive.encryptedPayloadBase64
                    saltHex = archive.saltHex
                } else if (rawText.startsWith("{")) {
                    try {
                        val jsonObj = org.json.JSONObject(rawText)
                        if (jsonObj.has("encryptedPayloadBase64")) {
                            encPayloadBase64 = jsonObj.optString("encryptedPayloadBase64")
                        } else if (jsonObj.has("encryptedPayload")) {
                            encPayloadBase64 = jsonObj.optString("encryptedPayload")
                        } else if (jsonObj.has("payload")) {
                            encPayloadBase64 = jsonObj.optString("payload")
                        }
                        if (jsonObj.has("saltHex")) {
                            saltHex = jsonObj.optString("saltHex")
                        } else if (jsonObj.has("salt")) {
                            saltHex = jsonObj.optString("salt")
                        }
                    } catch (_: Exception) {}
                }

                if (!encPayloadBase64.isNullOrBlank()) {
                    val encryptedBytes = try {
                        android.util.Base64.decode(encPayloadBase64, android.util.Base64.NO_WRAP)
                    } catch (_: Exception) {
                        android.util.Base64.decode(encPayloadBase64, android.util.Base64.DEFAULT)
                    }

                    // Decrypt using active vault key
                    var decryptedBytes: ByteArray? = try {
                        CryptoManager.decryptXChaCha20Poly1305(encryptedBytes, key)
                    } catch (_: Exception) { null }

                    // Fallback: Try session password + salt
                    if (decryptedBytes == null && activeSessionPassword != null && !saltHex.isNullOrBlank()) {
                        try {
                            val archiveSalt = try { String(CryptoManager.hexToBytes(saltHex), Charsets.UTF_8) } catch (_: Exception) { saltHex }
                            val altKey = CryptoManager.deriveKey(activeSessionPassword!!, archiveSalt)
                            decryptedBytes = CryptoManager.decryptXChaCha20Poly1305(encryptedBytes, altKey)
                        } catch (_: Exception) {}
                    }

                    if (decryptedBytes == null) {
                        throw IllegalStateException("Decryption failed: Key mismatch. Ensure you are logged into the vault using the master password that created this backup.")
                    }

                    val payloadJson = String(decryptedBytes, Charsets.UTF_8)
                    payload = parseVaultPayloadFlexible(payloadJson)
                }

                // 2. Fallback: Parse unencrypted raw text as JSON payload
                if (payload == null) {
                    payload = parseVaultPayloadFlexible(rawText)
                }

                // 3. Fallback: Check if rawText is raw Base64 string directly
                if (payload == null && !rawText.startsWith("{") && !rawText.startsWith("[")) {
                    try {
                        val decodedBytes = android.util.Base64.decode(rawText, android.util.Base64.DEFAULT)
                        val decrypted = try { CryptoManager.decryptXChaCha20Poly1305(decodedBytes, key) } catch (_: Exception) { null }
                        if (decrypted != null) {
                            payload = parseVaultPayloadFlexible(String(decrypted, Charsets.UTF_8))
                        }
                    } catch (_: Exception) {}
                }

                if (payload == null) {
                    throw IllegalArgumentException("Invalid or unrecognized backup JSON format")
                }

                if (payload.items.isEmpty() && payload.imageAssets.isEmpty()) {
                    throw IllegalArgumentException("Backup JSON was parsed, but contains no items or images to restore.")
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

                            // Check if image is raw unencrypted photo vs encrypted
                            val isRawImage = data.size > 4 && (
                                (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) ||
                                (data[0] == 0x89.toByte() && data[1] == 'P'.toByte() && data[2] == 'N'.toByte()) ||
                                (data[0] == 'G'.toByte() && data[1] == 'I'.toByte() && data[2] == 'F'.toByte())
                            )

                            val finalImageBytes = if (isRawImage) {
                                CryptoManager.encryptXChaCha20Poly1305(data, key)
                            } else {
                                try {
                                    CryptoManager.decryptXChaCha20Poly1305(data, key)
                                    data // Already correctly encrypted
                                } catch (_: Exception) {
                                    CryptoManager.encryptXChaCha20Poly1305(data, key)
                                }
                            }

                            imgFile.writeBytes(finalImageBytes)
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

    fun restoreFromKaspaTxId(
        manifestTxId: String,
        onSuccess: (Int, Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val key = derivedKey
        val priv = privateKey
        val context = getApplication<Application>()
        if (key == null || priv == null) {
            onError("Vault is locked. Please unlock your vault before restoring data.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isRestoringFromKfs.value = true
            _kfsRestoreProgress.value = 0.05f
            _kfsRestoreStatus.value = "Initiating KFS retrieval..."
            try {
                val cleanId = manifestTxId.trim()
                var manifestAndCiphertext: Pair<KfsManifest, ByteArray>? = null

                try {
                    manifestAndCiphertext = KfsEngine.fetchAndReconstructFromKaspa(cleanId) { progress, status ->
                        _kfsRestoreProgress.value = progress
                        _kfsRestoreStatus.value = status
                    }
                } catch (onChainError: Exception) {
                    // Fallback to checking local cache files and persistent KFS records
                    val cacheFile1 = File(context.cacheDir, "kfs_cache_$cleanId.dat")
                    val cacheFile2 = File(context.filesDir, "kfs_cache_$cleanId.dat")
                    val allRecords = db.kfsDao().getAllRecords().firstOrNull() ?: emptyList()
                    val localRecord = db.kfsDao().getRecordById(cleanId)
                        ?: allRecords.find { it.manifestTxId == cleanId || it.merkleRoot == cleanId || it.id == cleanId }

                    val targetCacheFile = when {
                        cacheFile1.exists() -> cacheFile1
                        cacheFile2.exists() -> cacheFile2
                        localRecord != null && File(context.cacheDir, "kfs_cache_${localRecord.id}.dat").exists() -> File(context.cacheDir, "kfs_cache_${localRecord.id}.dat")
                        localRecord != null && File(context.cacheDir, "kfs_cache_${localRecord.merkleRoot}.dat").exists() -> File(context.cacheDir, "kfs_cache_${localRecord.merkleRoot}.dat")
                        localRecord != null && File(context.cacheDir, "kfs_cache_${localRecord.manifestTxId}.dat").exists() -> File(context.cacheDir, "kfs_cache_${localRecord.manifestTxId}.dat")
                        else -> null
                    }

                    if (targetCacheFile != null && targetCacheFile.exists()) {
                        _kfsRestoreStatus.value = "Recovering from persistent local KFS record..."
                        val cachedBytes = targetCacheFile.readBytes()
                        val dummyManifest = KfsManifest(
                            fileId = localRecord?.id ?: cleanId,
                            timestamp = localRecord?.timestamp ?: System.currentTimeMillis(),
                            totalBytes = cachedBytes.size,
                            totalChunks = localRecord?.totalChunks ?: 1,
                            merkleRoot = localRecord?.merkleRoot ?: cleanId,
                            chunkHashes = emptyList(),
                            chunkTxIds = emptyList(),
                            algorithm = "XChaCha20-Poly1305+BLAKE2b",
                            signature = null
                        )
                        manifestAndCiphertext = Pair(dummyManifest, cachedBytes)
                    } else {
                        throw onChainError
                    }
                }

                val (manifest, ciphertext) = manifestAndCiphertext!!

                _kfsRestoreStatus.value = "Decrypting XChaCha20-Poly1305 payload with active master key..."
                
                val decryptedBytes = try {
                    CryptoManager.decryptXChaCha20Poly1305(ciphertext, key)
                } catch (e: Exception) {
                    throw IllegalStateException("Decryption failed: Key mismatch. Ensure your vault is unlocked with the same master password used during KFS broadcast.")
                }

                val decryptedText = String(decryptedBytes, Charsets.UTF_8).trim()

                var payload: VaultBackupPayload? = null

                // 1. Try parsing as VaultBackupPayload (items + imageAssets)
                payload = try {
                    backupPayloadAdapter.fromJson(decryptedText)
                } catch (e: Exception) {
                    null
                }

                // 2. Try parsing as List<VaultItem>
                if (payload == null) {
                    payload = try {
                        val items = vaultItemListAdapter.fromJson(decryptedText)
                        if (!items.isNullOrEmpty()) VaultBackupPayload(items = items, imageAssets = emptyMap()) else null
                    } catch (e: Exception) {
                        null
                    }
                }

                if (payload == null) {
                    throw IllegalStateException("Unrecognized vault payload structure inside decrypted KFS data.")
                }

                var restoredImages = 0
                for ((filename, b64Data) in payload.imageAssets) {
                    try {
                        if (b64Data.isNotBlank()) {
                            val sanitizedName = File(filename).name
                            val imgFile = File(context.filesDir, sanitizedName)
                            val imgPlaintext = try {
                                android.util.Base64.decode(b64Data, android.util.Base64.NO_WRAP)
                            } catch (e: Exception) {
                                android.util.Base64.decode(b64Data, android.util.Base64.DEFAULT)
                            }
                            val imgCiphertext = CryptoManager.encryptXChaCha20Poly1305(imgPlaintext, key)
                            imgFile.writeBytes(imgCiphertext)
                            restoredImages++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                var restoredItems = 0
                for (item in payload.items) {
                    val itemId = if (item.id.isBlank()) UUID.randomUUID().toString() else item.id
                    val itemToSave = item.copy(id = itemId)
                    val itemJson = vaultItemAdapter.toJson(itemToSave)
                    val plaintext = itemJson.toByteArray(Charsets.UTF_8)
                    val encCiphertext = CryptoManager.encryptXChaCha20Poly1305(plaintext, key)
                    val signature = try {
                        CryptoManager.sign(encCiphertext, priv)
                    } catch (e: Exception) {
                        ByteArray(0)
                    }
                    val entity = VaultEntryEntity(
                        id = itemId,
                        ciphertext = encCiphertext,
                        signature = signature
                    )
                    db.vaultDao().insertEntry(entity)
                    restoredItems++
                }

                // Record this restored manifest into local KFS database if not already present
                try {
                    val existing = db.kfsDao().getRecordById(manifest.fileId)
                    if (existing == null) {
                        val stringListAdapter = moshi.adapter<List<String>>(Types.newParameterizedType(List::class.java, String::class.java))
                        val recordEntity = com.example.db.KfsBroadcastRecordEntity(
                            id = manifest.fileId,
                            title = "Restored from Kaspa (${restoredItems} items, ${restoredImages} images)",
                            manifestTxId = manifestTxId,
                            merkleRoot = manifest.merkleRoot,
                            chunkTxIdsJson = stringListAdapter.toJson(manifest.chunkTxIds),
                            totalChunks = manifest.totalChunks,
                            totalBytes = manifest.totalBytes,
                            totalFeeSompis = 0L,
                            timestamp = manifest.timestamp,
                            status = "RESTORED",
                            errorMessage = null
                        )
                        db.kfsDao().insertRecord(recordEntity)
                    }
                } catch (e: Exception) {
                    // ignore record insert failure
                }

                loadItems()
                resetAutoLockTimer()

                withContext(Dispatchers.Main) {
                    _isRestoringFromKfs.value = false
                    onSuccess(restoredItems, restoredImages)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isRestoringFromKfs.value = false
                    onError(e.localizedMessage ?: "Failed to recover vault from Kaspa network")
                }
            }
        }
    }

    fun deleteKfsRecord(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.kfsDao().deleteRecord(id)
        }
    }

    fun clearAllKfsRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            db.kfsDao().clearAllRecords()
        }
    }

    fun addManualKfsRecord(title: String, txIdOrMerkleRoot: String) {
        val cleanId = txIdOrMerkleRoot.trim()
        if (cleanId.isBlank()) return
        val id = UUID.randomUUID().toString()
        val record = KfsBroadcastRecordEntity(
            id = id,
            title = if (title.isBlank()) "Saved Merkle Root / TxID" else title.trim(),
            manifestTxId = cleanId,
            merkleRoot = cleanId,
            chunkTxIdsJson = "[]",
            totalChunks = 1,
            totalBytes = 0,
            totalFeeSompis = 0L,
            timestamp = System.currentTimeMillis(),
            status = "SAVED",
            errorMessage = null
        )
        viewModelScope.launch(Dispatchers.IO) {
            db.kfsDao().insertRecord(record)
        }
    }

    fun clearKfsBroadcastState() {
        com.example.network.KfsEngine.resetBroadcastState()
    }

    fun syncAddressKfsHistory(
        targetAddress: String? = null,
        autoRestoreIfEmpty: Boolean = false,
        onComplete: ((Int, String) -> Unit)? = null
    ) {
        val address = targetAddress ?: _kaspaWallet.value?.address
        if (address.isNullOrBlank()) {
            onComplete?.invoke(0, "Kaspa address not available.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncingKfsHistory.value = true
            _syncKfsHistoryStatus.value = "Scanning Kaspa BlockDAG for on-chain history..."
            var discovered = 0
            try {
                // 1. Fetch transaction list for address
                val txList = try {
                    KaspaNetwork.api.getAddressFullTransactions(address, limit = 50)
                } catch (e: Exception) {
                    try {
                        val refs = KaspaNetwork.api.getAddressTransactions(address, limit = 50)
                        refs.mapNotNull { ref ->
                            ref.resolvedTxId?.let { txId ->
                                try { KaspaNetwork.getTransactionWithFallback(txId) } catch (_: Exception) { null }
                            }
                        }
                    } catch (e2: Exception) {
                        emptyList()
                    }
                }

                _syncKfsHistoryStatus.value = "Analyzing ${txList.size} on-chain transactions for KFS payloads..."
                val stringListAdapter = moshi.adapter<List<String>>(Types.newParameterizedType(List::class.java, String::class.java))

                for (tx in txList) {
                    val rawPayload = tx.resolvedPayload ?: continue
                    if (rawPayload.isBlank()) continue

                    val decodedPayload = if (rawPayload.trim().startsWith("{") && rawPayload.trim().endsWith("}")) {
                        rawPayload.trim()
                    } else {
                        try {
                            val bytes = CryptoManager.hexToBytes(rawPayload.trim())
                            String(bytes, Charsets.UTF_8).trim()
                        } catch (_: Exception) {
                            rawPayload.trim()
                        }
                    }

                    if (decodedPayload.contains("\"merkleRoot\"") && (decodedPayload.contains("\"totalChunks\"") || decodedPayload.contains("\"chunkHashes\"") || decodedPayload.contains("\"chunkTxIds\""))) {
                        val manifest = try {
                            manifestAdapter.fromJson(decodedPayload)
                        } catch (_: Exception) {
                            null
                        }

                        if (manifest != null && manifest.chunkTxIds.isNotEmpty()) {
                            val txId = tx.resolvedTransactionId ?: manifest.chunkTxIds.firstOrNull() ?: UUID.randomUUID().toString()
                            val blockTime = tx.resolvedBlockTime ?: manifest.timestamp
                            val existing = db.kfsDao().getRecordById(manifest.fileId)
                                ?: db.kfsDao().getAllRecords().firstOrNull()?.find { it.manifestTxId == txId || it.merkleRoot == manifest.merkleRoot }

                            if (existing == null) {
                                val record = KfsBroadcastRecordEntity(
                                    id = manifest.fileId.ifBlank { txId },
                                    title = "On-Chain KFS Backup (${manifest.totalChunks} chunks, ${manifest.totalBytes / 1024} KB)",
                                    manifestTxId = txId,
                                    merkleRoot = manifest.merkleRoot,
                                    chunkTxIdsJson = stringListAdapter.toJson(manifest.chunkTxIds),
                                    totalChunks = manifest.totalChunks,
                                    totalBytes = manifest.totalBytes,
                                    totalFeeSompis = 0L,
                                    timestamp = blockTime,
                                    status = "ON_CHAIN_SYNCED",
                                    errorMessage = null
                                )
                                db.kfsDao().insertRecord(record)
                                discovered++
                            }
                        }
                    }
                }

                _discoveredKfsCount.value = discovered
                val finalMsg = if (discovered > 0) {
                    "Found $discovered on-chain KFS Master Manifest(s)!"
                } else {
                    "Scan complete: Up-to-date with on-chain records."
                }
                _syncKfsHistoryStatus.value = finalMsg

                // If vault is empty, autoRestoreIfEmpty is true, and records are available
                if (autoRestoreIfEmpty && _vaultItems.value.isEmpty() && derivedKey != null) {
                    val allRecords = db.kfsDao().getAllRecords().firstOrNull() ?: emptyList()
                    val latestRecord = allRecords.firstOrNull()
                    if (latestRecord != null) {
                        _syncKfsHistoryStatus.value = "Auto-recovering vault payload from latest on-chain record..."
                        restoreFromKaspaTxId(
                            manifestTxId = latestRecord.manifestTxId,
                            onSuccess = { items, images ->
                                _syncKfsHistoryStatus.value = "Auto-restored $items items and $images images from on-chain history!"
                            },
                            onError = { _ -> }
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    _isSyncingKfsHistory.value = false
                    onComplete?.invoke(discovered, finalMsg)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isSyncingKfsHistory.value = false
                    _syncKfsHistoryStatus.value = "Scan error: ${e.localizedMessage}"
                    onComplete?.invoke(0, "Error scanning on-chain history: ${e.localizedMessage}")
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
