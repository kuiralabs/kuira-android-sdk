# Security & Cryptography Guidelines
**Version:** 1.0.0
**Last Updated:** January 10, 2026

**Purpose:** Security best practices for handling cryptographic operations and sensitive data.

**Related Guidelines:**
- For Midnight-specific security → See `MIDNIGHT_GUIDELINES.md`
- For testing security features → See `TESTING_GUIDELINES.md`

---

## NEVER Log Sensitive Data

```kotlin
// ❌ BAD: Logs private key!
log.debug("Private key: $privateKey")

// ✅ GOOD: Log only non-sensitive info
log.debug("Derived address for account 0")

// ✅ GOOD: Redact in toString()
data class PrivateKey(val bytes: ByteArray) {
    override fun toString() = "PrivateKey(***REDACTED***)"
}
```

**Never log:**
- Private keys
- Seed phrases / mnemonics
- Signing keys
- Passwords / PINs
- Proof witness data
- Nullifier secrets

---

## Memory Wiping

```kotlin
// ✅ GOOD: Clear sensitive data after use
val privateKey = derivePrivateKey(seed)
try {
    val signature = sign(data, privateKey)
    return signature
} finally {
    privateKey.clear()  // Wipe from memory
}

// Implement clear() for sensitive types
data class PrivateKey(private val bytes: ByteArray) {
    fun clear() {
        bytes.fill(0)  // Overwrite with zeros
    }
}
```

**Always wipe:**
- Private keys immediately after use
- HD wallet instances (`hdWallet.clear()`)
- ZswapSecretKeys (`zswapKeys.clear()`)
- Seed bytes after key derivation
- Temporary key material

---

## Input Validation

```kotlin
// ✅ GOOD: Validate before processing
fun sendTransaction(recipient: String, amount: BigInteger) {
    require(recipient.isNotBlank()) { "Recipient cannot be blank" }
    require(amount > BigInteger.ZERO) { "Amount must be positive" }
    require(isValidAddress(recipient)) { "Invalid recipient address" }

    // Now safe to proceed
}

// ✅ GOOD: Validate external input
fun deserializeTransaction(json: String): Result<Transaction> {
    return runCatching {
        val tx = Json.decodeFromString<Transaction>(json)
        require(tx.intents.isNotEmpty()) { "Transaction must have intents" }
        tx
    }
}
```

**Validate:**
- All user input
- All external data (API responses, deep link parameters)
- Transaction parameters (amounts, addresses)
- Deserialized objects
- File paths and URIs

---

## Use Android Security Features

```kotlin
// ✅ GOOD: Store keys in Android Keystore
val keyStore = KeyStore.getInstance("AndroidKeyStore")
val keyGenerator = KeyGenerator.getInstance(
    KeyProperties.KEY_ALGORITHM_AES,
    "AndroidKeyStore"
)

// ✅ GOOD: Encrypt sensitive data at rest
val encryptedPrefs = EncryptedSharedPreferences.create(
    context,
    "wallet_prefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

**Use:**
- **Android Keystore** for private key storage
- **EncryptedSharedPreferences** for seed phrases / sensitive settings
- **BiometricPrompt** for user authentication
- **Hardware-backed keys** when available

### Auth-required Keystore keys

When generating a Keystore key that requires user authentication
(biometric / PIN), do **not** inline the validity duration or the
accepted-authenticator bits. Both live in `core.auth.AuthPolicy`:

```kotlin
// ✅ GOOD: read both from AuthPolicy — single source of truth.
KeyGenParameterSpec.Builder(alias, purposes).apply {
    // …
    setUserAuthenticationParameters(
        AuthPolicy.VALIDITY_DURATION_SECONDS,
        AuthPolicy.ALLOWED_AUTHENTICATORS,
    )
    setUnlockedDeviceRequired(true)         // required
    setInvalidatedByBiometricEnrollment(false)  // see AuthPolicy doc
}
```

Why centralized: the validity window (currently 30s) is a calibrated
UX vs. snatched-unlocked-device trade-off (see
`docs/security/SECURITY_NOTES.md` 2026-05-18 entry). Diverging from
`AuthPolicy` requires an explicit security review note in the
SECURITY_NOTES log explaining the threat model the divergence
addresses — same bar as adding a new VULN entry.

For *high-value operations* (sending funds, claiming a payout, etc.),
layer a fresh `BiometricPrompt` call **on top of** the auth-validity
window. The window is for the warm "user just authenticated" intent;
high-value ops should require a deliberate re-auth regardless of
window state.

---

## Midnight-Specific Security Patterns

### Memory Management

```kotlin
// ✅ ALWAYS wipe keys after use
val hdWallet = HDWallet.fromSeed(seed)
try {
    val keys = hdWallet.selectAccount(0).deriveKeysAt(0)
    // use keys
} finally {
    hdWallet.clear()  // CRITICAL
}

// ✅ ALWAYS wipe ZswapSecretKeys
val zswapKeys = ZswapSecretKeys.fromSeed(seed)
try {
    val tx = createShieldedTransaction(zswapKeys)
    return tx
} finally {
    zswapKeys.clear()  // CRITICAL
}
```

**Why this matters:**
- Midnight uses cryptographic secrets that must not remain in memory
- HDWallet and ZswapSecretKeys contain private key material
- Failure to clear can lead to key leakage via memory dumps
- Android garbage collection is NOT immediate - explicit clearing required

### Transaction Security

```kotlin
// ✅ ALWAYS verify addresses before sending
fun sendTransaction(recipient: String, amount: BigInteger) {
    require(isValidMidnightAddress(recipient)) {
        "Invalid Midnight address format"
    }
    require(hasCorrectChecksum(recipient)) {
        "Address checksum validation failed"
    }
    // proceed with transaction
}
```

---

## Security Checklist

Before shipping any code:

- [ ] No sensitive data in logs
- [ ] All keys wiped after use (`clear()` called in `finally` blocks)
- [ ] Input validation on all external data
- [ ] Android Keystore used for key storage
- [ ] EncryptedSharedPreferences for sensitive settings
- [ ] BiometricPrompt for sensitive operations
- [ ] Address validation before transactions
- [ ] No hardcoded keys/seeds/passwords
- [ ] Proguard rules don't expose crypto internals
- [ ] No debug builds with relaxed security

---

## Threat Model

**What we protect against:**
- Memory dumps exposing private keys
- Logcat leaking sensitive data
- Malicious apps reading shared preferences
- Screenshot/screen recording of seed phrases
- Clipboard hijacking (address replacement)
- Man-in-the-middle attacks (use HTTPS for proof server)

**What we DON'T protect against:**
- Rooted devices with full memory access
- Compromised OS / malware with root access
- Physical device theft (user should use device encryption)
- Social engineering / phishing

**User responsibility:**
- Secure device lock (PIN/biometric)
- Keep OS updated
- Don't install from untrusted sources
- Backup seed phrase securely
