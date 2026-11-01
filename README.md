# Auto-Auth

Client-only Minecraft **1.21.1** mod for **Fabric** and **NeoForge**, built with Java 21. It checks online-session validity, refreshes a saved Microsoft login and resumes a pending server or Realms connection without restarting Minecraft.

## Using it

Open Auto-Auth by clicking its status text on the multiplayer, disconnect or Realms screen.

- **Add Microsoft account** opens your browser and saves the account after consent. Adding an account does not switch the active session.
- Click a saved account to sign in with that exact account. A successful refresh saves its final tokens and makes it the default before replacing the session.
- **Settings** controls automatic recovery and optional `/autoauth` debug commands.
- While a connection waits, use **Retry**, **Accounts**, or **Cancel**. Cancel prevents that connection from resuming; an existing background refresh may finish independently.
- Cancelling browser login closes its localhost listener and interrupts its token exchange. Leaving the login screen or stopping Minecraft also closes it.

Automatic recovery prefers the current profile's saved account, then the default, then the only saved account. It never collects your password. Browser authentication uses OAuth authorization code flow with S256 PKCE.

A rejected token or missing session can pause a connection when automatic mode is enabled. `CHECKING` and `UNKNOWN` never block a connection. Service outages remain unknown instead of being treated as rejected credentials. Transient refresh failures have a 30-second cooldown and stop after three failures; permanent failures require an explicit retry or a new saved login.

## Saved data

Configuration lives at `<gameDir>/config/autoauth/config.json`:

- `auto`: automatically recover rejected/missing sessions.
- `debug`: enable client `/autoauth status`, `refresh`, `config` and `auto` commands.
- `clientId`: optional public Microsoft application ID override; otherwise use the jar's configured default.
- `accounts`: saved profile UUID, username, Minecraft access token and Microsoft refresh token.
- `defaultUuid`: fallback account and most recently selected successful manual login.

The default public Microsoft client ID is `d18bb4d8-a27f-4451-a87f-fe6de4436813`. Set an installation/pack override in **`config/autoauth/client.json`**:

```json
{
    "clientId": "68d506ce-63f9-4991-aed0-9b4316defcf2"
}
```

This optional file takes precedence over the legacy `config.json` field and the jar default. Missing/blank values retain that fallback. It contains no accounts or tokens, so modpacks can copy it directly on updates without replacing saved logins. Do not distribute `config.json`, which contains player credentials. SKCraft uses the override above. Refresh tokens are application-specific; changing the client ID may require adding the account again.

**Saved tokens are plaintext credentials.** Keep the entire config folder, recovery copies and test/live game directories private. Do not upload them with bug reports. Writes use an atomic file replacement; storage failures are surfaced instead of pretending the login was saved. Invalid JSON or invalid account records are preserved in a neighboring `config.json.corrupt-<uuid>.json` before repair.

## Building and testing

From this directory on Windows:

```powershell
.\gradlew.bat :common:test :fabric:build :neoforge:build verifyArtifacts --console=plain
.\gradlew.bat :fabric:clientTest :neoforge:clientTest --console=plain
.\gradlew.bat :fabric:packagedClientTest :neoforge:packagedClientTest --console=plain
```

Run every automated gate with `.\gradlew.bat verifyAll --no-parallel --console=plain`.

On Linux/macOS replace `.\gradlew.bat` with `bash gradlew`. Client tests require a graphical desktop or a suitable virtual display/OpenGL environment. The wrapper downloads the pinned build dependencies on first use.

The automated tests use synthetic accounts and local HTTP fixtures: no Microsoft credentials or configured client ID are required. Real provider acceptance is a separate release check. See [TESTING.md](TESTING.md) for commands, result locations, isolation and the release checklist; [SPEC.md](SPEC.md) defines the behavioral contract.

Runtime jars are written to `fabric/build/libs/` and `neoforge/build/libs/`. Ship each loader's ordinary runtime jar, without `-sources`, `-javadoc` or `-runtime-tests` suffixes. Do not install the test mod in a player instance.

## Microsoft application registration and releases

The jar embeds the default public client ID configured by `azure_client_id` in `gradle.properties`. A release requires the owner's Microsoft **public/native-client** application registration, personal Microsoft account support and a matching loopback redirect. The current browser redirect is `http://localhost:<ephemeral-port>/`; listeners accept IPv4 and, where available, IPv6 loopback on the same port. Verify that registration and browser resolution work on supported systems. Never embed a client secret.

Override the public `azure_client_id` Gradle property only when building jars with a different default. `verifyRelease` rejects a blank or malformed ID; it does not prove the registration is accepted by Microsoft/Xbox/Minecraft. Players and pack makers can set `client.json` without recompiling.

A release also needs recorded live fresh-login, restart/refresh, online-mode server and entitled Realms checks. Those checks require an owned account and human browser consent/MFA; they have not been replaced by synthetic tests.

## License

MIT © 2026 Magi1053

## Publishing

Set the GitHub repository variable `MODRINTH_PROJECT_ID` to the Auto-Auth Modrinth project ID and the repository secret `MODRINTH_TOKEN` to a token allowed to publish versions to that project.

Update `version` in `gradle.properties`, commit the release changes, and push a matching tag such as `v1.0.0`. The publish workflow checks the tag, runs `clean verifyAll`, and publishes separate Fabric and NeoForge versions. Fabric declares Fabric API as required. A first successful automated run and live Modrinth publication still need verification.
