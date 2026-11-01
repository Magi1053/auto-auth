# Gates: packaged loader verification

Scope: run actual distributed Auto-Auth jars on Fabric and NeoForge with isolated regression-test mods.

- [x] P1: Fabric production launch uses raw obfuscated Minecraft, production KnotClient/libraries, and intermediary production/test jars from mods only.
  CHECK: .\gradlew.bat :fabric:packagedClientTest --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Final run 2026-11-01 15:08 America/Chicago passed in28s. Actual KnotClient loaded raw SHA-1-verified Mojang client jar and remapped it to intermediary (logs show net.minecraft.class_*); production/test jars staged in .tmp/agent-game-test/fabric-packaged/mods. javap confirmed intermediary test bytecode. No project build output is permitted on this launch classpath. Runtime libraries checksum-verified from official metadata/Maven.
- [x] P2: NeoForge packaged launch scans production/test jars from mods only and cannot pick up project production/test class outputs.
  CHECK: .\gradlew.bat :neoforge:packagedClientTest --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Final run 2026-11-01 15:07 America/Chicago passed in23s. Mod list contains production autoauth and test autoauth_tests from staged jars. Empty packagedLaunch source set and loadedMods=[]; generated packagedRegressionLegacyClasspath.txt contains zero project classes/resources paths, enforced again by JavaExec classpath guard. NeoForge uses its MDG transformed game runtime, while Auto-Auth itself is the actual distributed jar.
- [x] P3: Both packagedClientTest tasks enforce five-minute runtime limit and require fresh nonzero passing scenario XML and result marker.
  EVIDENCE: gradle/packaged-tests.gradle defines Duration.ofMinutes(5), checked stale evidence deletion, fresh SHA-256 packaged-inputs.json, results marker, and exact count/set of shared runtime-tests/scenarios.json in passing runtime-tests.xml; both tasks executed those verifiers successfully. Missing, skipped, failed, duplicate or omitted scenarios fail the wrapper.
- [x] P4: Both actual packaged clients pass their regression scenarios locally.
  EVIDENCE: .tmp/agent-game-test/{fabric,neoforge}-packaged/runtime-tests.xml each reports9 tests, zero failures/errors/skips; each results.txt contains ALL CLIENT CHECKS PASSED and all nine stable IDs. Both normal process exits were0 and Gradle successful. Source/staged SHA-256 values match both manifests. During verification, localhost IPv6 preference mismatch was reproduced and fixed with dual-loopback listeners; final browser-add scenario passes on both loaders. No live Microsoft/Xbox/Realms credentials used.
