# Kascrypt

Kascrypt is a post-quantum secure Android local vault and cryptographic wallet engineered with modern cryptographic standards, zero-cloud architecture, and hardware-backed mobile protection.

---

## Cryptographic Architecture & Features

### 1. Zero-Knowledge Encrypted Vault
- **XChaCha20-Poly1305 Symmetric Cipher:** All vault secrets, notes, passwords, and custom key-value pairs are encrypted using 256-bit XChaCha20 with an extended 192-bit nonce to eliminate nonce-reuse risks. Poly1305 MAC tags guarantee cryptographic authenticity and anti-tampering.
- **Argon2id Key Derivation:** Master passwords are transformed into 256-bit encryption keys using memory-hard Argon2id (16 MB memory cost, 2 iterations, 2 lanes) to withstand brute-force, ASIC, GPU, and quantum-assisted search attacks.
- **Local SQLite Isolation:** Encrypted records reside in an application-sandboxed SQLite database (`vault-db`) with system backups disabled (`android:allowBackup="false"`).

### 2. Encrypted Image & Document Upload
- **Zero-Unencrypted Caching:** Image files picked via the system Photo Picker are encrypted on-the-fly directly in memory using authenticated XChaCha20-Poly1305.
- **Bounded Ingestion:** Guarded with a 25MB safety stream limit to eliminate out-of-memory crash vectors on massive RAW files.
- **Path Traversal Shield:** Filenames are strictly canonicalized and isolated to prevent directory traversal attacks.
- **Cryptographic File Shredding:** When an image entry is deleted, flash storage sectors are overwritten with cryptographic pseudo-random noise prior to filesystem deletion.
- **Memory Zeroization:** Decrypted plaintext byte buffers are overwritten with zeros immediately following bitmap rendering.

### 3. Native BIP-39 Kaspa Wallet Engine
- **Standard 2,048-Word Dictionary:** Mnemonic seed generation strictly complies with the official 2,048-word BIP-39 standard using `SecureRandom` entropy.
- **Deterministic Derivation:** Derives BIP-44 standard Kaspa addresses (`m/44'/111111'/0'/0/0`) with secp256k1 elliptic curve x-only Schnorr public keys.
- **Full BIP-39 Passphrase Support:** Supports optional 25th-word secret passphrases during wallet derivation and import.
- **Checksum Validation on Import:** Imported seeds are validated against the 2,048-word dictionary, word count constraints (12 or 24 words), and SHA-256 entropy checksum bits before key derivation.
- **Encrypted Seed at Rest & Memory Zeroization:** Mnemonic phrases and private keys are encrypted with XChaCha20-Poly1305 using the Argon2id-derived root key. In-flight byte buffers are zeroed out after derivation.

### 4. Post-Quantum Cryptographic Readiness
- **Symmetric Security (Grover Resistance):** With a 256-bit key length, XChaCha20-Poly1305 provides 128 bits of security against Grover's quantum search algorithm, well above cryptographic security thresholds.
- **Argon2id Memory Hardness:** Quantum speedups cannot bypass physical memory latency and memory-bound operations.
- **Post-Quantum Signatures:** Incorporates BouncyCastle's Post-Quantum Provider (`BouncyCastlePQCProvider`) targeting NIST FIPS 204 ML-DSA (Dilithium lattice-based signatures) with Ed25519 fallback. Private signing keys are encrypted at rest using authenticated symmetric keys.

### 5. Mobile Security & Hardening
- **Anti-Screen Capture (`FLAG_SECURE`):** Enforces `FLAG_SECURE` to block screenshot capture, screen recording, and exposure in the Android Task Switcher.
- **Lifecycle Auto-Lock:** Vault keys are scrubbed and state is locked immediately when the app enters the background (`onStop()`) or upon extended inactivity.
- **Sensitive Clipboard Privacy:** Sensitive copies (passwords, private keys, seed phrases) are marked with `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+ to prevent system preview banners and keyboard logging.
- **Class 3 Hardware Biometrics:** Biometric unlocks enforce `BIOMETRIC_STRONG` to require hardware-backed biometric sensors and block software/2D face unlocks.
- **Backup & Extraction Prevention:** Excludes databases and shared preferences from cloud backups and device migration scripts (`data_extraction_rules.xml`, `backup_rules.xml`).

---

## Tech Stack

- **Platform:** Android (Min SDK 26, Target SDK 35)
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose with Material Design 3
- **Local Persistence:** Room Database with KSP
- **Cryptography:**
  - BouncyCastle & BouncyCastle PQC (`bcpkix-jdk18on`, `bcprov-jdk18on`, `bcpqc-jdk18on`)
  - Google Tink (XChaCha20-Poly1305)
  - BiometricX (`androidx.biometric:biometric`)
- **Networking:** Retrofit & OkHttp (Kaspa BlockDAG REST API)

---

## Continuous Integration & Release Builds

The repository includes an automated GitHub Actions workflow (`.github/workflows/build-apk.yml`) that builds and produces three distinct APK artifacts on every push or manual dispatch:
1. **`app-debug-apk`**: Development debug build (`assembleDebug`).
2. **`app-release-signed-apk`**: Production release build (`assembleRelease`), signed with either repository secrets (`RELEASE_KEYSTORE_BASE64`) or an automated 10,000-day CI release keystore.
3. **`app-release-unsigned-apk`**: Clean unsigned release build with all cryptographic signature envelopes (`META-INF`) stripped, ready for custom keystores, third-party distribution, or F-Droid/re-signing pipelines.
- Artifact retention is set to the maximum allowed 90 days.

