# Redo VPN (WireGuard + SSH + V2Ray/Xray)

## Build APK with GitHub Actions
1. Upload the whole project to your GitHub repository.
2. Open Actions → **Build APK with V2Ray** → Run workflow.
3. When the workflow succeeds, download the `Redo-VPN-apk` artifact and extract `app-debug.apk`.

The workflow builds the Android Xray core library (`libv2ray.aar`) before building the app. This first build may take several minutes.

## Protocols
- **WireGuard:** paste a WireGuard `.conf` configuration; this uses the existing WireGuard VPN backend.
- **SSH:** enter server host, port, username, password and optional payload; existing SSH/SOCKS behaviour is retained.
- **V2Ray/Xray:** add a `vless://`, `vmess://` or `trojan://` link. It starts a local SOCKS5 proxy at `127.0.0.1:10808`; configure that proxy in the app you want to use. This version does not yet route all Android device traffic through V2Ray as a system-wide VPN.

## Notes
- Only use server profiles you are authorized to use.
- The V2Ray core is built from the upstream AndroidLibXrayLite source at build time.
