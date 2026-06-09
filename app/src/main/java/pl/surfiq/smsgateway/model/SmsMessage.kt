package pl.surfiq.smsgateway.model

data class SmsMessage(
    val id:          String,
    val tenantId:    String,
    val gatewayId:   String,
    val toPhone:     String,
    val body:        String,
    val priority:    Int = 5,
    val attempts:    Int = 0,
    val maxAttempts: Int = 5,
)

data class GatewayConfig(
    val supabaseUrl:      String,
    val gatewayToken:     String,
    val tenantId:         String,
    val gatewayId:        String,
    val simSubscriptionId: Int    = -1,   // -1 = default SIM
    val pollIntervalSec:  Int    = 20,
)
