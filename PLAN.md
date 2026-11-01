# Auto-Auth implementation

Implement all six slices from the 2026-11-01 review: explicit account switching, expired-server recovery, cancellable OAuth, Realms recovery, complete account UI, and automated distribution verification.

## Shared contracts and ownership

- OAuth leaf owns `auth/MicrosoftAuth.java`, `auth/HttpJson.java`, new `auth/AuthFailure.java`, OAuth helpers, and corresponding `common/src/test/java/.../auth/` tests (excluding status/session tests).
- Storage leaf owns `config/` and corresponding config tests. Retain the existing config-facing API/fields for integration. `save`/mutation failures must propagate (unchecked is acceptable); `updateTokens` must never recreate a removed account. Provide record snapshots and atomic persistence; no plaintext logging.
- Recovery leaf owns `session/`, `auth/AuthStatusService.java`, `auth/SessionApplier.java`, `mixin/` and corresponding policy/status tests. Retain boolean `attemptReauth(force)` and add explicit `attemptReauth(UUID)` for manual selection; expose `isBusy()` and `progressMessage()` for UI. All Minecraft/config mutation on client thread; network off-thread. Add seams for actual-client tests to install prepared synthetic identity/services without provider calls.
- Integrator owns Gradle, runtime test sources, CI/scripts/docs and `gui/`. Do not edit another leaf's files without a handoff.
- `MicrosoftAuth.AuthResult` retains its four fields and `toUser()`. Add `beginLogin()` returning `LoginOperation` (`result(): CompletableFuture<AuthResult>`, `close()` immediately cancels/closes listener). `AuthFailure` exposes `retryable()`. Keep existing refresh method.
- Java 21, Minecraft 1.21.1, Fabric and NeoForge remain fixed. No Architectury runtime, credential collection, or production fixture/backdoor flags. Test entrypoints live only in isolated test sources/artifacts.
- All text UTF-8 without BOM and LF. Scratch only `.tmp/agent-implementation/` or `.tmp/agent-game-test/`. No commit/push/deploy is requested.

## Work and evidence

Each leaf owns its gate file under `gates/`. Root integration gates are in `GATES.md`. Tests land with functionality; integration re-runs the complete suite and both actual clients. Missing provider credentials must be reported as an unverified release prerequisite, never as a passing live test.

## Status log

- 2026-11-01: Revalidated worktree and existing specification; began implementation with disjoint ownership.
- 2026-11-01: Completed OAuth, storage, recovery/session, Realms and UI implementation with isolated runtime tests. Actual packaged NeoForge exposed the localhost IPv6 preference mismatch; fixed dual-loopback listeners and covered both families.
- 2026-11-01: Root final clean verifyAll passed (1m 37s): 92 JUnit cases, both distributions/content checks and nine scenarios in each development/packaged loader run. Both packaged SHA-256 manifests independently matched distributed jars. CI configured but not executed remotely. Owned Microsoft registration/live-account release gate remains external and unverified; verifyRelease correctly rejects the blank ID.
- 2026-11-01: User supplied the default and SKCraft public application IDs. Configured jar default d18bb4d8-a27f-4451-a87f-fe6de4436813 and separate public client.json override support so pack copies cannot replace saved credentials. SKCraft source override uses 68d506ce-63f9-4991-aed0-9b4316defcf2. All 97 shared tests, both builds and verifyRelease passed; live provider acceptance is still separate.
