# 02 — Roles and Permissions

Five roles. Enforced server-side by policies and middleware; the UI only hides
what the server already refuses.

| Role | Key | Platform |
|---|---|---|
| Resident / Reporter | `resident` | Android |
| Responder | `responder` | Android |
| Barangay Operator | `operator` | Web |
| Barangay Official / Administrator | `official` | Web |
| System Administrator | `sysadmin` | Web |

## 2.1 Permission matrix

`✓` allowed · `—` denied · `◐` restricted (see notes)

| Capability | resident | responder | operator | official | sysadmin |
|---|:--:|:--:|:--:|:--:|:--:|
| Create emergency report | ✓ | ✓ | — | — | — |
| View own submitted reports | ✓ | ✓ | ✓ | ✓ | ✓ |
| View all incidents | — | ◐¹ | ✓ | ✓ | ✓ |
| View incident precise location | — | ◐¹ | ✓ | ✓ | ✓ |
| View reporter identity | — | ◐¹ | ✓ | ✓ | ◐² |
| Relay packets over BLE | ✓ | ✓ | — | — | — |
| Accept assignment | — | ✓ | — | — | — |
| Update incident status | — | ◐¹ | ✓ | ✓ | — |
| Assign responder to incident | — | — | ✓ | ✓ | — |
| Override priority | — | — | ◐³ | ✓ | — |
| Manage responders | — | — | — | ✓ | ✓ |
| Manage rescue teams | — | — | — | ✓ | ✓ |
| Manage users / roles | — | — | — | ◐⁴ | ✓ |
| View packet & network monitoring | — | — | ✓ | ✓ | ✓ |
| View sync logs | — | — | ✓ | ✓ | ✓ |
| View audit logs | — | — | — | ✓ | ✓ |
| Generate reports | — | — | ✓ | ✓ | ✓ |
| Manage emergency types | — | — | — | ✓ | ✓ |
| Edit priority scoring config | — | — | — | ◐⁵ | ✓ |
| Database maintenance / backups | — | — | — | — | ✓ |
| Revoke a device registration | — | — | — | ✓ | ✓ |

**Notes**

1. **Responder scoping.** A responder sees only incidents assigned to them or to
   their rescue team, and may update status only on those. This is the primary
   privacy boundary in the system — see `docs/LIMITATIONS.md` and §22 of the
   proposal on data minimisation.
2. **sysadmin and personal data.** The system administrator maintains the
   platform, not the emergency response. Access to reporter identity is
   permitted but every such read is written to `audit_logs`.
3. **Operator priority override.** An operator may raise priority but not lower
   it. Lowering requires an `official`. Both are audited with a mandatory reason.
4. **Official user management.** An official manages barangay staff and
   responder accounts. They cannot create or modify a `sysadmin`.
5. **Scoring config.** An official may adjust weights; only a `sysadmin` may add
   or remove scoring rules. Every change is versioned and audited, because
   changing the formula retroactively changes how past decisions read.

## 2.2 Enforcement

- **API:** Sanctum bearer tokens. `role:` middleware for coarse gating,
  Laravel policies (`EmergencyPolicy`, `AssignmentPolicy`, `UserPolicy`) for
  per-record decisions.
- **Device tokens:** a registered device receives a device token scoped to
  packet sync only. A stolen device token cannot read the incident list.
  This matters because relay devices belong to ordinary residents.
- **Web:** the same policies, plus route middleware on the command center.
- **Audit:** every assignment, status change, priority override, user or role
  change, config change, and device revocation writes to `audit_logs` with
  actor, action, subject, before/after, IP, and timestamp.

## 2.3 The relay is not a role

Any Bulig install relays packets, regardless of the signed-in role — a
responder's phone carries a resident's report exactly as another resident's
phone would. Relaying grants **no** ability to read incident data beyond the
packet the device is carrying, and the payload is HMAC-protected so a relay
cannot alter it (`06-ble-protocol.md` §6.7). This is deliberate: relay capacity
scales with total installs, not with staff headcount.
