# Auto-Auth behavioral specification

Client-only Minecraft 1.21.1 session recovery for Fabric and NeoForge, Java 21. The shared source implements the policy; loader adapters provide initialization, platform paths and optional client commands. No Architectury runtime is required. This is a clean-room implementation; upstream mod source is not copied.

## Scope

- Detect whether the current session is usable for online multiplayer.
- Add Microsoft accounts through browser OAuth; retain final refresh results.
- Switch to an explicitly selected saved account without restarting.
- Optionally recover and resume one pending server connection or reopen Realms.
- Support multiple accounts and a default fallback account.

Server-side authentication, password collection, token/cookie import and automatic migration from another mod's account format are outside this scope. Dedicated servers do not initialize this client mod.

## Status and freshness

- `UNKNOWN`: not checked, or the provider is unavailable. No interception on this state alone.
- `CHECKING`: a background probe is in flight. Connections remain allowed.
- `ONLINE`: Yggdrasil `joinServer` and `hasJoinedServer` confirmed the expected profile UUID.
- `INVALID`: rejected credentials, absent join confirmation or a different confirmed profile UUID. Automatic mode can intercept.
- `OFFLINE`: missing profile/user, unsupported account type or an unusable token (`null`, blank, `0`, case-insensitive `null` string). Automatic mode can intercept.

The client captures a user/service snapshot before dispatching the probe. The worker uses a random server ID and never reads the mutable Minecraft user. Authentication-unavailable and unexpected transport failures produce `UNKNOWN`; credential rejection produces `INVALID`.

Checks share one in-flight probe per session generation. Session replacement or explicit rejection invalidates that generation; an old result cannot publish into the new session. Relevant menu ticks refresh an aged status after 60 seconds, including an unknown outage result, instead of hammering the service every tick. Initialization, a disconnect, a manual check and successful replacement also request checks. Neither `CHECKING` nor `UNKNOWN` is a reason to stall a join.

## Ownership and session replacement

Network requests and profile/permission preparation run on background executors. Account mutation/persistence, Minecraft field changes and screen/toast changes run through the client executor. Disk writes currently run on-client so their outcome is known before session installation.

A successful refresh must:

1. Match the selected account UUID and still refer to the saved account/token that started the operation.
2. Persist final access/refresh tokens before applying the session. A manual selection also atomically stores that account as the default. A removed account is never recreated by a late refresh.
3. Prepare the profile, user properties and authenticated API service without mutating the old session. Preparation failure retains the old usable session.
4. Recheck operation ownership and the saved refreshed token before commit.
5. Install the user, profile future, user-property future, API service, social manager, profile-key manager, reporting context, Realms fetcher and telemetry manager consistently in one client-thread turn. The Realms client is constructed from the replacement user, never from the previous mutable Minecraft user.
6. Invalidate cached Realms availability and auth status, then verify the replacement session.

Failure to retire old telemetry resources after commit is logged without converting an already installed session into a failed-login result. No offline API-service substitution may turn preparation failure into apparent success.

## Recovery policy

Only one active recovery attempt is admitted at a time, including explicit requests. Manual selection takes a UUID and never falls back to a different account. Automatic selection prefers current profile UUID, then default UUID, then the sole saved account. No match or no refresh token exposes an account action.

Transient failures retry no earlier than 30 seconds after failure. After three failed attempts, automatic retries stop until explicit Retry or a subsequently verified online state resets recovery. Revoked credentials, missing accounts, storage failure, mismatched identity and other permanent failures require user action immediately. A newly applied credential that the verification probe rejects also requires user action; success at the refresh endpoint must not create an infinite refresh/verification loop.

A successful online verification permits recovery from a later expiration. Explicit Retry resets the retry budget but cannot start a concurrent operation. Generation changes discard stale/cancelled completions and prohibit late session installation. Shutdown cancels result ownership and pending joins. Network cancellation of an already running refresh is separate from cancelling a pending connection.

## Server connection

When automatic mode is enabled and status is `INVALID` or `OFFLINE`:

1. Cancel `ConnectScreen.startConnecting`.
2. Capture the unwrapped parent, address, server data, quickPlay flag and transfer state as the pending request.
3. Open a server waiting screen and drive recovery/status checks.
4. Once verified `ONLINE` and not busy, consume the pending request before invoking `startConnecting` once with the same captured parameters.

Pending server and Realms requests are mutually exclusive. Waiting-screen Cancel/Escape, disconnect and shutdown clear the pending request. A cancelled connection never resumes even if its credential refresh finishes later. Disabling automatic mode while waiting clears the request and returns to the unwrapped parent. Opening account management does not resume a connection behind that UI; resumption occurs after returning to its waiting screen.

All disconnect constructor overloads delegate through the canonical `DisconnectionDetails` constructor. Its parent is unwrapped and pending joins are cancelled. A disconnect parent must never remain an abandoned waiting screen.

## Realms

A Realms `AUTHENTICATION_ERROR` in automatic mode rejects the current auth status even if the previous global status said `ONLINE`, records an unwrapped Realms parent and opens a waiting screen. After verified replacement, consume that pending request and reopen Realms once.

A cached Realms authentication result is invalidated during session replacement. A Realms-only rejection cannot repeatedly force otherwise successful refreshes: only one automatic recovery is attempted until Realms actually reports availability success or the user explicitly retries. If the same Realms-only authentication error persists, preserve the vanilla error instead of looping. Parental consent, incompatible-client and other non-authentication errors retain vanilla behavior.

## Browser add-account lifecycle

Adding saves an account; it does not implicitly replace the current Minecraft session.

1. Create an ephemeral IPv4-loopback HTTP listener and a matching IPv6-loopback listener on the same port where supported, sharing per-operation state/verifier and cleanup. Never bind a wildcard address. The browser redirect is `http://localhost:<port>/` and the application registration must match it.
2. Open authorization-code flow for `XboxLive.signin offline_access`, `prompt=select_account`, CSRF state and S256 PKCE challenge.
3. Accept exactly one valid `GET /` callback with the matching state and a code or denial. Wrong methods/paths, malformed/duplicate query fields, missing code and wrong state do not consume the operation.
4. Exchange the code with its verifier, then Microsoft → Xbox → XSTS → Minecraft → profile. Refresh flow uses the same downstream chain. Persist the final Minecraft access token and current Microsoft refresh token; retain the existing refresh token only when a refresh response omits its replacement.
5. Close the listener as token exchange begins. Success, denial, timeout, browser-open failure, cancellation, screen removal and shutdown clean up listener/executors/deadline. Closing the operation also interrupts ongoing token requests and prevents a later UI/account result.

The browser deadline is three minutes. HTTP requests have bounded timeouts and do not automatically follow redirects carrying credentials. Provider bodies and credential strings never enter player-facing errors or log messages. Typed failures distinguish retryable network/service errors from authentication/account action failures.

## Config and account UI

Path: `<gameDir>/config/autoauth/config.json`. Fields: `debug`, `auto`, `clientId`, `accounts` (`uuid`, `username`, `accessToken`, `refreshToken`) and optional `defaultUuid`.

The optional public `config/autoauth/client.json` file contains only `clientId` and takes precedence when nonblank. Otherwise the legacy account config's `clientId`, then the jar default, is used. The supplied jar default is `d18bb4d8-a27f-4451-a87f-fe6de4436813`; SKCraft's separate public override is `68d506ce-63f9-4991-aed0-9b4316defcf2`. First creation and blank-field migration of the account config seed the configured jar default. Invalid/unreadable public settings stop authentication rather than silently changing applications. Pack updates copy the public file directly, never the account/credential store. Player-facing errors must not instruct normal players to recompile or edit build/environment settings.

Account APIs return detached snapshots. Mutations validate and persist a candidate before publishing it. Same-directory temporary writes are forced to disk and atomically replace the destination; an unsupported atomic replacement is an explicit failure, not a destructive truncation fallback. The previous valid file survives write failure. Corrupt input is preserved before repair; failure to create that recovery copy preserves the original and surfaces an error.

The account list scrolls, includes every saved account and supports explicit Use/Remove. Add returns to the existing account screen; Done/Cancel/Escape return to the intended parent. Waiting screens show progress or actionable failure and provide Retry/Accounts/Cancel. Player-visible mod text uses translation keys. Plaintext credential storage is disclosed. Logs, screenshots and reports must not contain live credentials.

## Verification and release boundary

Common JUnit tests exercise policy/storage and loopback HTTP fixtures without booting Minecraft or using real accounts. Isolated test mods exercise production UI, mixins and session installation in both actual clients; packaged tests also load the distributed runtime jars. Test fixtures/entrypoints must never ship in those jars.

`verifyArtifacts` checks both distributions and fixture exclusion. `verifyRelease` additionally rejects blank/malformed public application IDs. A syntactically valid ID is not proof that Microsoft/Xbox/Minecraft accepts that registration. The owner must record live fresh sign-in, restart/refresh, online-mode-server recovery and entitled Realms checks before release. See [TESTING.md](TESTING.md); fixture tests do not claim those live checks passed.
