# Testing Auto-Auth

The verification layers answer different questions:

1. JUnit policy/storage and local HTTP fixtures prove deterministic recovery/auth behavior without live credentials.
2. Actual-client tests prove production mixin application, UI actions and session installation on each loader.
3. Packaged-client tests launch the distributed jar with a separate test mod, checking production packaging/remapping too.
4. The release owner verifies the configured application and real providers with an owned account. Synthetic tests cannot establish provider acceptance or Realms entitlement.

## Prerequisites and a clean checkout

Use Java 21. Run commands at the repository root. On Windows use the included PowerShell-compatible wrapper; on Linux/macOS replace `.\gradlew.bat` with `bash gradlew`. First use downloads pinned Gradle/Minecraft/loader/test dependencies, so dependency setup needs network access. Fixture execution does not require real Microsoft credentials or a client ID.

When this work is published, clone the actual repository URL supplied by its owner. This local repository currently has no published remote/history; none is created by a test command. Do not copy a personal Minecraft config, account store or game directory into the checkout.

For a fresh generated build state:

```powershell
.\gradlew.bat clean :common:test :fabric:build :neoforge:build verifyArtifacts --console=plain
```

`clean` removes generated Gradle outputs; it does not delete source or your Minecraft worlds. Test runs use independent game directories under `.tmp/agent-game-test/` instead of a player instance. Ordinary runtime jars are in `fabric/build/libs/` and `neoforge/build/libs/`.

## Deterministic tests

The full automated verification command is `.\gradlew.bat clean verifyAll --no-parallel --console=plain`. It runs the shared JUnit suite, both builds and artifact checks, then actual development and packaged clients for both loaders. Client launches are ordered so they do not compete for the same graphical desktop.

Build dependencies are pinned and `gradle/verification-metadata.xml` checks their hashes. When deliberately updating dependencies, regenerate with `--write-verification-metadata sha256`, review the hashes and normalize that generated XML to LF. The root Linux-native configuration also resolves hosted-CI native variants when generating checksums on Windows.

```powershell
.\gradlew.bat :common:test --console=plain
```

JUnit XML and HTML are written to `common/build/test-results/test/` and `common/build/reports/tests/test/`. A successful Gradle result must include real executed tests, not `NO-SOURCE`, zero matching tests, or stale files from an old run.

Focused examples:

```powershell
.\gradlew.bat :common:test --tests '*RecoveryControllerTest' --tests '*StatusTrackerTest' --tests '*SessionProbeTest' --console=plain
.\gradlew.bat :common:test --tests '*ConfigStoreTest' --console=plain
.\gradlew.bat :common:test --tests '*MicrosoftAuthTest' --console=plain
```

Use the test class names present under `common/src/test/java/` if additional classes are introduced. The tests cover explicit A→B selection, fallback account policy, single flight, fake-clock cooldown/retry limits, rejected post-install verification, removed accounts, cancelled/stale results, client-executor publication, status classification and config persistence/repair. Auth fixtures use a real loopback HTTP server for callback state/PKCE/lifecycle and the Microsoft→Xbox→XSTS→Minecraft→profile request chain.

Storage tests use temporary test paths and synthetic tokens. Failure injection must show that a write failure does not replace a valid account file or report successful persistence. Config APIs return detached snapshots; stale operations may not recreate removed accounts.

No test may use a developer's saved login, contact a production provider through its fixture transport, or log token/code/header values. Test-only injection is selected through isolated test sources and mixins, never a production configuration flag.

## Actual clients: both loaders

These tests require a graphical desktop and working OpenGL support:

```powershell
.\gradlew.bat :fabric:clientTest --console=plain
.\gradlew.bat :neoforge:clientTest --console=plain
```

Expected fresh outputs:

- `.tmp/agent-game-test/fabric/results.txt` and `runtime-tests.xml`
- `.tmp/agent-game-test/neoforge/results.txt` and `runtime-tests.xml`

The scenarios use synthetic identity/services at the provider boundary, while exercising production session installation, hooks and widgets. They check identity/profile/permissions, Realms availability invalidation, constructor cleanup, waiting-parent handling, cancellation and account management. A local/offline connection fixture checks connection lifecycle; it does not establish a real online-mode provider handshake.

Require a successful Gradle process, a fresh complete scenario result and `ALL CLIENT CHECKS PASSED`. A crash, deadline, missing XML/result, missing scenario or failed assertion is failure even if another scenario passed. Preserve diagnostic screenshots on failure where available; do not reuse an earlier result to interpret a later run.

The harness is isolated from the ordinary development runs. Its test classes/resources/mod metadata are outside `main` source sets, and `verifyArtifacts` rejects their presence in shipped jars.

## Packaged clients

```powershell
.\gradlew.bat :fabric:packagedClientTest --console=plain
.\gradlew.bat :neoforge:packagedClientTest --console=plain
```

Expected outputs use the same format in:

- `.tmp/agent-game-test/fabric-packaged/`
- `.tmp/agent-game-test/neoforge-packaged/`

These runs load the ordinary distributed runtime jar plus a separately built test mod. A development-source launch alone does not prove that Fabric remapping, refmaps, access declarations, metadata or service loading work in the distributed jar. Require the same fresh-result/exit gates as ordinary client tests.

## Artifact and release checks

```powershell
.\gradlew.bat verifyArtifacts --console=plain
```

This builds/inspects both distributions, checks loader resources/platform service registration, verifies the embedded client ID against the build setting, Fabric refmap and language resources, and rejects test classes/resources in shipping jars. It allows an explicitly blank public client ID for credential-free development tests.

The release metadata check is stricter:

```powershell
.\gradlew.bat verifyRelease "-Pazure_client_id=<your-owned-public-client-id>" --console=plain
```

The configured default is `d18bb4d8-a27f-4451-a87f-fe6de4436813`, so `verifyRelease` works without a property argument. Replace the placeholder only to build with a different public application ID. **Never provide a client secret.** Explicitly blank/malformed IDs fail the gate. A correctly formatted ID only proves metadata shape; it is not confirmation of account type, redirect registration or provider authorization.

Do not reuse previously built jars with a different ID. Let Gradle process resources and build the final release candidate with the same configured property before provider checks.

## Live release checklist

Live checks: **not executed**. The supplied default public ID is configured, and SKCraft's separate public `client.json` override uses `68d506ce-63f9-4991-aed0-9b4316defcf2`. These identifiers alone do not verify registration settings, personal-account consent or Realms access.

The release owner must use a private throwaway game directory and record loader/version, release jar checksum, date and pass/fail for every applicable case. Keep only sanitized observations, not account credentials.

1. Verify the owner's public/native-client Microsoft registration supports personal accounts and the current `http://localhost:<ephemeral-port>/` redirect. Check both IPv4/IPv6 loopback listener and browser resolution on supported operating systems. Do not use another application's ID as an undocumented workaround.
2. Install the candidate runtime jar into a clean matching Fabric client with its declared Fabric API dependency, then repeat on NeoForge. Ensure the isolated runtime-test mod is absent.
3. Add an owned Microsoft account through the system browser, completing consent/MFA manually. Confirm the account is saved and Add does not silently switch identity.
4. Use that account explicitly. With two owned accounts, select B while A is current; confirm B is used and becomes the default. Observe the correct profile and permission behavior without printing tokens.
5. Close Minecraft normally, restart the same private instance, and prove saved-token refresh works without another Add flow. Confirm refreshed credentials are durably retained, then repeat on the other loader.
6. With an actually expired/rejected session on the owned test account, join a real **online-mode** test server. Confirm waiting→refresh→successful join without restart. Cancel a pending join and confirm no later auto-join occurs. A synthetic invalid-token fixture or offline-mode server is not a substitute for this live result.
7. On an account with the required entitlement, enter Realms, exercise recovery from an authentication error and confirm the Realms session/profile is correct. Repeated Realms-only authentication errors must terminate at a useful error rather than loop. Preserve parental-consent and incompatible-client behavior.
8. Cancel browser login before and during completion, close its screen, and quit Minecraft. Verify no listener remains and no late account or screen action occurs. Verify normal session/connection behavior when automatic mode is disabled.
9. Confirm all automated gates pass for the exact release candidate and preserve the sanitized checklist record. Mark any unavailable live case explicitly unverified and do not label the release fully verified.

Human browser consent/MFA is not automated. Do not store live account passwords or refresh tokens in pull-request CI, repository fixtures or artifact uploads.

## CI

`.github/workflows/verify.yml` runs deterministic tests/build/artifact checks on Windows and Linux, with actual/packaged client tests under Linux Xvfb/Mesa. It is triggered by pushes, pull requests, manual dispatch and the configured nightly schedule once the owner publishes/enables the workflow in a repository. This local checkout cannot demonstrate remote CI until such a remote/run exists.

The implementation machine is Windows. Local successful Windows runs do not verify the hosted Linux graphical environment. Until a real hosted run passes, Xvfb/Mesa/OpenGL support remains an explicit environment verification item; adjust the graphical runner if its actual failure requires it, without dropping either loader's client gate.

Client jobs upload only the selected result XML/text and failure screenshot paths. Preserve no live account configs, raw provider responses, authorization URLs, callback codes or credential headers. Keep live-account release work trusted and opt-in; never execute it for untrusted pull-request code.
