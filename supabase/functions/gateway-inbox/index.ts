import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

// SMSIQ — gateway-inbox
// Receives INBOUND SMS (live + backfill) and contact-book entries from the
// gateway phone, writes them to sms_inbox / sms_contacts. Uses service_role
// (bypasses RLS) but authenticates the gateway by its token, exactly like the
// other gateway-* functions.

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

function json(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  })
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: corsHeaders })
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 })

  const tenantId  = req.headers.get("x-tenant-id")
  const gatewayId = req.headers.get("x-gateway-id")
  const token     = req.headers.get("x-gateway-token")

  if (!tenantId || !gatewayId || !token) {
    return json({ error: "Missing auth headers" }, 401)
  }
  if (!(await verifyGateway(tenantId, gatewayId, token))) {
    return json({ error: "Invalid gateway token" }, 401)
  }

  const body = await req.json().catch(() => null)
  if (!body) return json({ error: "Bad JSON" }, 400)

  // ── Contacts sync ─────────────────────────────────────────────────────────
  if (Array.isArray(body.contacts)) {
    const rows = body.contacts
      .filter((c: any) => c?.phone_e164 && c?.display_name)
      .map((c: any) => ({
        tenant_id:    tenantId,
        gateway_id:   gatewayId,
        phone_e164:   String(c.phone_e164),
        display_name: String(c.display_name),
        updated_at:   new Date().toISOString(),
      }))
    if (rows.length === 0) return json({ ok: true, contacts_upserted: 0 })
    const { error } = await supabase
      .from("sms_contacts")
      .upsert(rows, { onConflict: "tenant_id,phone_e164" })
    if (error) return json({ error: error.message }, 500)
    return json({ ok: true, contacts_upserted: rows.length })
  }

  // ── Inbound messages (live or backfill) ───────────────────────────────────
  if (Array.isArray(body.messages)) {
    const rows = body.messages
      .filter((m: any) => m?.from_phone && m?.body && m?.dedup_key)
      .map((m: any) => ({
        tenant_id:    tenantId,
        gateway_id:   gatewayId,
        from_phone:   String(m.from_phone),
        body:         String(m.body),
        received_at:  m.received_at ?? new Date().toISOString(),
        contact_name: m.contact_name ?? null,
        source:       m.source === "backfill" ? "backfill" : "live",
        dedup_key:    String(m.dedup_key),
        raw:          m.raw ?? null,
      }))
    if (rows.length === 0) return json({ ok: true, inserted: 0 })

    // Idempotent: ignore rows whose (tenant_id, dedup_key) already exists.
    const { data, error } = await supabase
      .from("sms_inbox")
      .upsert(rows, { onConflict: "tenant_id,dedup_key", ignoreDuplicates: true })
      .select("id")
    if (error) return json({ error: error.message }, 500)
    return json({ ok: true, inserted: data?.length ?? 0, received: rows.length })
  }

  return json({ error: "Expected 'messages' or 'contacts' array" }, 400)
})
