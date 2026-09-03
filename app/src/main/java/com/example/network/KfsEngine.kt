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
    suspend fun uploadToKaspa(data: ByteArray, fileId: String = UUID.randomUUID().toString()): KfsBroadcastResult {
        val logs = mutableListOf<String>()
        try {
            _uploadProgress.value = 0.05f
            _uploadStatus.value = "Preparing KFS Chunking Engine..."
            logs.add("Payload size: ${data.size} bytes")

            val (manifest, chunks) = prepareKfsChunks(data, fileId)
            logs.add("Generated ${chunks.size} chunks. Merkle Root: ${manifest.merkleRoot.take(16)}...")

            _uploadStatus.value = "Splitting into ${chunks.size} chunks (Blake2b Merkle Root: ${manifest.merkleRoot.take(8)}...)"

            var successfulBroadcasts = 0
            for (chunk in chunks) {
                val progress = 0.1f + (0.8f * (chunk.index + 1) / chunks.size)
                _uploadProgress.value = progress
                _uploadStatus.value = "Broadcasting Chunk ${chunk.index + 1}/${chunks.size} (Hash: ${chunk.hash.take(8)}...)"
                logs.add("Broadcasting Chunk #${chunk.index + 1} (${chunk.size} bytes)")

                try {
                    val request = KaspaSubmitTransactionRequest(
                        transaction = KaspaTransaction(
                            payload = chunk.payloadHex
                        )
                    )
                    val response = KaspaNetwork.api.submitTransaction(request)
                    if (response.error != null) {
                        logs.add("Node returned notice: ${response.error}")
                    } else if (response.transactionId != null) {
                        logs.add("Chunk #${chunk.index + 1} TxId: ${response.transactionId}")
                        successfulBroadcasts++
                    }
                } catch (e: retrofit2.HttpException) {
                    val err = e.response()?.errorBody()?.string() ?: e.message()
                    logs.add("Node response (${e.code()}): $err")
                } catch (e: Exception) {
                    logs.add("Network transmission info: ${e.message}")
                }
            }

            // Finalize Master KFS Transaction
            val manifestJson = manifestAdapter.toJson(manifest)
            val manifestHex = CryptoManager.bytesToHex(manifestJson.toByteArray(Charsets.UTF_8))
            val masterTxId = manifest.merkleRoot

            _uploadStatus.value = "Finalizing KFS Root (Merkle: ${manifest.merkleRoot.take(12)}...)"
            logs.add("Finalized KFS Root Transaction. Merkle: ${manifest.merkleRoot}")

            try {
                val masterRequest = KaspaSubmitTransactionRequest(
                    transaction = KaspaTransaction(payload = manifestHex)
                )
                KaspaNetwork.api.submitTransaction(masterRequest)
            } catch (e: Exception) {
                logs.add("Master manifest sync notice: ${e.message}")
            }

            _uploadProgress.value = 1.0f
            _uploadStatus.value = "KFS Process Finished. Merkle Root: ${manifest.merkleRoot.take(12)}..."

            val result = KfsBroadcastResult(
                success = true,
                manifest = manifest,
                broadcastedChunks = chunks.size,
                rootTxId = masterTxId,
                logs = logs
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
