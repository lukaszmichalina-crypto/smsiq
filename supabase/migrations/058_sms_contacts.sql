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

DROP POLICY IF EXISTS sms_contacts_staff_read ON sms_contacts;
CREATE POLICY sms_contacts_staff_read ON sms_contacts
  FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM user_roles WHERE user_id = auth.uid() AND role IN ('admin','staff')));
