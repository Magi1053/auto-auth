# Gates: OAuth

Scope: cancellable secure browser flow and deterministic protocol tests.

- [x] O1: S256 PKCE, valid-state accept-once callback, wrong-request rejection and immediate cancellation cleanup implemented and tested.
  CHECK: .\gradlew.bat :common:test --tests "*auth.MicrosoftAuthTest" --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: MicrosoftAuthTest exercises actual localhost callback, RFC7636 PKCE vector and matching token verifier, invalid state/path/method/duplicate/missing code, cancellation before callback and during every HTTP phase, external future cancellation, denial and deadline cleanup. Local run passed 2026-11-01.
- [x] O2: Full refresh/code/Xbox/XSTS/Minecraft/profile HTTP fixtures cover rotation, malformed responses and safe classified failures.
  CHECK: .\gradlew.bat :common:test --tests "*auth.MicrosoftAuthTest" --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Real loopback HTTP fixture verifies form encoding, exact auth envelopes, claims, final token rotation/omission fallback, profile ID, code exchange without redundant refresh, 401/403/408/429/5xx, invalid_grant, numeric XSTS errors, malformed JSON/claims and no redirect forwarding. Exceptions redact provider bodies and credential values.
- [x] O3: Tests pass and expert defect review has no outstanding implementation gap.
  CHECK: .\gradlew.bat :common:test --tests "*auth.MicrosoftAuthTest" --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Targeted MicrosoftAuthTest run reports 45 passing cases after adding both-family callback/cleanup tests (BUILD SUCCESSFUL in 3s). Root full-suite run also passed these cases. Packaged NeoForge exposed actual localhost IPv6 preference mismatch; production now binds both loopback address families on one shared ephemeral port, verified by both-family unit requests and all nine packaged NeoForge scenarios with preferIPv6Addresses=system. Reread fixed cleanup-before-completion ordering and callback/cancel executor race. Owned files verified LF without BOM. Real provider acceptance remains a separate root release gate.
