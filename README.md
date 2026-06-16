# SMSIQ — bramka SMS IQ Group

Produktowa bramka SMS dla **wszystkich projektów i klientów IQ Group** (nie tylko SurfIQ).
Android APK instalowany ręcznie (**sideload**) na dedykowanym telefonie z kartą SIM.

> **Pochodzenie:** repo powstało na bazie `funlikehelbrand-crypto/surfiq-sms-gateway`
> (żywa bramka szkolna FLH — zostaje jako backup). To repo `lukaszmichalina-crypto/smsiq`
> to wersja produktowa: dwukierunkowy inbox, import historii, kontakty, twardszy heartbeat.

> ⚠️ **Tylko sideload.** Aplikacja prosi o wrażliwe uprawnienia (`READ_SMS`, `RECEIVE_SMS`,
> `READ_CONTACTS`) — Google Play je odrzuca. NIE publikować w Google Play. Używać wyłącznie
> na firmowych telefonach-bramkach, za zgodą administratora (ekran zgody przy 1. uruchomieniu).

---

## Architektura

```
Panel / backend dowolnego produktu IQ
      │  queue_sms() → sms_outbox          (wychodzące)
      ▼
Supabase
      │  Edge Functions (claim-next, update-status, heartbeat, log, inbox)
      │  ← gateway token auth, service_role tylko w Edge Functions
      ▼
Android APK (ForegroundService)
  ├─ poll co 20s → claim_next_sms() (FOR UPDATE SKIP LOCKED)
  ├─ SmsManager.sendTextMessage()  → status callbacks → update
  ├─ retry backoff: 1/3/5/10/30 min
  ├─ heartbeat co 60s (bateria czytana bezpośrednio z BatteryManager)
  ├─ SmsIncomingReceiver → gateway-inbox → sms_inbox   (PRZYCHODZĄCE, dwukierunkowo)
  ├─ Import historii (content://sms) → sms_inbox (source=backfill, dedup)
  └─ Sync kontaktów (READ_CONTACTS) → sms_contacts (numer → nazwa)
```

---

## 1. Migracja Supabase

Wgraj w Supabase SQL Editor (projekt `pmkzzchckmpcmvtdhxwh`), w kolejności:

```
056_sms_gateway.sql     # bazowe: sms_gateways, sms_outbox, claim_next_sms() (już wgrane na FLH)
supabase/migrations/057_sms_inbox.sql      # NOWE: dwukierunkowy inbox (przychodzące SMS)
supabase/migrations/058_sms_contacts.sql   # NOWE: kontakty (numer → nazwa)
```

Sprawdź czy wykonało się bez błędów — szczególnie czy `claim_next_sms()` istnieje oraz czy
powstały tabele `sms_inbox` i `sms_contacts`. (Service-role nie może robić DDL przez REST API —
dlatego ręcznie w SQL Editor.)

---

## 2. Utwórz rekord bramki w Supabase

```sql
INSERT INTO sms_gateways (id, tenant_id, gateway_token, device_name, status)
VALUES (
  'hel-main',                                       -- gateway_id
  'f1ecd4aa-84cf-4e3b-97b8-9644247bfcea',           -- tenant_id FLH
  'moj-tajny-token-bramki-2026',                    -- gateway_token (zapamiętaj!)
  'Samsung Galaxy A15',
  'offline'
);
```

---

## 3. Deploy Edge Functions

```bash
cd SurfIQ/sms-gateway

# Zaloguj się (raz)
npx supabase login

# Linkuj projekt
npx supabase link --project-ref pmkzzchckmpcmvtdhxwh

# Deplouj wszystkie funkcje
npx supabase functions deploy gateway-heartbeat
npx supabase functions deploy gateway-claim-next
npx supabase functions deploy gateway-update-status
npx supabase functions deploy gateway-log
npx supabase functions deploy gateway-inbox        # NOWE: przychodzące SMS + kontakty
```

---

## 4. Budowanie APK

### Wymagania
- Android Studio Electric Eel lub nowszy (albo samo Android SDK + JDK 17)
- JAVA_HOME ustawiony na JDK 17

### Zbuduj debug APK (szybciej)

```bash
cd SurfIQ/sms-gateway

# Windows
gradlew.bat assembleDebug

# macOS/Linux  
./gradlew assembleDebug
```

APK gotowy w: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (bez obfuskacji — private app)

```bash
gradlew.bat assembleRelease
```

---

## 5. Instalacja APK na telefonie-bramce

### Przez ADB (USB)

```bash
# Połącz telefon USB, włącz tryb debugowania USB
adb devices
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Ręcznie (bez komputera)

1. Skopiuj APK na telefon (USB/Dysk Google/email)
2. W ustawieniach włącz "Instalacja z nieznanych źródeł"
3. Otwórz APK w menedżerze plików → Zainstaluj

---

## 6. Konfiguracja telefonu-bramki

**Obowiązkowe:**
- [ ] Ustawienia → Aplikacje → SMSIQ → Bateria → **Bez ograniczeń** (lub Brak optymalizacji)
- [ ] Ustawienia → Aplikacje → SMSIQ → Zezwolenia → **SMS: Zezwól**, **Telefon: Zezwól**
- [ ] Ustawienia → Powiadomienia → SMSIQ → **Włączone**
- [ ] Telefon **podłączony do ładowarki 24/7**
- [ ] Telefon umieszczony w miejscu z **najlepszym zasięgiem GSM** (okno, góra szafy)

**Zalecane:**
- [ ] Wyłącz automatyczne aktualizacje systemu (żeby restart nie ubił serwisu)
- [ ] Włącz autostart po restarcie (na Xiaomi/Samsung: Ustawienia → Aplikacje → Zarządzaj → SMSIQ → Autostart)
- [ ] Sprawdź VoLTE/VoWiFi u operatora — nie wpływa na SMS, ale poprawia ogólną stabilność
- [ ] Zaplanuj dzienny restart telefonu o 03:00 (Samsung: Ustawienia → Ogólne → Reset i Zaplanowane)

---

## 7. Pierwsze uruchomienie

1. Otwórz aplikację SMSIQ
2. Wypełnij:
   - **Supabase URL**: `https://pmkzzchckmpcmvtdhxwh.supabase.co`
   - **Gateway Token**: token z kroku 2
   - **Tenant ID**: UUID tenanta FLH
   - **Gateway ID**: `hel-main`
   - **SIM**: wybierz właściwą kartę (jeśli dual SIM)
   - **Poll interval**: `20` sekund
3. Kliknij **Test** → powinno pokazać ✅ Connection OK
4. Kliknij **Save & Start**
5. Zezwól na SMS, Powiadomienia, Wyłączenie optymalizacji baterii
6. W zakładce Diagnostics sprawdź `✅ ACTIVE`

---

## 8. Testowanie wysyłki

### Z aplikacji
1. W ekranie diagnostycznym wpisz swój numer
2. Kliknij **Send Test**
3. Powinien przyjść SMS w ciągu kilku sekund

### Z Supabase SQL Editor

```sql
-- Kolejkuj test SMS
SELECT queue_sms(
  'f1ecd4aa-84cf-4e3b-97b8-9644247bfcea',   -- tenant_id
  'hel-main',                                 -- gateway_id
  '+48887801809',                             -- numer docelowy
  'Test SMS z SMSIQ — ' || NOW()::TEXT,
  5,                                          -- priority
  NOW()                                       -- scheduled_at
);

-- Sprawdź status
SELECT id, to_phone, status, attempts, sent_at, last_error
FROM sms_outbox
ORDER BY created_at DESC
LIMIT 5;
```

---

## 9. Monitoring z panelu SurfIQ

Dodaj do panelu query sprawdzające stan:

```sql
-- Bramka online?
SELECT id, status, last_seen_at, battery_level, is_charging
FROM sms_gateways
WHERE tenant_id = 'f1ecd4aa-84cf-4e3b-97b8-9644247bfcea';

-- SMS wymagające uwagi
SELECT status, COUNT(*) FROM sms_outbox
WHERE tenant_id = 'f1ecd4aa-84cf-4e3b-97b8-9644247bfcea'
GROUP BY status;

-- Ostatnie logi błędów
SELECT level, message, created_at FROM sms_gateway_logs
WHERE tenant_id = 'f1ecd4aa-84cf-4e3b-97b8-9644247bfcea'
  AND level IN ('warning','error')
ORDER BY created_at DESC
LIMIT 20;
```

---

## 10. Debugowanie gdy SMS nie wychodzi

### Krok 1 — sprawdź status w Supabase
```sql
SELECT id, to_phone, status, attempts, last_error, updated_at
FROM sms_outbox WHERE id = '<uuid>';
```

**Statusy:**
- `queued` — czeka, nie tknięte (gateway offline?)
- `reserved` — gateway wziął, wysyłka w toku
- `sending` — wysłane do SmsManager, czeka na callback
- `retrying` — błąd, scheduled_at pokazuje kiedy następna próba
- `no_signal` — brak sieci, retry z backoffem
- `manual_review` — wyczerpane próby, wymaga ręcznej interwencji

### Krok 2 — sprawdź heartbeat bramki
```sql
SELECT id, status, last_seen_at, last_error FROM sms_gateways
WHERE tenant_id = '...';
```
Jeśli `last_seen_at` > 5 min temu → bramka offline.

### Krok 3 — sprawdź logi
```sql
SELECT * FROM sms_gateway_logs
WHERE sms_id = '<uuid>' ORDER BY created_at;
```

### Krok 4 — Logcat na telefonie
```bash
adb logcat -s "GatewayService:D" "SmsSender:D" "SmsStatusReceiver:D" "GatewayClient:D"
```

### Krok 5 — Ręczny retry
```sql
UPDATE sms_outbox
SET status = 'queued', scheduled_at = NOW(), last_error = NULL
WHERE id = '<uuid>';
```

---

## 11. Wysyłanie SMS z SurfIQ backend (Node.js)

```javascript
// Na VPS w server.js
async function sendSms(tenantId, gatewayId, toPhone, body, priority = 5) {
  const { data, error } = await supabase.rpc('queue_sms', {
    p_tenant_id:    tenantId,
    p_gateway_id:   gatewayId,
    p_to_phone:     toPhone,
    p_body:         body,
    p_priority:     priority,
    p_scheduled_at: new Date().toISOString(),
    p_created_by:   'server'
  })
  return data  // UUID wiadomości
}

// Przykład: SMS przy potwierdzeniu rezerwacji
await sendSms(
  tenantId, 'hel-main',
  '+48792011270',
  'FUN like HEL: Twoja rezerwacja kite 14.06 godz. 10:00 potwierdzona. Do zobaczenia!'
)
```

---

## Checklista przed sezonem

- [ ] Telefon-bramka naładowany i podłączony
- [ ] App pokazuje ✅ ACTIVE
- [ ] Wysłałeś test SMS i dostałeś go
- [ ] `sms_gateways.last_seen_at` < 2 min temu
- [ ] Bateria nieoptymalona (Ustawienia sprawdzone)
- [ ] SIM ma kredyty / plan SMS nieograniczony

---

## Rate limits (wbudowane w app)

- Max **30 SMS/minutę**
- Max **200 SMS/dzień**

Dla FLH szkoły wystarczy spokojnie. Jeśli potrzebujesz więcej — zmień `RateLimiter(maxPerMinute, maxPerDay)` w `GatewayService.kt`.

---

## 12. SMSIQ — dwukierunkowy inbox, import historii, kontakty

### Dwukierunkowy inbox (przychodzące SMS)
`SmsIncomingReceiver` łapie KAŻDY przychodzący SMS i wysyła go przez `gateway-inbox`
do tabeli `sms_inbox`. Panel czyta `sms_inbox` (np. odpowiedzi klientów „TAK"), a odpowiedzi
wychodzą normalnie przez `queue_sms()` → `sms_outbox`. Stare zachowanie STOP/opt-out działa dalej.

```sql
-- Ostatnie przychodzące
SELECT received_at, from_phone, contact_name, body, is_read
FROM sms_inbox
WHERE tenant_id = '...'
ORDER BY received_at DESC LIMIT 50;
```

### Import historii (backfill)
Przycisk **📥 Import SMS** w ekranie diagnostycznym czyta `content://sms` z telefonu
i wgrywa do `sms_inbox` (`source = 'backfill'`). Idempotentny — `dedup_key` jest identyczny
jak przy odbiorze na żywo, więc serwer (`upsert ignoreDuplicates`) odrzuca duplikaty.
Wymaga `READ_SMS`.

### Kontakty (numer → nazwa)
Przycisk **👥 Kontakty** czyta książkę telefoniczną (`READ_CONTACTS`) i wgrywa do
`sms_contacts`. Przychodzące SMS dostają `contact_name` automatycznie (PhoneLookup).
Wymaga `READ_CONTACTS`.

---

## 13. Decyzja: `applicationId`

`applicationId` pozostał **`pl.surfiq.smsgateway`** (bez zmian). Zmiana ID = inna aplikacja
przy instalacji = **utrata konfiguracji** (URL/token/tenant) na żywych telefonach `hel-main`
i `lukasz-priv`. Zmieniono tylko branding widoczny dla użytkownika (nazwa „SMSIQ", nagłówki,
notyfikacje).

> **Na przyszłość (świeża instalacja, nowy klient):** docelowo `eu.iqapp.smsiq` + osobna ikona.
> Przy migracji istniejącej bramki trzeba by od nowa wpisać config — robić świadomie.

---

## 14. Stabilność na Xiaomi / MIUI (lekcja z 16.06.2026)

Telefon `hel-main` (Xiaomi) potrafi „zniknąć": status w bazie zostaje `online`, ale
`last_seen_at` zamarza, a `battery_level` jest nieaktualny (MIUI ubił proces w tle).
**Diagnoza zawsze po `last_seen_at` / `updated_at`, NIE po polu `status`.** Heartbeat
czyta teraz baterię bezpośrednio (poprawne skalowanie EXTRA_LEVEL/EXTRA_SCALE).

Na telefonie-bramce (Xiaomi/Redmi/POCO):
- [ ] Ustawienia → Aplikacje → **SMSIQ** → **Autostart: WŁ**
- [ ] Bateria → **Bez ograniczeń** / „Brak optymalizacji"
- [ ] Zablokuj apkę w „ostatnich" (kłódka), żeby system jej nie zwijał
- [ ] Telefon na ładowarce 24/7

> Docelowo warto dodać po stronie serwera CRON oznaczający bramki jako `offline`, gdy
> `last_seen_at` > kilka minut — wtedy panel nie pokazuje fałszywego `online`.
