# Android live connection

The personal Android build can keep its Alleycat connection alive while
Litter is in the background. This lets it receive agent progress and surface
approval, question, and completion notifications without relying on Firebase
push. On Android 13 and newer, allow notification permission when prompted so
those alerts are visible.

## Background modes

Open **Settings → Background → Stay connected in background**:

| Mode | Behaviour |
|---|---|
| **Off** | No live background service or live notifications. |
| **While working** (default) | Keeps the connection while a turn or prompt is pending, then stays connected for about two minutes after work ends. |
| **Always** | Keeps a background connection while a saved computer is available, including when no agent is working. |

Android displays a quiet ongoing notification while the foreground service is
running. It shows current work and a recent snippet; **Stop agent** interrupts
active turns, while **Disconnect** stops background connectivity until Litter
is opened again. When the app is open, in-app approval and question UI is used
instead of duplicate alert notifications.

Approval alerts offer **Approve**, **Always** (approve for the session), and
**Deny**. A question alert opens its thread in Litter. Finished and failed
turns also produce notifications that open the relevant thread.

## Battery optimisation

For longer background sessions, open **Settings → Background → Battery
optimisation** and allow Litter to ignore battery optimisations. This helps
prevent Doze and device-specific battery management from suspending the live
connection. The app holds a partial wake lock only while work or a prompt is
active; exempting Litter may increase battery use, so leave the restriction in
place if reliable background updates are not needed.

## Alleycat host setup

The daemon must allow Claude to ask for approvals. In the Windows VM's
`host.toml`, set:

```toml
[agents.claude]
bypass_permissions = false

[session]
pending_grace_secs = 1800
```

`bypass_permissions = false` enables Claude's permission prompts. The session
grace period is optional; it keeps pending prompts alive for up to 30 minutes
if the phone disconnects. Apply changes on the VM with:

```sh
kittylitter reload
```
