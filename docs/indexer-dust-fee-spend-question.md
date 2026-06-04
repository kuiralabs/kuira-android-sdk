# Indexer question: does `dustLedgerEvents` include dust *fee* spends in contract txs?

**Status:** open — blocks confirming whether the wallet-side durable spent-dust
tracking fully recovers (lag) or whether the indexer itself needs a fix (omission).
Network: PREPROD.

## Context

We build a wallet's dust state by replaying the global `dustLedgerEvents` stream
(subscribe by `fromId`, replay into a local `DustLocalState`, filter by the dust
secret key). This works for dust generation and standalone spends. But the wallet's
dust state never reflects its **own** dust *fee* spends, causing the node to reject
the next spend with `Custom error: 115` ("UTXO already spent").

## What we observe (PREPROD)

- A wallet's backing-night dust UTXO sits at `seq=2`, `mt_index=720413`,
  nullifier `73636bf3…e8437`.
- We submit contract transactions (a commit-reveal game) that pay their fee in
  dust. After a successful, finalized move (the `contractAction` state advances —
  e.g. `p1Committed=true`), the node treats that dust UTXO as spent: re-submitting
  a spend of it returns **error 115**.
- A **full genesis replay** of `dustLedgerEvents` (940,929 events; indexer at
  block 1,075,214 — well past the move's blocks) shows **no `DustSpendProcessed`
  for that nullifier**. The replay sees `seq=0` spent, `seq=1` spent, `seq=2`
  received — then nothing. So per the dust event stream, `seq=2` is still unspent,
  while the node says it's spent.
- `seq=0` and `seq=1`'s spends **are** in the stream, so it isn't a blanket omission.

## Questions

1. Is `dustLedgerEvents` expected to emit a `DustSpendProcessed` for a dust spend
   that occurs as the **fee** of a *contract* transaction (a dust spend in the
   fee/guaranteed segment), or only for standalone dust transactions?
2. If it should emit them, is there a known **lag** for dust events on recent
   blocks relative to `contractAction` / `block.height` (which appear current)?
   Expected catch-up window?
3. Recommended way for a wallet to learn its own fee-spend's `DustSpendProcessed`
   (and the regenerated change UTXO) without replaying the entire global stream —
   e.g. a per-address or per-nullifier query?

## Repro data (PREPROD)

- dust nullifier: `73636bf3fed613ba36e611352164d0627363f7f0dfca8cff92117d2ce6e58e8437`
- contract: `480e66ba8dd0a49b9a7d738c67723933ac611811bc2d6844c20f2da5e4212dd1`
- indexer: `https://indexer.preprod.midnight.network/api/v4/graphql`
  (note: `networkState` is gone in v4; `block { height }` works)
- node: `wss://rpc.preprod.midnight.network`

Happy to share the wallet address / a failing tx hex.
