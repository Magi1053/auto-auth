# Gates: storage

Scope: failure-aware account persistence and validated snapshots.

- [x] S1: Atomic validated config persistence preserves prior data on failure and propagates errors.
  EVIDENCE: ConfigStoreTest.failedAtomicReplacementPreservesOriginalAndDeletesTemporary and unsupportedAtomicReplacementDoesNotFallBackToDestructiveWrite pass against the actual temporary-write/replacement implementation. ConfigException never retains raw IO/JSON causes.
- [x] S2: Migration/default selection/removal/refresh semantics and corrupt-input handling have deterministic tests.
  EVIDENCE: ConfigStoreTest covers client ID migration, automatic fallback selection, detached snapshots, duplicate/null/corrupt records, default repair, final rotation and removed-account refusal.
- [x] S3: Tests pass and expert defect review has no outstanding implementation gap.
  CHECK: .\gradlew.bat :common:test --tests '*config.*' --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Final clean verifyAll (2026-11-01) exited 0, BUILD SUCCESSFUL in 1m37s; ConfigStoreTest XML reports 16 tests, 0 failures, 0 errors. Includes atomic default-and-token rotation and failed-startup overwrite protection. Expert review fixed a leaked directory stream; direct setting saves roll back, and transactional setting setters publish only after persistence.
