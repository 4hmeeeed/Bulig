# 05 — REST API Contract (`/api/v1`)

Laravel 11 + Sanctum. JSON only. All timestamps ISO-8601 UTC.

## 5.1 Authentication

Two token kinds, deliberately separated:

| Token | Issued to | Abilities |
|---|---|---|
| **User token** | a signed-in human | scoped by role |
| **Device token** | a registered device | `sync:push`, `sync:pull` only |

A device token cannot read the incident list. Relay devices belong to ordinary
residents; compromising one must not expose barangay emergency data.

```
POST /api/v1/auth/login          {email, password, device_id?} -> {token, user}
POST /api/v1/auth/logout
GET  /api/v1/auth/me
POST /api/v1/devices/register    {device_id, model, android_version, label?}
                                 -> {device_token, hmac_key, server_time, ttl_initial}
POST /api/v1/devices/heartbeat   {device_id, battery, has_internet}
```

`devices/register` is the only endpoint that returns `hmac_key`, and it returns
it exactly once. Re-registration of an existing `device_id` rotates the key and
invalidates prior device tokens.

## 5.2 Sync — the endpoints the whole architecture depends on

### `POST /api/v1/sync/packets` — push
Auth: device token. **Idempotent.**

```jsonc
{
  "client_clock": "2026-08-27T04:10:00Z",
  "device_id": "1f2e...",
  "packets": [
    {
      "packet_id": "9b1d...",            // immutable, minted at origin
      "emergency_id": "44ca...",
      "origin_device_id": "1f2e...",
      "hop_count": 3,
      "ttl_remaining": 7,
      "ttl_initial": 10,
      "created_at_device": "2026-08-27T03:52:11Z",
      "hmac": "a91c...",
      "route_path": ["1f2e...", "77bb...", "0a4d..."],
      "payload": {
        "type_code": "MEDICAL",
        "description": "Elderly man collapsed, not responding",
        "affected_count": 4,
        "children_count": 0,
        "elderly_count": 2,
        "mobility_limited_count": 1,
        "is_life_threatening": true,
        "vulnerability_notes": null,
        "latitude": 11.2447,
        "longitude": 125.0038,
        "accuracy_m": 12.4,
        "location_provider": "gps",
        "captured_at": "2026-08-27T03:52:09Z"
      }
    }
  ]
}
```

Response `200` — always 200 for a well-formed batch; per-packet outcomes are in
the body. A transport-level error must not force a client to re-send packets the
server already accepted.

```jsonc
{
  "server_time": "2026-08-27T04:10:02Z",
  "clock_offset_ms": -1200,
  "sync_log_id": 3312,
  "results": [
    { "packet_id": "9b1d...", "status": "ACCEPTED",
      "emergency_code": "BLG-2026-0417", "priority_level": "CRITICAL" }
  ],
  "summary": { "accepted": 1, "duplicate": 0, "rejected": 0 }
}
```

Per-packet `status`: `ACCEPTED` · `DUPLICATE` · `TTL_EXPIRED_ACCEPTED` ·
`INVALID_HMAC` · `REJECTED` (with `reason`).

### `GET /api/v1/sync/pull?since=<iso>` — pull
Auth: device token. Returns assignment and status changes relevant to this
device's signed-in responder, plus `emergency_types` and `priority_config` when
their versions have changed. Cursor-paginated.

## 5.3 Incidents
Auth: user token, policy-scoped.

```
GET   /api/v1/incidents            ?status=&priority=&type=&from=&to=&q=&page=
GET   /api/v1/incidents/{code}
PATCH /api/v1/incidents/{code}/status        {status, note}
PATCH /api/v1/incidents/{code}/priority      {level, reason}     # audited
GET   /api/v1/incidents/{code}/timeline      # status_history + packet_logs merged
GET   /api/v1/incidents/{code}/packets       # routing evidence
GET   /api/v1/incidents/map        ?bbox=&status=              # lightweight GeoJSON
```

`GET /incidents/map` returns GeoJSON trimmed to marker essentials — the full
record is a second request on marker click. A command center on a barangay
connection should not download every description to draw pins.

## 5.4 Assignments and responders

```
GET   /api/v1/responders                      ?status=&team=
POST  /api/v1/assignments                     {emergency_code, responder_id|team_id, notes}
PATCH /api/v1/assignments/{id}/accept
PATCH /api/v1/assignments/{id}/decline        {reason}
PATCH /api/v1/assignments/{id}/status         {status}   # EN_ROUTE|ON_SITE|RESOLVED
GET   /api/v1/me/assignments                  ?active=1  # responder app home
```

An assignment status change also writes `status_history` on the parent emergency
and advances `emergencies.status`, in one transaction.

## 5.5 Administration

```
GET|POST|PATCH|DELETE /api/v1/users            # official, sysadmin
GET|POST|PATCH        /api/v1/rescue-teams
GET|POST|PATCH        /api/v1/emergency-types
GET|PATCH             /api/v1/settings
GET                   /api/v1/sync-logs        ?device=&outcome=&from=&to=
GET                   /api/v1/packet-logs      ?event=&packet_id=
GET                   /api/v1/audit-logs       ?user=&action=&from=&to=
GET                   /api/v1/metrics/evaluation
```

`GET /metrics/evaluation` returns the §24 operational metrics computed from
`packet_logs` and `sync_logs` — delivery success by hop count, transmission delay
percentiles, duplicate suppression rate, TTL enforcement count, sync success
rate. **The evaluation chapter is a database query, not a spreadsheet.**

## 5.6 Errors

Standard Laravel envelope, `422` for validation, `403` for policy denial, `401`
for auth. Errors carry a stable `code` string so the Android client can branch
without parsing prose:

```json
{ "message": "Device is revoked.", "code": "DEVICE_REVOKED" }
```

## 5.7 Rate limits

| Endpoint group | Limit |
|---|---|
| `auth/login` | 5/min per IP |
| `devices/register` | 3/hour per IP |
| `sync/packets` | 30/min per device |
| read endpoints | 120/min per user |

`sync/packets` is limited per **device**, not per IP — a whole barangay behind
one NAT must not throttle itself during exactly the event the system exists for.
