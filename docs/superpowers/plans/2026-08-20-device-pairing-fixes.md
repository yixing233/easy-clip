# Device Pairing and Revocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make device pairing, removal, and synchronization consistent across the Node server, Web client, Android client, and Windows desktop client.

**Architecture:** Keep the existing pairing-code workflow, but make the server issue a device-scoped credential only after approval. Every device sync/SignalR request must present that credential; removing a device clears it, disconnects the hub, and causes clients to clear local pairing state. Web response handling will consume each response body exactly once.

**Tech Stack:** Node.js/TypeScript, SQLite, SignalR/WebSocket, React/TypeScript, Kotlin/Android, WPF/.NET.

---

### Task 1: Define and enforce server-side device identity

**Files:**
- Modify: `server-node/src/db.ts`
- Modify: `server-node/src/auth.ts`
- Modify: `server-node/src/service.ts`
- Modify: `server-node/src/controllers.ts`
- Modify: `server-node/src/server.ts`
- Modify: `server-node/src/signalr.ts`

- [ ] Add a token hash/verification helper and an authenticated device actor shape.
- [ ] Issue a random token when a target device is approved, return it only through the pairing completion response, and store only its hash in `Devices.Token`.
- [ ] Reject device sync, device mutation, and hub connections when the device is missing, unbound, or the token is invalid; preserve admin/user session access for Web management routes.
- [ ] Prevent `generatePairingCode` from inserting an unknown/deleted device and make removal clear the token, invalidate pending requests, and disconnect the hub.
- [ ] Return explicit `401/403/410` JSON errors that identify expired, revoked, or invalid device credentials.

### Task 2: Fix Web response parsing and pairing lifecycle

**Files:**
- Modify: `web/src/api.ts`
- Modify: `web/src/pages/UserPage.tsx`
- Modify: `web/src/pages/SettingsPage.tsx`

- [ ] Replace clone/second-read logic with one `Response.text()` parse per request, including empty/204 handling.
- [ ] Remove the catch branch that suppresses `body stream already read` and surface the server error message.
- [ ] Ensure pairing completion stores/uses the returned device credential and displays actionable errors for expired/revoked requests.
- [ ] Run the Web production build and a real pair/approve/status/remove flow against the local server.

### Task 3: Make Android pairing state authoritative

**Files:**
- Modify: `android/app/src/main/java/clip/yixing/sync/data/SyncApi.kt`
- Modify: `android/app/src/main/java/clip/yixing/sync/util/SyncSettings.kt`
- Modify: `android/app/src/main/java/clip/yixing/sync/service/ClipboardMonitorService.kt`
- Modify: `android/app/src/main/java/clip/yixing/sync/ui/SettingsPage.kt`

- [ ] Persist the device token with the existing device ID and clear both token/paired state on `401/403/410`.
- [ ] Send the device credential on clipboard, history, and push requests; stop sync when local state is unpaired.
- [ ] On removal of the current device, immediately clear local pairing state and prevent background reconnect from recreating the device.
- [ ] Build the Android debug APK and exercise removal/reconnect behavior.

### Task 4: Update Windows desktop client

**Files:**
- Modify: `desktop/Services/ServerApi.cs`
- Modify: `desktop/Services/PushClient.cs`
- Modify: `desktop/Services/SettingsStore.cs`
- Modify: `desktop/Services/SyncEngine.cs`
- Modify: `desktop/Models/PairStatusResult.cs`
- Modify: `desktop/Models/PairingCodeResult.cs`

- [ ] Persist and send the device token for all device sync and SignalR requests.
- [ ] Clear local pairing state and stop reconnect loops after revoked/invalid credentials.
- [ ] Consume the pairing completion token once and handle server error payloads without masking them.
- [ ] Build the desktop project and verify one-process startup plus pairing/removal regression.

### Task 5: Test, deploy, and verify

**Files:**
- Create: `server-node/test/device-pairing.test.mjs` (or the repository's existing test location if one is present)

- [ ] Add a regression test covering pending pairing, approval/token issuance, authenticated sync, removal, token rejection, and re-pairing.
- [ ] Build server and Web, Android, and desktop clients; record any unavailable toolchain explicitly.
- [ ] Identify the online process manager, back up the SQLite database safely, upload only built artifacts, restart through its manager, and verify `/api/health` plus the live pair/remove flow at `https://nuxclip.157342.xyz`.

