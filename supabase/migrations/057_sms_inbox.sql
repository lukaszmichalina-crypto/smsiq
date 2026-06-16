-- SMSIQ: inbound SMS inbox (two-way messaging)
-- Mirrors sms_outbox patterns from 056_sms_gateway.sql.
-- Inbound SMS are written by the gateway-inbox Edge Function (service_role, bypasses RLS).
-- Panel users read their tenant's inbox; replies go out through queue_sms() → sms_outbox.

CREATE TABLE IF NOT EXISTS sms_inbox (
  id                UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  gateway_id        TEXT        NOT NULL,
  from_phone        TEXT        NOT NULL,
  body              TEXT        NOT NULL,
  received_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),   -- when the SMS hit the phone
  matched_client_id UUID,                                 -- resolved on panel side if known
  contact_name      TEXT,                                 -- name from device contacts, if matched
  is_read           BOOLEAN     NOT NULL DEFAULT FALSE,
  source            TEXT        NOT NULL DEFAULT 'live'    -- 'live' (received) | 'backfill' (imported)
                    CHECK (source IN ('live','backfill')),
  raw               JSONB,
  -- Stable per-message fingerprint to dedupe live vs backfill of the same SMS.
  dedup_key         TEXT        NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, dedup_key)
);

CREATE INDEX IF NOT EXISTS idx_sms_inbox_tenant_received
  ON sms_inbox (tenant_id, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_sms_inbox_from_phone
  ON sms_inbox (tenant_id, from_phone, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_sms_inbox_gateway
  ON sms_inbox (gateway_id, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_sms_inbox_unread
  ON sms_inbox (tenant_id, is_read) WHERE is_read = FALSE;

-- RLS — same model as sms_outbox. Edge Functions use service_role and bypass RLS.
ALTER TABLE sms_inbox ENABLE ROW LEVEL SECURITY;

CREATE POLICY sms_inbox_tenant_read ON sms_inbox
  FOR SELECT USING (tenant_id = (SELECT tenant_id FROM user_profiles WHERE id = auth.uid()));

CREATE POLICY sms_inbox_tenant_update ON sms_inbox
  FOR UPDATE USING (tenant_id = (SELECT tenant_id FROM user_profiles WHERE id = auth.uid()));
