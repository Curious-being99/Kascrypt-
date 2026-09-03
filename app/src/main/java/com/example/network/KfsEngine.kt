package com.example.network

import com.example.crypto.CryptoManager
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
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

            if (utxos.isNotEmpty() && wallet != null) {
                val totalSompis = utxos.sumOf { it.utxoEntry?.amount ?: 0L }
                val kasAmount = totalSompis.toDouble() / 100_000_000.0
                logs.add("Funded wallet active: ${String.format(java.util.Locale.US, "%.4f", kasAmount)} KAS (${utxos.size} UTXOs)")
            } else {
                logs.add("Notice: Wallet has 0 confirmed UTXOs on live BlockDAG node.")
            }

            val (manifest, chunks) = prepareKfsChunks(data, fileId)
            logs.add("Generated ${chunks.size} chunks. Merkle Root: ${manifest.merkleRoot.take(16)}...")

            _uploadStatus.value = "Splitting into ${chunks.size} chunks (Blake2b Merkle Root: ${manifest.merkleRoot.take(8)}...)"

            var successfulBroadcasts = 0
            var lastErrorMessage: String? = null
            var hasNodeRejection = false

            for (chunk in chunks) {
                val progress = 0.1f + (0.8f * (chunk.index + 1) / chunks.size)
                _uploadProgress.value = progress
                _uploadStatus.value = "Broadcasting Chunk ${chunk.index + 1}/${chunks.size} to Live Node..."
                logs.add("Broadcasting Chunk #${chunk.index + 1} (${chunk.size} bytes)")

                try {
                    val tx = buildKaspaTransaction(
                        payloadHex = chunk.payloadHex,
                        wallet = wallet,
                        utxos = utxos
                    )
                    val request = KaspaSubmitTransactionRequest(transaction = tx)
                    val response = KaspaNetwork.api.submitTransaction(request)
                    if (response.error != null) {
                        hasNodeRejection = true
                        lastErrorMessage = response.error
                        logs.add("Node error: ${response.error}")
                    } else if (response.transactionId != null) {
                        logs.add("Chunk #${chunk.index + 1} TxId: ${response.transactionId}")
                        successfulBroadcasts++
                    } else {
                        logs.add("Chunk #${chunk.index + 1} sent to node")
                    }
                } catch (e: retrofit2.HttpException) {
                    hasNodeRejection = true
                    val err = e.response()?.errorBody()?.string() ?: e.message()
                    lastErrorMessage = "HTTP ${e.code()}: $err"
                    logs.add("Node response (${e.code()}): $err")
                } catch (e: Exception) {
                    hasNodeRejection = true
                    val msg = e.message ?: "Network error"
                    lastErrorMessage = msg
                    logs.add("Network transmission error: $msg")
                }
            }

            // Finalize Master KFS Transaction
            val manifestJson = manifestAdapter.toJson(manifest)
            val manifestHex = CryptoManager.bytesToHex(manifestJson.toByteArray(Charsets.UTF_8))
            val masterTxId = manifest.merkleRoot

            logs.add("Computed KFS Merkle Root: ${manifest.merkleRoot}")

            try {
                val masterTx = buildKaspaTransaction(
                    payloadHex = manifestHex,
                    wallet = wallet,
                    utxos = utxos
                )
                val masterRequest = KaspaSubmitTransactionRequest(transaction = masterTx)
                val masterResponse = KaspaNetwork.api.submitTransaction(masterRequest)
                if (masterResponse.error != null) {
                    hasNodeRejection = true
                    lastErrorMessage = masterResponse.error
                    logs.add("Master manifest node error: ${masterResponse.error}")
                } else if (masterResponse.transactionId != null) {
                    logs.add("Master manifest TxId: ${masterResponse.transactionId}")
                }
            } catch (e: retrofit2.HttpException) {
                hasNodeRejection = true
                val err = e.response()?.errorBody()?.string() ?: e.message()
                lastErrorMessage = "HTTP ${e.code()}: $err"
                logs.add("Master manifest sync notice: HTTP ${e.code()} - $err")
            } catch (e: Exception) {
                hasNodeRejection = true
                val msg = e.message ?: "Connection error"
                lastErrorMessage = msg
                logs.add("Master manifest sync notice: $msg")
            }

            val isOverallSuccess = !hasNodeRejection && successfulBroadcasts == chunks.size

            _uploadProgress.value = 1.0f
            if (isOverallSuccess) {
                _uploadStatus.value = "KFS Process Finished. Merkle Root: ${manifest.merkleRoot.take(12)}..."
            } else {
                _uploadStatus.value = "KFS Node Broadcast Rejected (HTTP/RPC Error). Merkle Root: ${manifest.merkleRoot.take(12)}..."
            }

            val result = KfsBroadcastResult(
                success = isOverallSuccess,
                manifest = manifest,
                broadcastedChunks = successfulBroadcasts,
                rootTxId = if (isOverallSuccess) masterTxId else null,
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

    private fun buildKaspaTransaction(
        payloadHex: String,
        wallet: com.example.crypto.KaspaWalletManager.KaspaWallet?,
        utxos: List<KaspaUtxoEntry>,
        feeSompis: Long = 10000L
    ): KaspaTransaction {
        if (utxos.isEmpty() || wallet == null) {
            return KaspaTransaction(payload = payloadHex)
        }

        val selectedUtxo = utxos.firstOrNull { (it.utxoEntry?.amount ?: 0L) > feeSompis } ?: utxos.firstOrNull()
        if (selectedUtxo == null || selectedUtxo.outpoint?.transactionId == null) {
            return KaspaTransaction(payload = payloadHex)
        }

        val inputAmount = selectedUtxo.utxoEntry?.amount ?: 0L
        val changeAmount = (inputAmount - feeSompis).coerceAtLeast(0L)
        val utxoScriptPubKey = selectedUtxo.utxoEntry?.scriptPublicKey
        val scriptPubKey = KaspaScriptPublicKey(
            scriptPublicKey = if (!utxoScriptPubKey?.scriptPublicKey.isNullOrBlank()) {
                utxoScriptPubKey!!.scriptPublicKey
            } else {
                "20" + wallet.publicKeyHex + "ac"
            },
            version = utxoScriptPubKey?.version ?: 0
        )

        val inputs = listOf(
            KaspaTransactionInput(
                previousOutpoint = KaspaOutpoint(
                    transactionId = selectedUtxo.outpoint.transactionId,
                    index = selectedUtxo.outpoint.index ?: 0L
                ),
                signatureScript = "",
                sequence = 0L,
                sigOpCount = 1
            )
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

        return KaspaTransaction(
            version = 0,
            inputs = inputs,
            outputs = outputs,
            lockTime = 0L,
            subnetworkId = "0000000000000000000000000000000000000000",
            gas = 0L,
            payload = payloadHex
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
}
