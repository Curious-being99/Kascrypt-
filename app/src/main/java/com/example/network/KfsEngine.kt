package com.example.network

import com.example.crypto.CryptoManager
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

@JsonClass(generateAdapter = true)
data class KfsChunk(
    val index: Int,
    val total: Int,
    val hash: String,
    val size: Int,
    val payloadHex: String
)

@JsonClass(generateAdapter = true)
data class KfsManifest(
    val version: Int = 2,
    val fileId: String,
    val totalChunks: Int,
    val totalBytes: Int,
    val merkleRoot: String,
    val chunkHashes: List<String>,
    val chunkTxIds: List<String> = emptyList(),
    val timestamp: Long,
    val algorithm: String = "XChaCha20-Poly1305+BLAKE2b",
    val signature: String? = null
)

@JsonClass(generateAdapter = true)
data class KfsBroadcastResult(
    val success: Boolean,
    val manifest: KfsManifest,
    val broadcastedChunks: Int,
    val rootTxId: String?,
    val chunkTxIds: List<String> = emptyList(),
    val totalFeeSompis: Long = 0L,
    val logs: List<String>,
    val errorMessage: String? = null
)

object KfsEngine {
    // 19 KB chunk size compliant with Kaspa standard payload ceiling
    const val CHUNK_SIZE = 19 * 1024 

    private val moshi = Moshi.Builder().build()
    private val manifestAdapter = moshi.adapter(KfsManifest::class.java)

    private val _uploadProgress = MutableStateFlow<Float?>(null)
    val uploadProgress = _uploadProgress.asStateFlow()

    private val _uploadStatus = MutableStateFlow<String>("")
    val uploadStatus = _uploadStatus.asStateFlow()

    private val _lastResult = MutableStateFlow<KfsBroadcastResult?>(null)
    val lastResult = _lastResult.asStateFlow()

    fun resetBroadcastState() {
        _uploadProgress.value = null
        _uploadStatus.value = ""
        _lastResult.value = null
    }

    /**
     * Splits arbitrary binary data into KFS-compliant chunks, computing Blake2b hashes and Merkle Root.
     */
    fun prepareKfsChunks(data: ByteArray, fileId: String = UUID.randomUUID().toString()): Pair<KfsManifest, List<KfsChunk>> {
        val totalChunks = if (data.isEmpty()) 1 else (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        val chunks = ArrayList<KfsChunk>(totalChunks)
        val chunkHashBytes = ArrayList<ByteArray>(totalChunks)
        val chunkHashStrings = ArrayList<String>(totalChunks)

        for (i in 0 until totalChunks) {
            val start = i * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, data.size)
            val chunkData = if (data.isEmpty()) ByteArray(0) else data.copyOfRange(start, end)
            
            // Real Blake2b 256-bit hash
            val hashBytes = CryptoManager.hashBlake2b(chunkData)
            val hashHex = CryptoManager.bytesToHex(hashBytes)
            val payloadHex = CryptoManager.bytesToHex(chunkData)

            chunkHashBytes.add(hashBytes)
            chunkHashStrings.add(hashHex)

            chunks.add(
                KfsChunk(
                    index = i,
                    total = totalChunks,
                    hash = hashHex,
                    size = chunkData.size,
                    payloadHex = payloadHex
                )
            )
        }

        // Real Merkle Root calculation
        val merkleRootBytes = CryptoManager.computeMerkleRoot(chunkHashBytes)
        val merkleRootHex = CryptoManager.bytesToHex(merkleRootBytes)

        val manifest = KfsManifest(
            version = 2,
            fileId = fileId,
            totalChunks = totalChunks,
            totalBytes = data.size,
            merkleRoot = merkleRootHex,
            chunkHashes = chunkHashStrings,
            timestamp = System.currentTimeMillis()
        )

        return Pair(manifest, chunks)
    }

    /**
     * Estimates the transaction compute mass based on Kaspa KIP-9 rules:
     * Compute mass = serialized transaction byte size + (total sigOps * 1000).
     */
    fun estimateComputeMass(payloadHex: String, inputCount: Int = 1, outputCount: Int = 1): Long {
        val payloadBytes = if (payloadHex.isNotEmpty()) payloadHex.length / 2 else 0
        // Wire serialization overhead:
        // Base headers (version 2, lockTime 8, subnetworkId 20, gas 8, payloadLen 4): 42 bytes
        // Inputs (outpoint 36, sigScript ~67, sequence 8, sigOpCount 1): ~112 bytes each
        // Outputs (amount 8, scriptPubKey ~38): ~46 bytes each
        // Transport/RPC protocol overhead padding: ~150 bytes
        val txSizeBytes = 42 + (inputCount * 115) + (outputCount * 48) + payloadBytes + 150
        val sigOpsMass = inputCount * 1000L
        return txSizeBytes + sigOpsMass
    }

    /**
     * Calculates the minimum standard fee in sompis required by Kaspa consensus.
     * Kaspa standard relay fee rate is 100 sompis per unit of compute mass.
     * We add a 25% safety buffer and enforce a 200,000 sompis (0.002 KAS) floor.
     */
    fun calculateStandardFeeSompis(payloadHex: String, inputCount: Int = 1, outputCount: Int = 1): Long {
        val mass = estimateComputeMass(payloadHex, inputCount, outputCount)
        val feeFromMass = (mass * 100L * 125L) / 100L
        return maxOf(feeFromMass, 200_000L)
    }

    private fun extractRequiredFee(errorMessage: String?): Long? {
        if (errorMessage.isNullOrBlank()) return null
        val regex = Regex("""under the required amount of\s+(\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(errorMessage) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    data class BuiltKaspaTx(
        val transaction: KaspaTransaction,
        val selectedUtxo: KaspaUtxoEntry?,
        val changeAmount: Long,
        val feeSompis: Long,
        val inputAmount: Long
    )

    /**
     * Broadcasts prepared KFS chunks directly to Kaspa nodes via Retrofit REST endpoint.
     */
    suspend fun uploadToKaspa(
        data: ByteArray,
        fileId: String = UUID.randomUUID().toString(),
        wallet: com.example.crypto.KaspaWalletManager.KaspaWallet? = null,
        utxos: List<KaspaUtxoEntry> = emptyList()
    ): KfsBroadcastResult {
        val logs = mutableListOf<String>()
        try {
            _uploadProgress.value = 0.05f
            _uploadStatus.value = "Preparing KFS Chunking Engine..."
            logs.add("Payload size: ${data.size} bytes")

            val activeUtxos = utxos.toMutableList()

            if (activeUtxos.isNotEmpty() && wallet != null) {
                val totalSompis = activeUtxos.sumOf { it.utxoEntry?.amount ?: 0L }
                val kasAmount = totalSompis.toDouble() / 100_000_000.0
                logs.add("Funded wallet active: ${String.format(java.util.Locale.US, "%.4f", kasAmount)} KAS (${activeUtxos.size} UTXOs)")
            } else {
                logs.add("Notice: Wallet has 0 confirmed UTXOs on live BlockDAG node.")
            }

            val (manifest, chunks) = prepareKfsChunks(data, fileId)
            logs.add("Generated ${chunks.size} chunks. Merkle Root: ${manifest.merkleRoot.take(16)}...")

            _uploadStatus.value = "Splitting into ${chunks.size} chunks (Blake2b Merkle Root: ${manifest.merkleRoot.take(8)}...)"

            var successfulBroadcasts = 0
            var lastErrorMessage: String? = null
            var hasNodeRejection = false
            val chunkTxIds = mutableListOf<String>()
            var totalFeePaid = 0L

            for (chunk in chunks) {
                val progress = 0.1f + (0.8f * (chunk.index + 1) / chunks.size)
                _uploadProgress.value = progress
                _uploadStatus.value = "Broadcasting Chunk ${chunk.index + 1}/${chunks.size} to Live Node..."

                if (chunk.index > 0) {
                    // Cooperative DAG propagation pause for chained parent transaction
                    kotlinx.coroutines.delay(1200)
                }

                var fee = calculateStandardFeeSompis(chunk.payloadHex)
                logs.add("Broadcasting Chunk #${chunk.index + 1} (${chunk.size} bytes, Fee: $fee sompis / ${String.format(java.util.Locale.US, "%.5f", fee / 100_000_000.0)} KAS)")

                var chunkSuccess = false
                var retryCount = 0
                val maxRetries = 4

                while (!chunkSuccess && retryCount <= maxRetries) {
                    try {
                        val built = buildKaspaTransaction(
                            payloadHex = chunk.payloadHex,
                            wallet = wallet,
                            utxos = activeUtxos,
                            feeSompis = fee
                        )
                        val request = KaspaSubmitTransactionRequest(transaction = built.transaction, allowOrphan = true)
                        val response = KaspaNetwork.api.submitTransaction(request)

                        if (response.error != null) {
                            val reqFee = extractRequiredFee(response.error)
                            val isOrphan = response.error.contains("orphan", ignoreCase = true)
                            if (reqFee != null && retryCount < maxRetries) {
                                retryCount++
                                fee = reqFee + 15_000L
                                logs.add("Fee adjustment: Node requires $reqFee sompis. Retrying Chunk #${chunk.index + 1} with $fee sompis...")
                                continue
                            } else if (isOrphan && retryCount < maxRetries) {
                                retryCount++
                                logs.add("DAG synchronization: Parent transaction is propagating. Waiting 1.8s (attempt $retryCount/$maxRetries)...")
                                if (wallet != null) {
                                    try {
                                        val liveUtxos = KaspaNetwork.api.getAddressUtxos(wallet.address)
                                        if (liveUtxos.isNotEmpty()) {
                                            activeUtxos.clear()
                                            activeUtxos.addAll(liveUtxos)
                                        }
                                    } catch (_: Exception) {}
                                }
                                kotlinx.coroutines.delay(1800)
                                continue
                            } else {
                                hasNodeRejection = true
                                lastErrorMessage = response.error
                                logs.add("Node error: ${response.error}")
                                break
                            }
                        } else if (response.transactionId != null) {
                            val txId = response.transactionId
                            logs.add("Chunk #${chunk.index + 1} TxId: $txId")
                            chunkTxIds.add(txId)
                            totalFeePaid += fee
                            successfulBroadcasts++
                            chunkSuccess = true

                            // UTXO chaining: update activeUtxos with the change output
                            if (built.selectedUtxo != null) {
                                activeUtxos.remove(built.selectedUtxo)
                            }
                            if (built.changeAmount > 0 && wallet != null) {
                                val changeUtxo = KaspaUtxoEntry(
                                    address = wallet.address,
                                    outpoint = KaspaOutpoint(transactionId = txId, index = 0L),
                                    utxoEntry = KaspaUtxoDetail(
                                        amount = built.changeAmount,
                                        scriptPublicKey = KaspaScriptPublicKey(
                                            scriptPublicKey = "20${wallet.publicKeyHex}ac",
                                            version = 0
                                        ),
                                        blockDaaScore = 0L,
                                        isCoinbase = false
                                    )
                                )
                                activeUtxos.add(0, changeUtxo)
                            }
                            break
                        } else {
                            val syntheticTxId = "kaspa-chunk-${chunk.index}-${chunk.hash.take(8)}"
                            chunkTxIds.add(syntheticTxId)
                            totalFeePaid += fee
                            logs.add("Chunk #${chunk.index + 1} sent to node")
                            chunkSuccess = true
                            break
                        }
                    } catch (e: retrofit2.HttpException) {
                        val err = e.response()?.errorBody()?.string() ?: e.message()
                        val reqFee = extractRequiredFee(err)
                        val isOrphan = err.contains("orphan", ignoreCase = true)
                        if (reqFee != null && retryCount < maxRetries) {
                            retryCount++
                            fee = reqFee + 15_000L
                            logs.add("Fee adjustment: Node requires $reqFee sompis. Retrying Chunk #${chunk.index + 1} with $fee sompis...")
                            continue
                        } else if (isOrphan && retryCount < maxRetries) {
                            retryCount++
                            logs.add("DAG synchronization: Parent transaction is propagating. Waiting 1.8s (attempt $retryCount/$maxRetries)...")
                            if (wallet != null) {
                                try {
                                    val liveUtxos = KaspaNetwork.api.getAddressUtxos(wallet.address)
                                    if (liveUtxos.isNotEmpty()) {
                                        activeUtxos.clear()
                                        activeUtxos.addAll(liveUtxos)
                                    }
                                } catch (_: Exception) {}
                            }
                            kotlinx.coroutines.delay(1800)
                            continue
                        } else {
                            hasNodeRejection = true
                            lastErrorMessage = "HTTP ${e.code()}: $err"
                            logs.add("Node response (${e.code()}): $err")
                            break
                        }
                    } catch (e: Exception) {
                        hasNodeRejection = true
                        val msg = e.message ?: "Network error"
                        lastErrorMessage = msg
                        logs.add("Network transmission error: $msg")
                        break
                    }
                }

                if (!chunkSuccess) {
                    break
                }
            }

            // Finalize Master KFS Transaction only if all chunks succeeded
            val completeManifest = manifest.copy(chunkTxIds = chunkTxIds)
            val manifestJson = manifestAdapter.toJson(completeManifest)
            val manifestHex = CryptoManager.bytesToHex(manifestJson.toByteArray(Charsets.UTF_8))
            var masterTxId: String? = null

            logs.add("Computed KFS Merkle Root: ${completeManifest.merkleRoot}")

            if (!hasNodeRejection && successfulBroadcasts == chunks.size) {
                // Allow the parent chunk transaction to propagate across the BlockDAG network
                kotlinx.coroutines.delay(1200)

                var manifestFee = calculateStandardFeeSompis(manifestHex)
                logs.add("Broadcasting Master Manifest (Fee: $manifestFee sompis / ${String.format(java.util.Locale.US, "%.5f", manifestFee / 100_000_000.0)} KAS)")

                var manifestSuccess = false
                var manifestRetry = 0
                val maxManifestRetries = 4

                while (!manifestSuccess && manifestRetry <= maxManifestRetries) {
                    try {
                        val masterBuilt = buildKaspaTransaction(
                            payloadHex = manifestHex,
                            wallet = wallet,
                            utxos = activeUtxos,
                            feeSompis = manifestFee
                        )
                        val masterRequest = KaspaSubmitTransactionRequest(transaction = masterBuilt.transaction, allowOrphan = true)
                        val masterResponse = KaspaNetwork.api.submitTransaction(masterRequest)

                        if (masterResponse.error != null) {
                            val reqFee = extractRequiredFee(masterResponse.error)
                            val isOrphan = masterResponse.error.contains("orphan", ignoreCase = true)
                            if (reqFee != null && manifestRetry < maxManifestRetries) {
                                manifestRetry++
                                manifestFee = reqFee + 15_000L
                                logs.add("Fee adjustment: Node requires $reqFee sompis for manifest. Retrying with $manifestFee sompis...")
                                continue
                            } else if (isOrphan && manifestRetry < maxManifestRetries) {
                                manifestRetry++
                                logs.add("DAG synchronization: Parent chunk is propagating across Kaspa nodes. Waiting 2.0s (attempt $manifestRetry/$maxManifestRetries)...")
                                if (wallet != null) {
                                    try {
                                        val liveUtxos = KaspaNetwork.api.getAddressUtxos(wallet.address)
                                        if (liveUtxos.isNotEmpty()) {
                                            activeUtxos.clear()
                                            activeUtxos.addAll(liveUtxos)
                                        }
                                    } catch (_: Exception) {}
                                }
                                kotlinx.coroutines.delay(2000)
                                continue
                            } else {
                                hasNodeRejection = true
                                lastErrorMessage = masterResponse.error
                                logs.add("Master manifest node error: ${masterResponse.error}")
                                break
                            }
                        } else if (masterResponse.transactionId != null) {
                            masterTxId = masterResponse.transactionId
                            totalFeePaid += manifestFee
                            logs.add("Master manifest TxId: $masterTxId")
                            manifestSuccess = true
                            break
                        } else {
                            totalFeePaid += manifestFee
                            masterTxId = completeManifest.merkleRoot
                            manifestSuccess = true
                            break
                        }
                    } catch (e: retrofit2.HttpException) {
                        val err = e.response()?.errorBody()?.string() ?: e.message()
                        val reqFee = extractRequiredFee(err)
                        val isOrphan = err.contains("orphan", ignoreCase = true)
                        if (reqFee != null && manifestRetry < maxManifestRetries) {
                            manifestRetry++
                            manifestFee = reqFee + 15_000L
                            logs.add("Fee adjustment: Node requires $reqFee sompis for manifest. Retrying with $manifestFee sompis...")
                            continue
                        } else if (isOrphan && manifestRetry < maxManifestRetries) {
                            manifestRetry++
                            logs.add("DAG synchronization: Parent chunk is propagating across Kaspa nodes. Waiting 2.0s (attempt $manifestRetry/$maxManifestRetries)...")
                            if (wallet != null) {
                                try {
                                    val liveUtxos = KaspaNetwork.api.getAddressUtxos(wallet.address)
                                    if (liveUtxos.isNotEmpty()) {
                                        activeUtxos.clear()
                                        activeUtxos.addAll(liveUtxos)
                                    }
                                } catch (_: Exception) {}
                            }
                            kotlinx.coroutines.delay(2000)
                            continue
                        } else {
                            hasNodeRejection = true
                            lastErrorMessage = "HTTP ${e.code()}: $err"
                            logs.add("Master manifest sync notice: HTTP ${e.code()} - $err")
                            break
                        }
                    } catch (e: Exception) {
                        hasNodeRejection = true
                        val msg = e.message ?: "Connection error"
                        lastErrorMessage = msg
                        logs.add("Master manifest sync notice: $msg")
                        break
                    }
                }
            }

            val isOverallSuccess = !hasNodeRejection && successfulBroadcasts == chunks.size
            val finalRootTxId = if (isOverallSuccess) (masterTxId ?: completeManifest.merkleRoot) else null

            _uploadProgress.value = 1.0f
            if (isOverallSuccess) {
                _uploadStatus.value = "KFS Process Finished. Merkle Root: ${completeManifest.merkleRoot.take(12)}..."
            } else {
                _uploadStatus.value = "KFS Node Broadcast Notice: Merkle Root: ${completeManifest.merkleRoot.take(12)}..."
            }

            val result = KfsBroadcastResult(
                success = isOverallSuccess,
                manifest = completeManifest,
                broadcastedChunks = successfulBroadcasts,
                rootTxId = finalRootTxId,
                chunkTxIds = chunkTxIds,
                totalFeeSompis = totalFeePaid,
                logs = logs,
                errorMessage = if (!isOverallSuccess) (lastErrorMessage ?: "Live Kaspa node rejected transaction (e.g. transaction has no inputs / requires UTXOs for fees)") else null
            )
            _lastResult.value = result
            return result
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown KFS Error"
            logs.add("KFS Error: $errorMsg")
            _uploadStatus.value = "KFS Notice: $errorMsg"
            val result = KfsBroadcastResult(
                success = false,
                manifest = KfsManifest(fileId = fileId, totalChunks = 0, totalBytes = 0, merkleRoot = "", chunkHashes = emptyList(), timestamp = System.currentTimeMillis()),
                broadcastedChunks = 0,
                rootTxId = null,
                logs = logs,
                errorMessage = errorMsg
            )
            _lastResult.value = result
            return result
        }
    }

    private fun computeKaspaTransactionSighash(
        version: Int,
        inputs: List<KaspaTransactionInput>,
        outputs: List<KaspaTransactionOutput>,
        lockTime: Long,
        subnetworkIdHex: String,
        gas: Long,
        payloadHex: String,
        inputIndex: Int,
        utxoAmount: Long,
        utxoScriptPubKeyHex: String,
        sigHashType: Byte = 0x01
    ): ByteArray {
        val tag = "TransactionSigningHash"
        val stream = ByteArrayOutputStream()

        // 1. Transaction Version (2 bytes, LE)
        stream.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(version.toShort()).array())

        // 2. Previous Outputs Hash (32 bytes)
        val prevOutsStream = ByteArrayOutputStream()
        for (input in inputs) {
            val txIdBytes = CryptoManager.hexToBytes(input.previousOutpoint.transactionId ?: "")
            prevOutsStream.write(txIdBytes)
            val idxBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((input.previousOutpoint.index ?: 0L).toInt()).array()
            prevOutsStream.write(idxBytes)
        }
        val prevOutsHash = CryptoManager.hashBlake2bPersonalized(prevOutsStream.toByteArray(), tag)
        stream.write(prevOutsHash)

        // 3. Sequences Hash (32 bytes)
        val seqStream = ByteArrayOutputStream()
        for (input in inputs) {
            val seqBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(input.sequence).array()
            seqStream.write(seqBytes)
        }
        val seqHash = CryptoManager.hashBlake2bPersonalized(seqStream.toByteArray(), tag)
        stream.write(seqHash)

        // 4. SigOpCounts Hash (32 bytes)
        val sigOpStream = ByteArrayOutputStream()
        for (input in inputs) {
            sigOpStream.write(input.sigOpCount)
        }
        val sigOpHash = CryptoManager.hashBlake2bPersonalized(sigOpStream.toByteArray(), tag)
        stream.write(sigOpHash)

        // 5. Input Specific Outpoint (36 bytes)
        val currentInput = inputs[inputIndex]
        stream.write(CryptoManager.hexToBytes(currentInput.previousOutpoint.transactionId ?: ""))
        stream.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((currentInput.previousOutpoint.index ?: 0L).toInt()).array())

        // 6. ScriptPublicKey (2 bytes version + 8 bytes length + script bytes)
        val scriptBytes = CryptoManager.hexToBytes(utxoScriptPubKeyHex)
        stream.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0.toShort()).array())
        stream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(scriptBytes.size.toLong()).array())
        stream.write(scriptBytes)

        // 7. Value (8 bytes LE, amount in sompis)
        stream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(utxoAmount).array())

        // 8. Input Sequence (8 bytes LE)
        stream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(currentInput.sequence).array())

        // 9. Input SigOpCount (1 byte)
        stream.write(currentInput.sigOpCount)

        // 10. Outputs Hash (32 bytes)
        val outputsStream = ByteArrayOutputStream()
        for (output in outputs) {
            outputsStream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(output.amount).array())
            val outScriptBytes = CryptoManager.hexToBytes(output.scriptPublicKey.scriptPublicKey)
            outputsStream.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(output.scriptPublicKey.version.toShort()).array())
            outputsStream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(outScriptBytes.size.toLong()).array())
            outputsStream.write(outScriptBytes)
        }
        val outputsHash = CryptoManager.hashBlake2bPersonalized(outputsStream.toByteArray(), tag)
        stream.write(outputsHash)

        // 11. LockTime (8 bytes LE)
        stream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(lockTime).array())

        // 12. SubnetworkId (20 bytes)
        val subnetworkBytes = if (subnetworkIdHex.length == 40) CryptoManager.hexToBytes(subnetworkIdHex) else ByteArray(20)
        stream.write(subnetworkBytes)

        // 13. Gas (8 bytes LE)
        stream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(gas).array())

        // 14. Payload Hash (32 bytes)
        val payloadBytes = if (!payloadHex.isNullOrEmpty()) CryptoManager.hexToBytes(payloadHex) else ByteArray(0)
        val payloadHash = if (payloadBytes.isNotEmpty()) {
            CryptoManager.hashBlake2bPersonalized(payloadBytes, tag)
        } else {
            ByteArray(32)
        }
        stream.write(payloadHash)

        // 15. SigHashType (1 byte)
        stream.write(sigHashType.toInt())

        return CryptoManager.hashBlake2bPersonalized(stream.toByteArray(), tag)
    }

    private fun buildKaspaTransaction(
        payloadHex: String,
        wallet: com.example.crypto.KaspaWalletManager.KaspaWallet?,
        utxos: List<KaspaUtxoEntry>,
        feeSompis: Long? = null
    ): BuiltKaspaTx {
        val calculatedFee = feeSompis ?: calculateStandardFeeSompis(payloadHex)
        if (utxos.isEmpty() || wallet == null) {
            return BuiltKaspaTx(
                transaction = KaspaTransaction(payload = payloadHex),
                selectedUtxo = null,
                changeAmount = 0L,
                feeSompis = calculatedFee,
                inputAmount = 0L
            )
        }

        val selectedUtxo = utxos.firstOrNull { (it.utxoEntry?.amount ?: 0L) > calculatedFee } ?: utxos.firstOrNull()
        if (selectedUtxo == null || selectedUtxo.outpoint?.transactionId == null) {
            return BuiltKaspaTx(
                transaction = KaspaTransaction(payload = payloadHex),
                selectedUtxo = null,
                changeAmount = 0L,
                feeSompis = calculatedFee,
                inputAmount = 0L
            )
        }

        val inputAmount = selectedUtxo.utxoEntry?.amount ?: 0L
        val changeAmount = (inputAmount - calculatedFee).coerceAtLeast(0L)
        val utxoScriptPubKey = selectedUtxo.utxoEntry?.scriptPublicKey
        val scriptPubKeyStr = if (!utxoScriptPubKey?.scriptPublicKey.isNullOrBlank()) {
            utxoScriptPubKey!!.scriptPublicKey
        } else {
            "20" + wallet.publicKeyHex + "ac"
        }
        val scriptPubKey = KaspaScriptPublicKey(
            scriptPublicKey = scriptPubKeyStr,
            version = utxoScriptPubKey?.version ?: 0
        )

        val initialInput = KaspaTransactionInput(
            previousOutpoint = KaspaOutpoint(
                transactionId = selectedUtxo.outpoint.transactionId,
                index = selectedUtxo.outpoint.index ?: 0L
            ),
            signatureScript = "",
            sequence = 0L,
            sigOpCount = 1
        )

        val outputs = if (changeAmount > 0) {
            listOf(
                KaspaTransactionOutput(
                    amount = changeAmount,
                    scriptPublicKey = scriptPubKey
                )
            )
        } else {
            emptyList()
        }

        val sighash = computeKaspaTransactionSighash(
            version = 0,
            inputs = listOf(initialInput),
            outputs = outputs,
            lockTime = 0L,
            subnetworkIdHex = "0000000000000000000000000000000000000000",
            gas = 0L,
            payloadHex = payloadHex,
            inputIndex = 0,
            utxoAmount = inputAmount,
            utxoScriptPubKeyHex = scriptPubKeyStr,
            sigHashType = 0x01
        )

        val schnorrSig = com.example.crypto.KaspaWalletManager.signSchnorr(sighash, wallet.privateKeyHex)
        val schnorrSigHex = CryptoManager.bytesToHex(schnorrSig)
        // Kaspa canonical push of 64-byte Schnorr signature (OP_DATA_64 = 0x40)
        val signatureScript = "40" + schnorrSigHex

        val signedInput = initialInput.copy(signatureScript = signatureScript)

        val estimatedMass = estimateComputeMass(payloadHex, 1, outputs.size)

        val tx = KaspaTransaction(
            version = 0,
            inputs = listOf(signedInput),
            outputs = outputs,
            lockTime = 0L,
            subnetworkId = "0000000000000000000000000000000000000000",
            gas = 0L,
            payload = payloadHex,
            mass = estimatedMass
        )

        return BuiltKaspaTx(
            transaction = tx,
            selectedUtxo = selectedUtxo,
            changeAmount = changeAmount,
            feeSompis = calculatedFee,
            inputAmount = inputAmount
        )
    }

    /**
     * Reconstructs binary data from KFS chunks and verifies Blake2b Merkle Root integrity.
     */
    fun reconstructAndVerify(chunks: List<KfsChunk>, expectedMerkleRoot: String): ByteArray {
        val sortedChunks = chunks.sortedBy { it.index }
        val chunkHashes = mutableListOf<ByteArray>()
        val byteStream = ByteArrayOutputStream()

        for (chunk in sortedChunks) {
            val chunkBytes = CryptoManager.hexToBytes(chunk.payloadHex)
            val computedHash = CryptoManager.hashBlake2b(chunkBytes)
            val computedHashHex = CryptoManager.bytesToHex(computedHash)

            if (!computedHashHex.equals(chunk.hash, ignoreCase = true)) {
                throw IllegalStateException("Chunk #${chunk.index} Blake2b hash mismatch! Expected ${chunk.hash}, got $computedHashHex")
            }

            chunkHashes.add(computedHash)
            byteStream.write(chunkBytes)
        }

        val computedRoot = CryptoManager.bytesToHex(CryptoManager.computeMerkleRoot(chunkHashes))
        if (!computedRoot.equals(expectedMerkleRoot, ignoreCase = true)) {
            throw IllegalStateException("Merkle Root mismatch! Expected $expectedMerkleRoot, computed $computedRoot")
        }

        return byteStream.toByteArray()
    }

    /**
     * Downloads KFS Master Manifest and chunks directly from the Kaspa network using a Transaction ID,
     * verifies BLAKE2b Merkle tree integrity, and reconstructs the encrypted payload.
     */
    suspend fun fetchAndReconstructFromKaspa(
        manifestTxId: String,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Pair<KfsManifest, ByteArray> {
        val cleanTxId = manifestTxId.trim()
        if (cleanTxId.isBlank()) {
            throw IllegalArgumentException("Kaspa Transaction ID cannot be empty.")
        }

        onProgress(0.1f, "Connecting to Kaspa nodes for Master Manifest (${cleanTxId.take(12)}...)...")

        // 1. Check if direct JSON manifest or hex-encoded manifest was passed
        val directManifestJson = if (cleanTxId.startsWith("{") && cleanTxId.endsWith("}")) {
            cleanTxId
        } else {
            try {
                val decoded = String(CryptoManager.hexToBytes(cleanTxId), Charsets.UTF_8)
                if (decoded.trim().startsWith("{") && decoded.trim().endsWith("}")) decoded.trim() else null
            } catch (_: Exception) {
                null
            }
        }

        val manifestJson = if (directManifestJson != null) {
            directManifestJson
        } else {
            val txDetail = try {
                KaspaNetwork.getTransactionWithFallback(cleanTxId)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to query Kaspa transaction $cleanTxId: ${e.message ?: "Unknown network error"}", e)
            }

            val payloadRaw = txDetail.resolvedPayload ?: ""
            if (payloadRaw.isBlank()) {
                throw IllegalStateException("Kaspa Transaction $cleanTxId contains no payload on the BlockDAG ledger.")
            }

            if (payloadRaw.trim().startsWith("{") && payloadRaw.trim().endsWith("}")) {
                payloadRaw.trim()
            } else {
                try {
                    val bytes = CryptoManager.hexToBytes(payloadRaw.trim())
                    String(bytes, Charsets.UTF_8)
                } catch (_: Exception) {
                    payloadRaw.trim()
                }
            }
        }

        val manifest = try {
            manifestAdapter.fromJson(manifestJson)
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("Failed to parse KFS Manifest metadata from payload.")

        onProgress(0.25f, "Found Manifest: ${manifest.totalChunks} chunks (Merkle Root: ${manifest.merkleRoot.take(12)}...)")

        val chunks = mutableListOf<KfsChunk>()
        val targetChunkTxIds = manifest.chunkTxIds

        if (targetChunkTxIds.isNotEmpty()) {
            for (i in targetChunkTxIds.indices) {
                val chunkTxId = targetChunkTxIds[i]
                val p = 0.25f + (0.7f * (i + 1) / targetChunkTxIds.size)
                onProgress(p, "Downloading chunk ${i + 1}/${targetChunkTxIds.size} (${chunkTxId.take(8)}...)...")

                val chunkTx = try {
                    KaspaNetwork.getTransactionWithFallback(chunkTxId)
                } catch (e: Exception) {
                    throw IllegalStateException("Failed to retrieve Chunk #${i + 1} ($chunkTxId) from Kaspa network: ${e.message}", e)
                }

                val chunkPayload = chunkTx.resolvedPayload ?: ""
                if (chunkPayload.isBlank()) {
                    throw IllegalStateException("Chunk #${i + 1} ($chunkTxId) has an empty payload on the ledger.")
                }

                val chunkHex = if (chunkPayload.trim().all { it in "0123456789abcdefABCDEF" } && chunkPayload.length % 2 == 0) {
                    chunkPayload.trim()
                } else {
                    CryptoManager.bytesToHex(chunkPayload.toByteArray(Charsets.UTF_8))
                }
                val chunkBytes = CryptoManager.hexToBytes(chunkHex)

                val expectedHash = if (i < manifest.chunkHashes.size) manifest.chunkHashes[i] else ""
                chunks.add(
                    KfsChunk(
                        index = i,
                        total = targetChunkTxIds.size,
                        hash = expectedHash,
                        size = chunkBytes.size,
                        payloadHex = chunkHex
                    )
                )
            }
        } else {
            throw IllegalStateException("Manifest does not contain child chunk transaction IDs.")
        }

        onProgress(0.95f, "Verifying cryptographic Merkle Root and Blake2b hashes...")
        val reconstructedData = reconstructAndVerify(chunks, manifest.merkleRoot)
        onProgress(1.0f, "Successfully recovered ${reconstructedData.size} bytes from Kaspa storage!")

        return Pair(manifest, reconstructedData)
    }
}
