<div align="center">

<img src="docs/images/banner.jpg" alt="NekoXray Banner" width="100%" />

# NekoXray

### Modern Android Xray-core (`v26.10.31`) VPN & Proxy Client in Jetpack Compose & Material 3

[**🇬🇧 English**](README.md) · [**🇷🇺 Русский**](README_RU.md)

[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%E2%80%9336)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](#)
[![Xray-core](https://img.shields.io/badge/Xray--core-v26.10.31-22D3EE?style=for-the-badge)](#)
[![QUIC Protocols](https://img.shields.io/badge/QUIC-Hysteria%202%20%2B%20TUIC%20v5-38BDF8?style=for-the-badge)](#)
[![Post-Quantum](https://img.shields.io/badge/PQC-ML--KEM--768%20%2B%20ML--DSA--65-A855F7?style=for-the-badge)](#)
[![License: MIT OR LGPL-3.0-or-later](https://img.shields.io/badge/License-MIT%20OR%20LGPL--3.0--or--later-34D399?style=for-the-badge)](LICENSE)

<p align="center">
  <a href="#-highlights">Highlights</a> •
  <a href="#-supported-protocols--transports">Protocols</a> •
  <a href="#-screenshots">Screenshots</a> •
  <a href="#-network-resilience--always-on-vpn">Network Engine</a> •
  <a href="#-architecture">Architecture</a> •
  <a href="#-building-from-source">Build</a> •
  <a href="#-license">License</a>
</p>

</div>

---

**NekoXray** is a clean-room Android VPN and proxy client built from the ground up with **Kotlin**, **Jetpack Compose**, and **Material Design 3**, powered by native **Xray-core (`AndroidLibXrayLite` / `libv2ray.aar` v26.10.31)** and **Kwik QUIC (`0.12.2`)**. Crafted around a high-contrast **Wired Cyber** dark aesthetic, it combines post-quantum **VLESS REALITY + XHTTP**, **Hysteria 2**, **TUIC v5**, **SSH tunneling**, **anti-spoofing DoH + optional Direct DNS**, **smart GeoIP/GeoSite routing**, **Proxy-Only mode**, **seamless Wi-Fi ↔ LTE/5G handoff**, and **Android Always-On / Lockdown VPN** in a responsive UI for phones, foldables, and tablets.

---

## ✨ Highlights

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>🛡️ Next-Gen Censorship Resistance & QUIC</h3>
      <ul>
        <li><b>VLESS REALITY & XHTTP (SplitHTTP):</b> Supports <code>packet-up</code>, <code>stream-up</code>, <code>stream-one</code>, <code>auto</code> modes, <code>extra</code> JSON padding/limits, <code>mlkem768x25519plus</code> PQC encryption, and <code>pqv</code> (<b>ML-DSA-65</b>) verification.</li>
        <li><b>Hysteria 2 (<code>hysteria2://</code>, <code>hy2://</code>):</b> Native Xray QUIC outbound with Brutal congestion control, Salamander UDP obfuscation, UDP port hopping (<code>mport</code>), and <code>pinSHA256</code> pinning.</li>
        <li><b>TUIC v5 (<code>tuic://</code>):</b> Embedded Kwik QUIC bridge with RFC 8446 TLS <code>ExportKeyingMaterial</code> auth, <code>0-RTT</code> handshake, BBR/Cubic/NewReno, <code>native</code>/<code>quic</code> UDP relay, and <code>disable_sni</code>.</li>
      </ul>
    </td>
    <td width="50%" valign="top">
      <h3>🔒 Anti-Spoofing DNS & Seamless Handoff</h3>
      <ul>
        <li><b>DoH & Direct DNS Protection:</b> Remote DoH (Google / Cloudflare) blocks ISP DNS hijacking, paired with an optional <b>Direct DNS</b> toggle (enabled by default) for direct/local routes.</li>
        <li><b>Physical Network Tracking:</b> Real-time <code>ConnectivityManager</code> monitoring of validated Wi-Fi, 5G/LTE, and Ethernet transports with automatic <code>setUnderlyingNetworks()</code> & socket binding.</li>
        <li><b>TUN & Proxy-Only + Always-On:</b> Switch between full-device TUN VPN and local SOCKS5/HTTP <b>Proxy-Only</b> mode, with full Android <i>Always-On</i> and <i>Lockdown</i> VPN support.</li>
      </ul>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>🧭 Smart Routing & Per-App Proxy</h3>
      <ul>
        <li><b>Pre-Bundled Geo Assets:</b> Gradle downloads <code>geoip.dat</code> and <code>geosite.dat</code> at build time, unpacking them on first launch with safe domain/CIDR fallback.</li>
        <li><b>Granular Rules:</b> Route by domain (<code>geosite:</code>, <code>domain:</code>, wildcards), IP/CIDR (<code>geoip:</code>), port, or protocol to <code>PROXY</code>, <code>DIRECT</code>, or <code>BLOCK</code>.</li>
        <li><b>Per-App VPN + Play Store Updates:</b> <i>All Apps</i>, <i>Only Selected</i>, or <i>Bypass Selected</i>, NekoBox clipboard list sync (<code>false</code>/<code>true</code>), plus automatic companion grouping for <b>Google Play Store</b> (<code>DownloadManager</code> + Play Services) so app installs and updates never stall.</li>
      </ul>
    </td>
    <td width="50%" valign="top">
      <h3>🧩 Widgets, QR v40 & REALITY Toolkit</h3>
      <ul>
        <li><b>3 Homescreen Widgets + Quick Settings:</b> <code>4×2</code> Status Dashboard, <code>3×1</code> Compact Bar, <code>1×1</code> Mini Toggle, persistent Notification Tray bar, and QS Tile.</li>
        <li><b>Ultra-Dense Vector QR:</b> Encodes and scans <b>2200+ char</b> ML-KEM-768 VLESS URIs (up to QR Version 40, ECC-L) via CameraX or zero-permission Android Photo Picker.</li>
        <li><b>REALITY Toolkit & 6 Locales:</b> Built-in X25519 + UUID + Short ID generator with ready server <code>config.json</code>, live <code>TLSv1.3 / H2</code> SNI probe, and instant <b>EN / RU / ZH-CN / ZH-TW / FA (RTL) / ES</b> UI.</li>
      </ul>
    </td>
  </tr>
</table>

---

## 🌐 Supported Protocols & Transports

| Protocol | Transports | Security / Auth | Key Capabilities |
| :--- | :--- | :--- | :--- |
| **VLESS** | `TCP`, `XHTTP` (`SplitHTTP`), `WS`, `gRPC`, `HTTPUpgrade`, `KCP` | `REALITY`, `TLS`, `None` | `xtls-rprx-vision`, `mlkem768x25519plus` (0-RTT), `pqv` (`ML-DSA-65`), TLS Hello Fragment, `xhttpSettings.extra` |
| **Hysteria 2** | Native `QUIC` / `UDP` | `TLS`, `pinSHA256` | Brutal congestion control (`upmbps` / `downmbps`), `salamander` obfs, `udphop` (`mport` + `hop-interval`), ALPN |
| **TUIC v5** | `QUIC` (`TuicTunnelBridge`) | `TLS` + RFC 8446 Exporter (`UUID` + `Password`) | `0-RTT` handshake, `bbr` / `cubic` / `new_reno`, `native` / `quic` UDP relay, `disable_sni`, heartbeat |
| **VMess** | `TCP`, `WS`, `gRPC`, `HTTPUpgrade`, `XHTTP`, `KCP` | `TLS`, `None` | AEAD encryption (`auto`, `aes-128-gcm`, `chacha20-poly1305`, `none`) |
| **Trojan** | `TCP`, `WS`, `gRPC`, `XHTTP` | `TLS`, `REALITY` | uTLS fingerprint spoofing, ALPN (`h2`, `http/1.1`), SNI override |
| **Shadowsocks** | `TCP` + `UDP` | `2022-blake3-*`, AEAD ciphers | `2022-blake3-aes-128-gcm`, `2022-blake3-aes-256-gcm`, `2022-blake3-chacha20-poly1305`, `aes-256-gcm`, `chacha20-ietf-poly1305` |
| **WireGuard** | Native `UDP` tunnel | Curve25519 Keypair + Pre-Shared Key | Custom Peer Endpoint, Reserved bytes (`[b0, b1, b2]`), Local CIDR & MTU |
| **SSH Tunnel** | `TCP` (`mwiede/jsch`) | Password, PEM / OpenSSH Private Key (+ passphrase) | Embedded local SOCKS5 TCP + **DNS-over-TCP (`UDP ASSOCIATE`)** bridge & Host Public Key pinning |
| **SOCKS5 / HTTP** | `TCP` | Optional Username / Password | Standard outbound proxy chaining & clean URI distinction from subscription URLs |
| **Custom JSON** | Any Xray-core inbound/outbound | Full Xray `v26.10.31` schema | Raw `config.json` editor with syntax validation & live TUN injection |

> **Subscriptions Supported:** Standard Base64 / plaintext node feeds, `https://.../sub/...`, `sub://`, `v2rayng://install-sub`, `clash://install-config`, and `happ://add/...` links.

---

## 📱 Screenshots

<div align="center">

| Profiles (REALITY · Hy2 · TUIC · SSH) | Routing & Per-App Proxy | REALITY Keygen & SNI Probe |
| :---: | :---: | :---: |
| <img src="docs/screenshots/01_profiles_dark.png" width="250" alt="Profiles Screen" /> | <img src="docs/screenshots/02_routing_rules.png" width="250" alt="Routing Screen" /> | <img src="docs/screenshots/03_tools_reality_keygen.png" width="250" alt="Tools Screen" /> |
| *Live speed telemetry, QUIC/TCP ping & badges* | *GeoSite/GeoIP rules & NekoBox Per-App list sync* | *X25519 keygen, server JSON & TLSv1.3 SNI probe* |

| Multi-Language Settings & Direct DNS | High-Density Vector QR Share |
| :---: | :---: |
| <img src="docs/screenshots/04_settings_ru.png" width="250" alt="Settings Screen" /> | <img src="docs/screenshots/05_qr_share.png" width="250" alt="QR Code Dialog" /> |
| *DoH DNS, Proxy-Only & 6 locales (EN/RU/ZH/FA/ES)* | *Crisp vector QR up to v40 (2200+ char PQC URIs)* |

</div>

---

## 🔄 Network Resilience & Always-On VPN

Mobile networks frequently transition between Wi-Fi and Cellular (LTE/5G). NekoXray implements a dedicated physical network state machine in `XrayVpnService`:

1. **Physical Transport Ranking:** Filters out virtual VPN interfaces (`NET_CAPABILITY_NOT_VPN` + `!TRANSPORT_VPN`) and ranks candidates by `NET_CAPABILITY_VALIDATED`, usable `LinkProperties` IPv4/IPv6 addresses, OS default network status, and transport priority (`Ethernet > Wi-Fi > Cellular > Bluetooth`).
2. **Process & Socket Binding:** Calls `VpnService.setUnderlyingNetworks(...)` and `ConnectivityManager.bindProcessToNetwork(...)` while protecting (`VpnService.protect(socket)`) and binding Kotlin, SSH, and TUIC QUIC sockets directly to the active physical `Network`.
3. **Deadlock-Free TUN & Bridge Handoff:** Serializes network transitions with generation tokens and bounded mutex timeouts (`800ms` on stop, `2500ms` on start/handoff) inside `NonCancellable` blocks with full `ParcelFileDescriptor` tracking — ensuring the system VPN, `TuicTunnelBridge`, and `SshTunnelBridge` reconnect seamlessly without leaking `/dev/net/tun` descriptors.
4. **System Always-On & Lockdown:** Declares `android.net.VpnService.SUPPORTS_ALWAYS_ON = true` and automatically bootstraps the selected profile from Room + DataStore when started by Android OS in the background.

---

## 🏗️ Architecture

```mermaid
flowchart LR
    Apps["📱 Android Apps"] -->|"TUN 172.19.0.1/30\nor Proxy-Only"| Vpn["🛡️ XrayVpnService"]
    Vpn -->|"tunFd"| Xray["⚡ Xray-core v26.10.31\n(VLESS · Hy2 · VMess · Trojan · WG)"]
    Vpn -.->|"TUIC v5"| Tuic["🚀 TuicTunnelBridge\n(Kwik QUIC · SOCKS5 + UDP)"]
    Vpn -.->|"SSH"| Ssh["🔐 SshTunnelBridge\n(JSch · SOCKS5 + DNS-over-TCP)"]
    Tuic --> Xray
    Ssh --> Xray
    Xray -->|"DoH / Direct DNS +\nsetUnderlyingNetworks"| Net["🌐 Physical Network\n(Wi-Fi / 5G / LTE / Ethernet)"]
```

```text
app/src/main/java/com/example/
├── core/
│   ├── crypto/    # X25519 REALITY keygen, shortId & UUID utilities
│   ├── model/     # ProxyProfile (incl. Hy2 & TUIC), ProxyGroup, RoutingRule, XraySettings
│   ├── network/   # TLSv1.3 / H2 SNI probe, QUIC/TCP/TLS latency tester, subscription fetcher
│   ├── parser/    # URI parser & exporter (vless/hy2/tuic/vmess/trojan/ss/wg/ssh/socks/http/json + subs)
│   ├── vpn/       # XrayVpnService, XrayTileService, TuicTunnelBridge, SshTunnelBridge, KotlinTunForwarder
│   ├── widget/    # 4x2 Status, 3x1 Compact, 1x1 Mini AppWidgets & Tray Notification controller
│   └── xray/      # XrayConfigBuilder (Hy2/REALITY/DoH/Direct DNS), XrayAssetHelper & validator
├── data/          # Room database v4 (KSP), DataStore Preferences, NekoRepository
└── ui/            # Jetpack Compose M3 screens, adaptive Navigation, 6-locale i18n, Wired theme
```

| Layer | Technology |
| :--- | :--- |
| **UI & Design System** | Jetpack Compose, Material Design 3, Adaptive `NavigationBar` / `NavigationRail`, Custom Vector Canvas QR |
| **Core Proxy & QUIC** | `AndroidLibXrayLite` (`libv2ray.aar` `v26.10.31`), `tech.kwik:kwik` (`0.12.2`) for TUIC v5, `mwiede/jsch` (`2.28.8`) for SSH |
| **Geo Routing Data** | Auto-downloaded `geoip.dat` & `geosite.dat` (`v2fly`) + `XrayAssetHelper` runtime verification |
| **Persistence** | Room (`2.7.0` with KSP, schema v4) + Jetpack DataStore Preferences + `kotlinx.serialization` |
| **Camera & Media** | CameraX (`1.5.0`) + ZXing (`3.5.3`) + zero-permission `PickVisualMedia` Photo Picker |
| **Quality & Testing** | JUnit 4 + Robolectric (`@GraphicsMode(NATIVE)` screenshot tests + 36 unit/integration tests) |

---

## 🛠️ Building from Source

During `preBuild`, `app/build.gradle.kts` automatically downloads:
- `app/libs/libv2ray.aar` (`2dust/AndroidLibXrayLite` `v26.10.31`) via `:app:downloadLibv2ray`
- `app/src/main/assets/geoip.dat` & `geosite.dat` via `:app:downloadGeoAssets`

```bash
# 1. Build universal debug APK
gradle assembleDebug

# 2. Build per-ABI split APKs (arm64-v8a, armeabi-v7a, x86_64, x86 + universal)
gradle assembleDebug -PenableAbiSplits=true

# 3. Build signed multi-ABI release APKs & Android App Bundle (.aab)
gradle assembleRelease bundleRelease -PenableAbiSplits=true

# 4. Run the full unit & Robolectric test suite (36 tests)
gradle :app:testDebugUnitTest --console=plain
```

### 📦 Multi-ABI Release Artifacts

| Artifact | Target Architecture | Recommended For |
| :--- | :--- | :--- |
| `NekoXray-<tag>-arm64-v8a.apk` | 64-bit ARM (`aarch64`) | **Most modern Android phones & tablets (Recommended)** |
| `NekoXray-<tag>-armeabi-v7a.apk` | 32-bit ARM (`armv7l`) | Legacy 32-bit Android devices & Android TV boxes |
| `NekoXray-<tag>-x86_64.apk` | 64-bit x86 (`amd64`) | ChromeOS, Android Studio emulators, x86_64 tablets |
| `NekoXray-<tag>-x86.apk` | 32-bit x86 (`i686`) | Legacy x86 emulators |
| `NekoXray-<tag>-universal.apk` | All ABIs bundled | Universal fallback installer |
| `NekoXray-<tag>.aab` | Play App Bundle | Google Play / Play Creator Program distribution |

---

## ⚖️ License

**NekoXray** source code is dual-licensed under **`MIT OR LGPL-3.0-or-later`** — you may choose either license at your option (see [`LICENSE`](LICENSE)).

| Component | License | Reference |
| :--- | :--- | :--- |
| **NekoXray Application Source Code** | `MIT OR LGPL-3.0-or-later` | [`LICENSE`](LICENSE) |
| **AndroidLibXrayLite (`libv2ray.aar`)** | `LGPL-3.0` | [`COPYING.LESSER`](COPYING.LESSER) · [`COPYING`](COPYING) · [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) |
| **Xray-core (bundled in `libv2ray.aar`)** | `MPL-2.0` | [XTLS/Xray-core](https://github.com/XTLS/Xray-core) |
| **Kwik QUIC (`tech.kwik:kwik` `0.12.2`)** | `LGPL-3.0` | [ptrd/kwik](https://github.com/ptrd/kwik) |
| **JSch (`com.github.mwiede:jsch` `2.28.8`)** | `BSD-3-Clause` | [mwiede/jsch](https://github.com/mwiede/jsch) |
| **Third-Party Notices & Attributions** | Various Permissive / Weak Copyleft | [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md) |

> **LGPL-3.0 Section 4 Compliance & Clean-Room Notice:** Prebuilt APK/AAB releases dynamically link `AndroidLibXrayLite` (`libv2ray.aar`) and `tech.kwik:kwik`. Complete Gradle build scripts and source code are provided in this repository so any user can replace `app/libs/libv2ray.aar` with a modified build and reassemble the application (`gradle assembleDebug`). NekoXray implements clean-room compatibility with NekoBox URI/clipboard formats and contains no GPL-3.0 code.
