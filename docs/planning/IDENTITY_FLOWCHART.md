# Kuira Identity — Implementation Flowchart

Ground-truth map of the `core/identity` module as currently wired (not aspirational). Source cited inline as `file:line`. Stubs/future work are listed at the bottom and are deliberately kept out of the flow diagrams.

The whole system hangs off one root secret: the **passkey**. Every long-lived secret is re-derived on demand from a passkey **PRF** output using three domain-separated salts, so nothing sensitive has to be stored or synced in the clear.

| Salt | Constant | Derives |
|------|----------|---------|
| `SIGIL_SALT` | `SHA-256("kuira:sigil:v1")` (`SeedDeriver.kt:51`) | Ed25519 sigil DID |
| `SEED_SALT` | `SHA-256("kuira:seed:v1")` (`SeedDeriver.kt:39`) | BIP-39 wallet seed |
| `BACKUP_SALT` | `SHA-256("kuira:backup:v1")` (`AppStateBackup.kt:163`) | app-state backup AES key |

---

## 1. Architecture / component map

```mermaid
flowchart TD
    subgraph ROOT["Root credential"]
        PK["Passkey (P-256, WebAuthn)\nPasskeyManager.kt:28\nsynced via Google Password Manager"]
    end

    subgraph SIGIL["sigil/"]
        SIP["SigilIdentityProvider\n(interface) :35"]
        ED["Ed25519PrfSigilProvider\nPRF -> Ed25519 -> did:key:z6Mk :39"]
        SSS["SigilStateStore\n(did, credentialId, pubKeyHex)\nSharedPreferences :44"]
    end

    subgraph KEYS["key derivation (backup/)"]
        SD["SeedDeriver\nPRF -> entropy -> BIP-39 seed :36"]
        PKD["PrfKeyDeriver\nHKDF(PRF) -> AES key :18"]
        SDK["SeedDerivedKeyDeriver\nHKDF(seed) -> AES keys :21"]
    end

    subgraph WALLET["accesskey/"]
        AKM["AccessKeyManager\nHD role 5 (IDENTITY)\nm/44'/2400'/acct'/5/idx\nsecp256k1 :22"]
    end

    subgraph AUTH["auth/"]
        KA["KeyAuthorization\n99-byte delegation payload :20"]
        AR["AuthorizationRecord :12"]
        AS["AuthorizationStore\nAES-256-GCM at rest :26"]
    end

    subgraph DID["did/"]
        DKG["DidKeyGenerator\ndid:key encode/parse :26"]
    end

    subgraph BK["backup + recovery (backup/)"]
        ASB["AppStateBackup :44"]
        ASE["AppStateBackupEncryptor v2 :27"]
        DBE["DustBackupEncryptor v1 :21"]
        DCB["DustCloudBundle (KDB1) :43"]
        BST["BackupStorage (iface) :10"]
        BS["BlockStoreBackupStorage\nGoogle Block Store (4KB) :23"]
        DBS["DriveBackupStorage\nDrive appDataFolder :29"]
        DAM["DriveAuthManager (OAuth) :47"]
    end

    PK --> SIP --> ED --> DKG
    ED --> SSS
    PK -->|PRF SEED_SALT| SD --> AKM
    AKM --> KA --> AR --> AS
    SIP --> KA
    PK -->|PRF BACKUP_SALT| ASB --> PKD --> ASE --> BST
    SD --> SDK --> DBE --> DCB --> DBS
    BST --> BS
    BST --> DBS
    DBS --- DAM
```

---

## 2. Enrollment — "Forge Sigil"

Three biometric taps in the full path: create passkey, derive sigil, sign the access-key delegation.

```mermaid
flowchart TD
    A["User taps Forge Sigil"] --> B["PasskeyManager.createPasskey()\n:47 (biometric #1)"]
    B --> C["AttestationParser.extractPublicKey()\n:29 -> P-256 pubkey"]
    C --> D["PasskeyManager.authenticateWithPrf(SIGIL_SALT)\n:243 (biometric #2) -> 32B PRF out"]
    D --> E["Ed25519PrfSigilProvider.deriveFromPrfOutput()\n:53\nEd25519(seed=PRF), wipe priv key :86"]
    E --> F["DidKeyGenerator.fromEd25519()\n:151 -> did:key:z6Mk..."]
    F --> G["SigilStateStore.persistSigil()\n:162 (.commit, durable)"]

    G --> H["SeedDeriver.derivePrfMaterial()\n:110 -> entropy 32B + BIP-39 seed 64B"]
    H --> I["AccessKeyManager.deriveDefaultAccessKey()\n:34 -> secp256k1 @ m/44'/2400'/0'/5/0"]
    I --> J["KeyAuthorization.buildPayload()\n:37\nmagic13+rootP256_33+accessSecp_33+scope4+ts8+exp8"]
    J --> K["KeyAuthorization.hashPayload() SHA-256\n:73 -> 32B challenge"]
    K --> L["PasskeyManager.authenticate(challenge)\n:124 (biometric #3)\nsignature commits to payload"]
    L --> M["AuthorizationStore.save()\n:44 AES-256-GCM -> kuira_authorizations.bin"]
    M --> N["(optional) AppStateBackup.backup()\n:61 -> cloud (see diagram 4)"]
    N --> Z["Identity ready"]
```

---

## 3. Unlock + sign a transaction

```mermaid
flowchart TD
    A["User: connect dApp / send tx"] --> B{"SigilStateStore.hasSigil()?\n:120"}
    B -->|no| BX["throw SigilRequiredException :19\nUI offers Forge Sigil"]
    B -->|yes| C["SeedDeriver.deriveBip39Seed(SEED_SALT)\n:89 (biometric, cached for session)"]
    C --> D["AccessKeyManager.deriveDefaultAccessKey()\n:34 -> secp256k1"]
    D --> E{"AuthorizationStore.findActiveByDid()\n:61"}
    E -->|found + not expired/revoked| H["use existing record"]
    E -->|missing / expired / revoked| F["re-build payload + sign\n(KeyAuthorization :37, Passkey.authenticate :124)"]
    F --> G["AuthorizationStore.save() :44"]
    G --> H
    H --> I["verify P-256 sig over authData || SHA-256(clientDataJSON)\n(offline, no server)"]
    I --> J["Midnight SDK signs tx with access key (secp256k1)"]
    J --> K["submit to network"]
```

---

## 4. Backup (cloud-synced state)

```mermaid
flowchart TD
    A["User: Backup to cloud"] --> B["AppStateBackup.backup()\n:61"]
    B --> C["Passkey.authenticateWithPrf(BACKUP_SALT)\n:66 -> 32B PRF out"]
    C --> D["PrfKeyDeriver.deriveKey() HKDF-SHA256\n:30 -> AES-256 key"]
    D --> E["AppStateBackupEncryptor.encrypt() v2\n:55  [0x02|IV12|GCM] ~525B"]
    E --> F["BackupStorage.store()"]
    F --> G["BlockStoreBackupStorage :27\nsetShouldBackupToCloud=true (4KB cap)"]

    subgraph DUST["dust state (~500KB, separate path)"]
        H["DustCloudBundle.encode() KDB1 :49"] --> I["SeedDerivedKeyDeriver.deriveDustBackupKey()\n:46 HKDF(seed)"]
        I --> J["DustBackupEncryptor.encrypt() v1 :32\n[0x01|IV12|GCM] no size limit"]
        J --> K["DriveBackupStorage.store() :34\nDrive appDataFolder (OAuth via DriveAuthManager)"]
    end
```

Note: secrets are wiped after use (PRF + plaintext + derived key) e.g. `AppStateBackup.kt:83`, `AppStateBackupEncryptor.kt:88`. No seed is ever uploaded — it is re-derived on restore.

---

## 5. Recovery (new device, zero words)

```mermaid
flowchart TD
    A["New device: sign in to Google"] --> B["Passkey auto-syncs (Google Password Manager)"]
    B --> C["App launches -> check SigilStateStore"]
    C --> D["Passkey.authenticateWithPrf(SIGIL_SALT, preferImmediatelyAvailable)\n:243 (biometric)"]
    D -->|NoPasskeyCredentialException :470| DX["offer Forge new sigil"]
    D --> E["Ed25519PrfSigilProvider.deriveFromPrfOutput()\n:53 -> same did:key:z6Mk..."]
    E --> F["SigilStateStore.persistSigil() :162"]
    F --> G["SeedDeriver.deriveBip39Seed(SEED_SALT)\n:89 -> identical 64B seed"]

    G --> H["AppStateBackup.restore() :97"]
    H --> H1["BlockStore.retrieve() :66"]
    H1 --> H2["PRF(BACKUP_SALT) -> PrfKeyDeriver -> AES :109"]
    H2 --> H3{"AppStateBackupEncryptor.decrypt() :99"}
    H3 -->|v1 legacy blob| H3X["BackupException -> treat as no backup :104"]
    H3 -->|AEAD fail| H3Y["BackupDecryptionException :123"]
    H3 -->|ok| H4["appMetadata restored"]

    G --> I["Dust restore"]
    I --> I1["DriveBackupStorage.retrieve() :41"]
    I1 --> I2["SeedDerivedKeyDeriver.deriveDustBackupKey() :46"]
    I2 --> I3["DustBackupEncryptor.decrypt() :50 -> DustCloudBundle.decode() :68"]
    I3 --> I4["dust state restored"]

    H4 --> Z["Wallet fully recovered"]
    I4 --> Z
```

---

## Key branch / error paths (cross-cutting)

- **No sigil** -> `SigilRequiredException` (`SigilRequiredException.kt:19`), UI offers Forge.
- **No passkey on device** -> `NoPasskeyCredentialException` (`PasskeyManager.kt:470`), offer Forge new.
- **Legacy P-256 DID** (`did:key:zDn...`) -> auto-migrated to Ed25519 on load (`SigilStateStore.kt:97`).
- **Legacy v1 backup blob** -> rejected as "no backup" (`AppStateBackup.kt:104`).
- **Wrong passkey / corrupted blob** -> AEAD `BackupDecryptionException` (`AppStateBackupEncryptor.kt:123`).
- **PRF unsupported** -> `BackupException("PRF not available")` (rare on Android; Google PWM supports PRF).
- **Drive not consented** -> `DriveConsentRequiredException` (`DriveAuthManager.kt:86`) / `NeedsConsent` outcome triggers consent UI.

## Not in the diagrams (stub / future, per IDENTITY_INVESTIGATION.md)

- CredentialProvider Mode 2 (Kuira as system passkey provider).
- Per-dApp access keys (`AccessKeyManager.deriveAccessKeyAt(index>0)` exists but routing/policies not wired).
- MCP agent pairing, "My Sigil" dashboard screen.
- Direct passkey-signed Midnight txs (if protocol adopts P-256), DRek social recovery, passkey-derived secp256k1 (would remove access-key cloud backup).
