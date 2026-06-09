package pl.surfiq.smsgateway.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "surfiq_gw_secure",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular prefs if encryption unavailable (old devices)
            context.getSharedPreferences("surfiq_gw_fallback", Context.MODE_PRIVATE)
        }
    }

    var supabaseUrl: String
        get() = prefs.getString("supabase_url", "") ?: ""
        set(v) { prefs.edit().putString("supabase_url", v).apply() }

    var gatewayToken: String
        get() = prefs.getString("gateway_token", "") ?: ""
        set(v) { prefs.edit().putString("gateway_token", v).apply() }

    var tenantId: String
        get() = prefs.getString("tenant_id", "") ?: ""
        set(v) { prefs.edit().putString("tenant_id", v).apply() }

    var gatewayId: String
        get() = prefs.getString("gateway_id", "") ?: ""
        set(v) { prefs.edit().putString("gateway_id", v).apply() }

    var simSubscriptionId: Int
        get() = prefs.getInt("sim_sub_id", -1)
        set(v) { prefs.edit().putInt("sim_sub_id", v).apply() }

    var pollIntervalSec: Int
        get() = prefs.getInt("poll_interval_sec", 20)
        set(v) { prefs.edit().putInt("poll_interval_sec", v).apply() }

    val isConfigured: Boolean
        get() = supabaseUrl.isNotEmpty() && gatewayToken.isNotEmpty() &&
                tenantId.isNotEmpty()    && gatewayId.isNotEmpty()

    fun clear() = prefs.edit().clear().apply()
}
