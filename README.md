# KasCrypt

**KasCrypt** is a post-quantum secure Android local vault, cryptographic photo safe, and native Kaspa wallet. Engineered with a **strict zero-cloud, zero-knowledge architecture**, KasCrypt protects your credentials, encrypted files, and digital assets against both classical and quantum computing threats.

---

## 🛡️ What is KasCrypt?

KasCrypt combines cutting-edge post-quantum cryptography (NIST FIPS 203 ML-KEM & FIPS 204 ML-DSA) with the high-throughput Kaspa BlockDAG network to deliver the most resilient personal data storage and wallet solution on mobile.

Whether storing confidential login credentials, secret notes, private photos, or transacting on Kaspa, your data remains completely encrypted on your device and under your sovereign control.

---

## 🚀 Key Features

### 1. 🔐 Zero-Knowledge Post-Quantum Vault
- **XChaCha20-Poly1305 AEAD Encryption:** All vault items (passwords, notes, keys, bank info, identities, secret tokens) are encrypted with 256-bit XChaCha20 and an extended 192-bit nonce to eliminate nonce-reuse vulnerabilities.
- **Argon2id Key Derivation:** Master passwords are transformed into 256-bit encryption keys using memory-hard Argon2id (16 MB memory cost, 2 iterations, 2 lanes) to thwart brute-force, ASIC, GPU, and quantum-assisted dictionary attacks.
- **NIST ML-DSA Post-Quantum Signatures:** Vault records and backup payloads are signed with lattice-based ML-DSA (Dilithium) to ensure tamper-proof integrity and cryptographic non-repudiation.
- **BLAKE2b Merkle Tree Verification:** Uses cryptographic tree hashes to audit data consistency and detect unauthorized modifications.

### 2. 🖼️ Encrypted Photo & Document Safe
- **Direct Stream Ingestion:** Images and files picked via Android's Photo Picker are encrypted in memory on-the-fly before ever touching disk storage.
- **Zero-Unencrypted Caching:** Raw unencrypted thumbnails or previews are never cached in temp folders or system media stores.
- **Cryptographic File Shredding:** When an asset is deleted, flash storage sectors are overwritten with pseudo-random noise prior to filesystem deletion.
- **Automatic Memory Zeroization:** Decrypted byte arrays are scrubbed immediately following on-screen bitmap rendering.

### 3. ⚡ Native Kaspa Wallet (BIP-39 / BIP-44)
- **Standard 2,048-Word Dictionary:** Generate or import standard 12-word or 24-word BIP-39 mnemonic phrases.
- **Optional 25th-Word Passphrase:** Full support for BIP-39 security passphrases during key derivation.
- **Deterministic Derivation:** Generates standard Kaspa addresses (`m/44'/111111'/0'/0/0`) with secp256k1 x-only Schnorr public keys.
- **Live Network Synchronization:** Real-time balance queries, UTXO tracking, and transaction broadcasting over the Kaspa BlockDAG REST API.
- **On-Chain KFS (Kaspa File System) Archiving:** Publish and restore encrypted vault backups directly to and from the Kaspa BlockDAG.

### 4. 📦 Portable & Cross-Device Encrypted Backups
- **Self-Contained Encrypted Archives:** Export single `.json` backup files containing all vault entries, encrypted photo assets, and Kaspa wallet credentials.
- **Direct Migration on New Devices:** One-tap **"Restore From Backup File"** option available directly on initial app setup and lock screens for seamless device upgrades.
- **Authenticated Signatures:** Backups are authenticated with XChaCha20-Poly1305 AEAD and ML-DSA signatures.

### 5. 🔒 Hardened Mobile Security
- **Anti-Screen Capture (`FLAG_SECURE`):** Prevents screenshots, video recordings, and task switcher preview leaks.
- **Hardware-Backed Biometrics:** Class 3 Strong Biometric authentication leveraging Android KeyStore and Secure Element / StrongBox.
- **Lifecycle Auto-Lock:** Instantly purges memory keys and locks the vault when the app is backgrounded or left inactive.
- **Sensitive Clipboard Scrubbing:** Copies to clipboard are tagged as sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`) on Android 13+ with automated clipboard clearing.
- **System Backup Disablement:** SQLite databases and preferences are excluded from cloud backups and extraction scripts (`allowBackup="false"`).

---

## 🛠️ Tech Stack & Architecture

- **Platform:** Android (Min SDK 26, Target SDK 35)
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose with Material Design 3
- **Local Persistence:** Room Database with KSP
- **Cryptography:**
  - BouncyCastle & BouncyCastle PQC (`bcpkix-jdk18on`, `bcprov-jdk18on`, `bcpqc-jdk18on`)
  - Google Tink (XChaCha20-Poly1305)
  - BiometricX (`androidx.biometric:biometric`)
  - Argon2 Kotlin KDF
- **Networking:** Retrofit & OkHttp (Kaspa BlockDAG REST API)

---

## 🏗️ Continuous Integration & Automated Builds

KasCrypt includes an automated GitHub Actions pipeline (`.github/workflows/build-apk.yml`) that builds, verifies, and publishes release APKs on every push to `main` or version tag:

1. **`KasCrypt-release-signed.apk`**: Production release build, signed and ready for installation.
2. **`KasCrypt-release-unsigned.apk`**: Clean release build ready for custom keystores or F-Droid distribution.
3. **`KasCrypt-debug.apk`**: Development build with debug symbols.

Every build is published to the **GitHub Releases `latest` tag** and stored in GitHub Actions workflow artifacts for 90 days.


