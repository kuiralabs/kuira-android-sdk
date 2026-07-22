Thanks for the detailed report — the contract and call sites made this easy to pin down.

## What's happening

`receiveUnshielded` and `sendUnshielded` move NIGHT **into** and **out of** the contract. For that to be valid, the wallet has to supply the value (deposit) or receive it (withdrawal) through an **unshielded offer** attached to the transaction. A plain `call(circuitName, args…)` attaches no such offer, so the contract's unshielded balance doesn't add up and the node rejects the transaction — which surfaces as the generic "invalid transaction" you're seeing. The circuit compiles and proves fine; it's the missing offer that fails at submit.

This is opt-in today: the SDK exposes helpers to build that offer, and you pass the result into `call(...)`.

## Workaround (works on `0.1.0-alpha05`, no upgrade needed)

**Deposit** — build a funding offer that covers `amount`:

```kotlin
suspend fun deposit(
    context: Context,
    sdk: MidnightSdk,
    address: String,
    amount: Long,
    onProgress: (suspend (ContractCallStage) -> Unit)? = null,
) {
    val handle = buildHandle(context, sdk, address = address, forWrite = true)

    // Cover the value the circuit receives via receiveUnshielded.
    val funding = sdk.buildUnshieldedFundingJson(amount.toBigInteger())

    handle.call(
        CIRCUIT_DEPOSIT,
        amount,
        unshieldedFundingJson = funding,
        onProgress = onProgress,
    )
}
```

**Withdraw** — build a withdrawal offer to the recipient (no signing; the contract provides the value):

```kotlin
suspend fun withdraw(
    context: Context,
    sdk: MidnightSdk,
    address: String,
    amount: Long,
    recipient: ByteArray, // 32-byte UserAddress hash
    onProgress: (suspend (ContractCallStage) -> Unit)? = null,
) {
    val handle = buildHandle(context, sdk, address = address, forWrite = true)

    // The recipient here MUST match the recipient the circuit sends to.
    val withdrawal = sdk.buildUnshieldedWithdrawalJson(recipient, amount.toBigInteger())

    handle.call(
        CIRCUIT_WITHDRAW,
        amount,
        recipient,
        unshieldedWithdrawalJson = withdrawal,
        onProgress = onProgress,
    )
}
```

### Notes

- **`buildUnshieldedFundingJson(amount, tokenType?)`** selects the wallet's UTXOs to cover `amount` (with change back to the wallet) and attaches + signs the offer for you. It throws `InsufficientFundsException` if the wallet can't cover `amount`, so make sure the wallet holds enough NIGHT (plus dust for fees).
- **`buildUnshieldedWithdrawalJson(recipient, amount, tokenType?)`** attaches an output to `recipient`. That address must be the same recipient your circuit sends to — otherwise the node rejects it.
- `tokenType` defaults to native NIGHT, which matches your `default<Bytes<32>>` color, so you don't need to pass it here.
- `recipient` must be the 32-byte `UserAddress` hash (the Bech32m-decoded bytes), which is what you're already passing as the circuit argument.

## Improvement coming

Requiring the caller to build and pass the offer for a circuit that already declares the transfer isn't great DX, and the failure mode ("invalid transaction" with no hint) makes it hard to discover. A future release will improve this — at minimum by failing early with an actionable message when a circuit moves unshielded value and no offer was supplied, and ideally by funding the common deposit case automatically. Tracking that here.

## Verified

We reproduced your contract (a `payment` contract with `deposit`/`receiveUnshielded` and
`withdraw`/`sendUnshielded` on NIGHT) and confirmed this end-to-end on a device against a localnet:

- a plain `call("deposit", amount)` fails with exactly `Transaction rejected: Invalid Transaction`;
- `call("deposit", amount, unshieldedFundingJson = sdk.buildUnshieldedFundingJson(amount))` succeeds,
  and the deposited amount lands in the contract ledger (`totalDeposited` == amount).

In the meantime the workaround above should unblock you. Let me know if the deposit or withdrawal still fails after wiring in the offers.
