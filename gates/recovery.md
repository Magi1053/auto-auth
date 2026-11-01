# Gates: recovery

Scope: explicit selection, generation-safe recovery and consistent client session state.

- [x] R1: Explicit selection, single flight, bounded transient retries, permanent-failure action and stale/cancel behavior have passing tests.
  EVIDENCE: RecoveryControllerTest passes 14 regression cases, including wrong-account selection, ordering, removal, cancellation, cooldown, post-install rejection and executor marshalling.
- [x] R2: Client-thread session/cache replacement, Realms cache invalidation and canonical disconnect hooks implemented.
  EVIDENCE: SessionApplier.installPreparedOnClientThread replaces identity/profile/permissions/social/keys/reporting/Realms/telemetry atomically on the client thread; new RealmsAvailabilityAccess clears cached availability. DisconnectedScreenMixin targets canonical DisconnectionDetails constructor. Both loader compileJava tasks passed; parent actual-client verification remains required by GATES.md.
- [x] R3: Status snapshots classify outages separately and discard obsolete results; required check/connect semantics preserved.
  EVIDENCE: Six StatusTrackerTest and four SessionProbeTest cases pass, covering obsolete generation, queued publication, sentinel tokens, unavailable-vs-rejected auth and nonblocking CHECKING/UNKNOWN.
- [x] R4: Tests pass and a prepared-session seam supports integrator's actual-client verification.
  CHECK: .\gradlew.bat :common:test --tests '*RecoveryControllerTest' --tests '*StatusTrackerTest' --tests '*SessionProbeTest' :fabric:compileJava :neoforge:compileJava --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: 2026-11-01 focused Gradle run: 24 tests passed and both loader compilers succeeded. PreparedSession(User,UserApiService,ProfileResult,UserProperties), prepare(User,MinecraftSessionService,Proxy) and installPreparedOnClientThread are available to isolated test mixins; production has no fixture flags.
