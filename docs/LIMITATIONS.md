# Limitations and Honest Scope

Stating these clearly is part of the deliverable. A system that overstates what
it can do during a disaster is worse than one that admits its boundaries.

## 1. Delivery is opportunistic, never guaranteed
Bulig improves the *probability* that a report escapes a connectivity outage. It
promises nothing. If no other Bulig device comes within BLE range, and the
originating device never regains connectivity, the report stays on the phone.
The UI says exactly this and never displays "sent" when it means "stored."

## 2. BLE range is short and easily degraded
Roughly 10–30 m line of sight, substantially less through concrete, and further
degraded by rain, human bodies, and 2.4 GHz congestion. No claim of wider range
appears anywhere in this project.

## 3. Android background execution is hostile to this design
Doze, App Standby, and aggressive manufacturer battery managers can suspend or
kill the relay service. Mitigated by a foreground service and a battery-optimisation
exemption prompt — mitigated, not solved. Relay effectiveness with the screen off
is expected to be measurably worse, and that result will be reported rather than
omitted.

## 4. Device compatibility varies
BLE peripheral (advertising) support is absent on some older chipsets; those
devices degrade to central-only and can receive but not originate relays. GATT
connection limits and MTU vary by manufacturer.

## 5. Battery cost is real
Continuous scanning and advertising consume power. Duty cycling and a
battery-floor threshold reduce but do not eliminate this. Measured impact is
reported in the evaluation.

## 6. Relay density is the binding constraint
Store-and-forward needs participants. In a barangay with few installs, multi-hop
delivery is unlikely. This is a property of the approach, not a defect of the
implementation, and it bounds any claim about real-world effectiveness.

## 7. GPS is imprecise and sometimes unavailable
Indoors, under canopy, or in dense construction, fixes may be poor or absent. The
system records accuracy, flags approximate locations, and offers a manual pin.
A displayed marker is an estimate.

## 8. Clocks drift offline
Corrected via measured offset at sync (`07-offline-sync.md` §7.6). Packets whose
corrected timing remains impossible are flagged and excluded from timing
statistics rather than silently averaged.

## 9. The mesh has no confidentiality on the wire
Payloads are authenticated (HMAC) but not encrypted between devices. A determined
attacker with a BLE sniffer within range could read a report in transit. Payload
encryption is documented future work, not a claimed feature.

## 10. Bulig does not replace official emergency services
It is a barangay-level coordination aid. It is not connected to 911, PNP, BFP, or
any government dispatch system, and the app states so on first launch and in the
report confirmation screen.

## 11. This is a single-barangay pilot prototype
Not a city-wide system. Not production-hardened. Scaling, high availability,
disaster recovery, and multi-barangay federation are out of scope.

## 12. Wi-Fi Direct / Wi-Fi P2P is optional future work
Not part of the core prototype. Listed as Phase 5 enhancement only.

## 13. No AI or machine learning in the core system
Prioritisation is a documented rule set, chosen deliberately for explainability.
ML is noted as possible future work and is not required, used, or claimed.

## 14. Field facts about the barangay are TO BE VALIDATED
Current emergency procedure, population, response times, communication tools,
and connectivity conditions must come from interviews, observation, and approved
records. No placeholder statistic appears in this repository. Where a figure is
needed and not yet gathered, it is marked **TO BE VALIDATED**.

## 15. Server availability is a single point of failure
The Laravel/MySQL server is not redundant. If it is down, synced packets queue on
devices and deliver when it returns — but the command center is unavailable in
the meantime. The mesh keeps working; the coordination layer does not.
