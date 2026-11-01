# Gates: Auto-Auth integration

Scope: all six implementation slices and automated verification for both loaders.

- [x] G1: Explicit account switching, consistent session/cache replacement and durable failure-aware persistence pass regression tests.
  EVIDENCE: Final clean verifyAll passed 2026-11-01. common/build/test-results/test contains 92 tests, zero failures/errors/skips, including preparation/provider failures, explicit selection, atomic persistence/default change and unavailable-store refusal. All four real client runs pass session-consistency and explicit-account-switch, asserting user/profile/permissions/API/social/keypair/reporting/telemetry/Realms replacement and actual pointer dispatch.
- [x] G2: Expired-server recovery, retry, stale completion and cancellation invariants pass deterministic and actual-client checks.
  EVIDENCE: RecoveryControllerTest (14), StatusTrackerTest (8) and SessionProbeTest (4) passed. All four runtime manifests pass connect-status-gates, pending-server-resume-and-cancel, disconnect-overloads and local-connection-failure (a real ephemeral loopback socket and vanilla disconnect).
- [x] G3: Cancellable PKCE OAuth and full local HTTP auth-chain tests pass, with immediate listener cleanup and sanitized failures.
  EVIDENCE: MicrosoftAuthTest has 45 passing cases, including actual HTTP chains, valid/malformed/duplicate callbacks, every cancellation phase and both loopback address families. Browser-add-and-cancel passes on both loaders in dev and packaged modes; NeoForge's actual IPv6 preference reproduced the old defect and validates the fix.
- [x] G4: Realms auth recovery refreshes credentials/cache once and preserves non-auth errors in both loader clients.
  EVIDENCE: realms-cached-auth-recovery passes in .tmp/agent-game-test/{fabric,neoforge,fabric-packaged,neoforge-packaged}/runtime-tests.xml. Uses synthetic provider boundary while executing production error hook, cache invalidation, reauth/reopen and non-auth error paths; no live entitlement claim.
- [x] G5: Scrollable translated account UI and stable navigation/progress/retry behavior pass actual-client checks.
  EVIDENCE: account-list-settings-navigation, explicit-account-switch and browser-add-and-cancel all pass in all four current-source runs. Tests cover overflow scrolling, last-row remove, real pointer selection, setting persistence, resize, parent restoration and add/cancel lifecycle. Source translation resources verified in distributed jars.
- [x] G6: CI, reproducible builds, jar/test-exclusion verification, and packaged launch checks exist and both loaders pass local verification.
  CHECK: .\gradlew.bat verifyAll --no-parallel --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Root independently ran clean verifyAll: BUILD SUCCESSFUL in 1m 37s, 60 actionable tasks (50 executed). Both builds, source LF/BOM check, jar-content checks, 92 shared tests and exact nine scenarios in each of four client runs passed. Source/staged SHA-256 values match packaged-inputs.json for both loaders; test processes exited normally. Wrapper/plugin versions and distribution/dependency checksums pinned, including Linux native variants. GitHub workflow configured for Windows/Linux checks and graphical Linux clients; hosted CI itself remains unexecuted because no remote/run exists.
- [ ] G7: Owned public client registration, real Microsoft login/refresh, online-mode server and entitled Realms are release-verified.
  EVIDENCE: Public IDs are now supplied and configured: default d18bb4d8-a27f-4451-a87f-fe6de4436813, SKCraft public override 68d506ce-63f9-4991-aed0-9b4316defcf2. verifyRelease passes metadata verification. Live account consent, online-mode server authentication and entitled Realms checks remain unexecuted; identifiers do not prove provider acceptance. TESTING.md records the live checklist.

ABANDON: G7 Live release verification still requires human-assisted account consent and server/Realms access, which were not performed. The provided public identifiers unblock metadata verification; this live gate remains explicitly unverified rather than being replaced by fixtures.
