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

  const { sms_id, status, last_error, scheduled_at } = await req.json()
  if (!sms_id || !status) {
    return new Response(JSON.stringify({ error: "Need sms_id and status" }), { status: 400, headers: corsHeaders })
  }

  const now = new Date().toISOString()
  const update: Record<string, unknown> = { status, updated_at: now }

  if (last_error !== undefined) update.last_error = last_error

  if (status === "sent")                       update.sent_at      = now
  if (status === "delivered")                  update.delivered_at = now
  if (status === "failed" ||
      status === "manual_review")              update.failed_at    = now
  if (scheduled_at)                            update.scheduled_at = scheduled_at

  const { error } = await supabase
    .from("sms_outbox")
    .update(update)
    .eq("id", sms_id)
    .eq("tenant_id", tenantId)

  if (error) {
    return new Response(JSON.stringify({ error: error.message }), { status: 500, headers: corsHeaders })
  }

  return new Response(JSON.stringify({ ok: true }), {
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  })
})
