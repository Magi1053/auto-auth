# Gates: actual client regression suite

Scope: isolated synthetic-provider scenario mod loaded by both real loader clients.

- [x] C1: Test sources and mixin declarations compile under both Fabric and NeoForge.
  CHECK: .\gradlew.bat :fabric:compileRuntimeTestJava :neoforge:compileRuntimeTestJava --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: 2026-11-01 local compile passed for both source sets; separate runtimetest package avoids splitting production JPMS packages.
- [x] C2: Nine real client scenarios pass on Fabric with fresh complete result manifest and process success.
  EVIDENCE: Final clean verifyAll (2026-11-01) exited 0, BUILD SUCCESSFUL in 1m37s. Fresh fabric/runtime-tests.xml reports tests=9, failures=0, errors=0, skipped=0; results.txt ends ALL CLIENT CHECKS PASSED. Root independently verified the exact scenario catalog.
- [x] C3: Nine real client scenarios pass on NeoForge with fresh complete result manifest and process success.
  EVIDENCE: Same final clean verifyAll run: fresh neoforge/runtime-tests.xml reports tests=9, failures=0, errors=0, skipped=0; results.txt ends ALL CLIENT CHECKS PASSED. All session-owned services, real list mouse dispatch, local socket failure, Realms and OAuth UI paths ran.
- [x] C4: Packaged launches load distribution jars and exclude fixtures from production artifacts.
  EVIDENCE: Final clean verifyAll passed Fabric and NeoForge packaged-client runs; each fresh packaged/runtime-tests.xml reports tests=9, failures=0, errors=0, skipped=0. Artifact verification passed; root independently compared production source/staged SHA manifests for both loaders and confirmed no test Java process remained.
