# Security policy

## Reporting a vulnerability

Please report security issues privately via GitHub's
**Report a vulnerability** button (Security → Advisories) on this
repository, rather than opening a public issue. Include the affected
version, the panel model, and steps to reproduce. You should get a
response within a week; fixes are released as new versions (there is
no update mechanism in the app itself, so please watch releases if
you run this daemon).

## Security model (summary)

- The daemon's command authority comes entirely from your MQTT
  broker: anyone who can publish to the broker can operate the panel
  (relays, screen, reboot). Use broker accounts/ACLs.
- MQTT is plaintext unless the **Use TLS** setting is enabled; with
  TLS the broker certificate is verified against the Android system
  trust store with hostname checking. Self-signed broker certificates
  are not supported.
- The broker password is stored AES/GCM-encrypted with a key in the
  Android Keystore. On a rooted panel this is protection against
  casual exposure, not against root.
- Screen on/off and reboot escalate via `su` where available: treat
  the daemon as a root-capable agent on rooted panels.
- See the Security section of the README for the full discussion.

## Supported versions

Only the latest release is supported with security fixes.
