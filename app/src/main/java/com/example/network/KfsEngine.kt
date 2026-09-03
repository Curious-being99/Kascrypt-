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
        val payloadHash = CryptoManager.hashBlake2bPersonalized(payloadBytes, tag)
        stream.write(payloadHash)

        // 15. SigHashType (1 byte)
        stream.write(sigHashType.toInt())

        return CryptoManager.hashBlake2bPersonalized(stream.toByteArray(), tag)
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
        // Kaspa P2PK opcode 0x41 (65 bytes) + 64-byte Schnorr signature + 0x01 (SIGHASH_ALL)
        val signatureScript = "41" + schnorrSigHex + "01"

        val signedInput = initialInput.copy(signatureScript = signatureScript)

        return KaspaTransaction(
            version = 0,
            inputs = listOf(signedInput),
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
