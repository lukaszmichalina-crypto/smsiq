package pl.surfiq.smsgateway.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import pl.surfiq.smsgateway.model.GatewayConfig
import pl.surfiq.smsgateway.model.SmsMessage
import java.io.IOException
import java.util.concurrent.TimeUnit

class SupabaseGatewayClient(private val config: GatewayConfig) {

    companion object {
        private const val TAG = "GatewayClient"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun fnUrl(name: String) = "${config.supabaseUrl}/functions/v1/$name"

    private fun authHeaders() = Headers.Builder()
        .add("x-gateway-token", config.gatewayToken)
        .add("x-tenant-id",     config.tenantId)
        .add("x-gateway-id",    config.gatewayId)
        .add("Content-Type",    "application/json")
        .build()

    private fun post(fnName: String, payload: Any): Response? {
        val body = gson.toJson(payload).toRequestBody(JSON_MT)
        val req  = Request.Builder()
            .url(fnUrl(fnName))
            .headers(authHeaders())
            .post(body)
            .build()
        return try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            Log.w(TAG, "$fnName network error: ${e.message}")
            null
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Claims next queued/retrying message for this gateway. Returns null if queue is empty. */
    fun claimNext(): SmsMessage? {
        val resp = post("gateway-claim-next", emptyMap<String, Any>()) ?: return null
        return resp.use { r ->
            if (r.code == 204) return null
            if (!r.isSuccessful) {
                Log.w(TAG, "claimNext ${r.code}")
                return null
            }
            val json = r.body?.string() ?: return null
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val m: Map<String, Any?> = gson.fromJson(json, type)
            SmsMessage(
                id          = m["id"]           as? String ?: return null,
                tenantId    = m["tenant_id"]    as? String ?: return null,
                gatewayId   = m["gateway_id"]   as? String ?: return null,
                toPhone     = m["to_phone"]     as? String ?: return null,
                body        = m["body"]         as? String ?: return null,
                priority    = (m["priority"]    as? Double)?.toInt() ?: 5,
                attempts    = (m["attempts"]    as? Double)?.toInt() ?: 0,
                maxAttempts = (m["max_attempts"] as? Double)?.toInt() ?: 5,
            )
        }
    }

    /** Updates SMS status. For retrying/no_signal pass scheduledAt (ISO-8601 UTC string). */
    fun updateStatus(
        smsId:       String,
        status:      String,
        lastError:   String? = null,
        scheduledAt: String? = null,
    ): Boolean {
        val payload = mutableMapOf<String, Any?>("sms_id" to smsId, "status" to status)
        lastError?.let   { payload["last_error"]   = it }
        scheduledAt?.let { payload["scheduled_at"] = it }
        return post("gateway-update-status", payload)?.use { it.isSuccessful } ?: false
    }

    /** Sends heartbeat. Called every 60 s. */
    fun heartbeat(
        deviceName:   String,
        phoneNumber:  String?,
        batteryLevel: Int,
        isCharging:   Boolean,
        signalStatus: String,
        status:       String = "online",
        appVersion:   String = "1.0.0",
    ): Boolean {
        val payload = mapOf(
            "device_name"    to deviceName,
            "phone_number"   to phoneNumber,
            "battery_level"  to batteryLevel,
            "is_charging"    to isCharging,
            "signal_status"  to signalStatus,
            "status"         to status,
            "app_version"    to appVersion,
        )
        return post("gateway-heartbeat", payload)?.use { it.isSuccessful } ?: false
    }

    /** Writes a log entry to Supabase. Non-critical — fire and forget. */
    fun log(
        level:    String,
        message:  String,
        smsId:    String?             = null,
        metadata: Map<String, Any?>? = null,
    ): Boolean {
        val payload = mutableMapOf<String, Any?>("level" to level, "message" to message)
        smsId?.let    { payload["sms_id"]   = it }
        metadata?.let { payload["metadata"] = it }
        return post("gateway-log", payload)?.use { it.isSuccessful } ?: false
    }
}
