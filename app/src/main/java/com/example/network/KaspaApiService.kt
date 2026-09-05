package com.example.network

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

object FlexibleLongAdapter {
    @FromJson
    fun fromJson(reader: JsonReader): Long {
        return if (reader.peek() == JsonReader.Token.STRING) {
            reader.nextString().toLongOrNull() ?: 0L
        } else {
            reader.nextLong()
        }
    }

    @ToJson
    fun toJson(writer: JsonWriter, value: Long?) {
        if (value == null) {
            writer.nullValue()
        } else {
            writer.value(value)
        }
    }
}

@JsonClass(generateAdapter = true)
data class KaspaNetworkInfoResponse(
    val networkId: String? = null
)

@JsonClass(generateAdapter = true)
data class KaspaBlockDagResponse(
    val blockCount: Long? = null,
    val headerCount: Long? = null,
    val tipHashes: List<String>? = null,
    val difficulty: Double? = null,
    val pastMedianTime: Long? = null,
    val virtualParentHashes: List<String>? = null,
    val pruningPointHash: String? = null,
    val virtualDaaScore: Long? = null
)

@JsonClass(generateAdapter = true)
data class KaspaAddressBalanceResponse(
    val address: String? = null,
    val balance: Long? = null
)

@JsonClass(generateAdapter = true)
data class KaspaUtxoEntry(
    val address: String? = null,
    val outpoint: KaspaOutpoint? = null,
    val utxoEntry: KaspaUtxoDetail? = null
)

@JsonClass(generateAdapter = true)
data class KaspaOutpoint(
    val transactionId: String? = null,
    val index: Long? = null
)

@JsonClass(generateAdapter = true)
data class KaspaUtxoDetail(
    val amount: Long? = null,
    val scriptPublicKey: KaspaScriptPublicKey? = null,
    val blockDaaScore: Long? = null,
    val isCoinbase: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class KaspaScriptPublicKey(
    val scriptPublicKey: String = "",
    val version: Int = 0
)

@JsonClass(generateAdapter = true)
data class KaspaTransactionInput(
    val previousOutpoint: KaspaOutpoint,
    val signatureScript: String = "",
    val sequence: Long = 0,
    val sigOpCount: Int = 1
)

@JsonClass(generateAdapter = true)
data class KaspaTransactionOutput(
    val amount: Long = 0,
    val scriptPublicKey: KaspaScriptPublicKey = KaspaScriptPublicKey()
)

@JsonClass(generateAdapter = true)
data class KaspaTransaction(
    val version: Int = 0,
    val inputs: List<KaspaTransactionInput> = emptyList(),
    val outputs: List<KaspaTransactionOutput> = emptyList(),
    val lockTime: Long = 0,
    val subnetworkId: String = "0000000000000000000000000000000000000000",
    val gas: Long = 0,
    val payload: String = "",
    val mass: Long = 0
)

@JsonClass(generateAdapter = true)
data class KaspaSubmitTransactionRequest(
    val transaction: KaspaTransaction,
    val allowOrphan: Boolean = true
)

@JsonClass(generateAdapter = true)
data class KaspaSubmitTransactionResponse(
    val transactionId: String? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class KaspaTransactionDetailResponse(
    val transactionId: String? = null,
    val transaction_id: String? = null,
    val hash: String? = null,
    val mass: Long? = null,
    val payload: String? = null,
    val payloadHex: String? = null,
    val payload_hex: String? = null,
    val blockTime: Long? = null,
    val block_time: Long? = null,
    val isAccepted: Boolean? = null,
    val is_accepted: Boolean? = null,
    val acceptingBlockHash: String? = null,
    val accepting_block_hash: String? = null
) {
    val resolvedTransactionId: String?
        get() = transactionId ?: transaction_id ?: hash

    val resolvedPayload: String?
        get() = payload ?: payloadHex ?: payload_hex

    val resolvedBlockTime: Long?
        get() = blockTime ?: block_time

    val resolvedIsAccepted: Boolean?
        get() = isAccepted ?: is_accepted
}

interface KaspaApiService {
    @GET("info/network")
    suspend fun getNetworkInfo(): KaspaNetworkInfoResponse
    
    @GET("info/blockdag")
    suspend fun getBlockDagInfo(): KaspaBlockDagResponse

    @GET("addresses/{address}/balance")
    suspend fun getAddressBalance(@Path("address") address: String): KaspaAddressBalanceResponse

    @GET("addresses/{address}/utxos")
    suspend fun getAddressUtxos(@Path("address") address: String): List<KaspaUtxoEntry>

    @GET("transactions/{transactionId}")
    suspend fun getTransaction(@Path("transactionId") transactionId: String): KaspaTransactionDetailResponse

    @POST("transactions")
    suspend fun submitTransaction(@Body request: KaspaSubmitTransactionRequest): KaspaSubmitTransactionResponse
}

object KaspaNetwork {
    private var currentUrl = "https://api.kaspa.org/"
    private var retrofit: Retrofit? = null
    
    fun setCustomNodeUrl(url: String) {
        val safeUrl = if (url.endsWith("/")) url else "$url/"
        if (currentUrl != safeUrl) {
            currentUrl = safeUrl
            retrofit = null // Force recreation
        }
    }

    fun getCurrentUrl(): String = currentUrl

    val api: KaspaApiService
        get() {
            if (retrofit == null) {
                val moshi = Moshi.Builder()
                    .add(FlexibleLongAdapter)
                    .build()
                retrofit = Retrofit.Builder()
                    .baseUrl(currentUrl)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
            }
            return retrofit!!.create(KaspaApiService::class.java)
        }

    suspend fun getTransactionWithFallback(transactionId: String): KaspaTransactionDetailResponse {
        return api.getTransaction(transactionId)
    }
}
