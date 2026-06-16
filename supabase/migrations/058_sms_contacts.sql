-- SMSIQ: device contacts mirror (number → name matching)
-- Synced from the gateway phone's address book by the gateway-inbox Edge Function
-- (action="contact"). Lets the panel show "Szkoła Hel" instead of a bare +48… number.

CREATE TABLE IF NOT EXISTS sms_contacts (
  id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  gateway_id    TEXT        NOT NULL,
  phone_e164    TEXT        NOT NULL,            -- normalised number (best effort)
  display_name  TEXT        NOT NULL,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, phone_e164)
);

CREATE INDEX IF NOT EXISTS idx_sms_contacts_tenant_phone
  ON sms_contacts (tenant_id, phone_e164);

ALTER TABLE sms_contacts ENABLE ROW LEVEL SECURITY;

CREATE POLICY sms_contacts_tenant_read ON sms_contacts
  FOR SELECT USING (tenant_id = (SELECT tenant_id FROM user_profiles WHERE id = auth.uid()));
