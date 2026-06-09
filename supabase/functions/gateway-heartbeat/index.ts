import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
)

async function verifyGateway(tenantId: string, gatewayId: string, token: string): Promise<boolean> {
  const { data } = await supabase
    .from("sms_gateways")
    .select("gateway_token")
    .eq("id", gatewayId)
    .eq("tenant_id", tenantId)
    .single()
  return data?.gateway_token === token
}

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "x-gateway-token, x-tenant-id, x-gateway-id, content-type",
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: corsHeaders })
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 })

  const tenantId  = req.headers.get("x-tenant-id")
  const gatewayId = req.headers.get("x-gateway-id")
  const token     = req.headers.get("x-gateway-token")

  if (!tenantId || !gatewayId || !token) {
    return new Response(JSON.stringify({ error: "Missing auth headers" }), { status: 401, headers: corsHeaders })
  }

  const valid = await verifyGateway(tenantId, gatewayId, token)
  if (!valid) {
    return new Response(JSON.stringify({ error: "Invalid gateway token" }), { status: 401, headers: corsHeaders })
  }

  const body = await req.json()
  const now  = new Date().toISOString()

  const upsert = {
    id:                  gatewayId,
    tenant_id:           tenantId,
    gateway_token:       token,
    device_name:         body.device_name         ?? null,
    phone_number:        body.phone_number         ?? null,
    sim_subscription_id: body.sim_subscription_id  ?? null,
    status:              body.status               ?? "online",
    battery_level:       body.battery_level        ?? null,
    is_charging:         body.is_charging          ?? false,
    signal_status:       body.signal_status        ?? null,
    last_seen_at:        now,
    last_error:          body.last_error           ?? null,
    app_version:         body.app_version          ?? null,
    updated_at:          now,
  }

  const { error } = await supabase
    .from("sms_gateways")
    .upsert(upsert, { onConflict: "id,tenant_id" })

  if (error) {
    return new Response(JSON.stringify({ error: error.message }), { status: 500, headers: corsHeaders })
  }

  return new Response(JSON.stringify({ ok: true, server_time: now }), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  })
})
