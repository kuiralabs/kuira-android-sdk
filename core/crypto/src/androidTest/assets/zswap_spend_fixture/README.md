# zswap spend fixture

61 localnet zswap ledger events in which the wallet derived from master seed `07` x32 owns one
5 NIGHT shielded coin, plus that wallet's zswap seed (BIP-32 `m/44'/2400'/0'/3/0`, which is what
`replayEvents` expects — the master seed decrypts nothing).

Here so a zswap SPEND can be proved locally without a chain: coins only enter a `ZswapLocalState`
via `replayEvents`, there is no offline way to mint one, and the other fixture
(`zswap_fixture/`) is the global PREPROD stream under a synthetic seed, so it owns nothing.

Captured once against the ledger-8 localnet. Recreate with:

    mn wallet generate spendfix --seed 0707...07 --network undeployed
    mn airdrop 5 --shielded --wallet <its undeployed shielded address>

then subscribe to `zswapLedgerEvents` from genesis and keep every `raw` in id order. The events
are the global stream, so a contiguous prefix from id 0 is genesis-anchored.
