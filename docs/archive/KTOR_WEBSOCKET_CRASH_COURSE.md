# Ktor WebSocket Crash Course

**Understanding GraphQLWebSocketClient.kt - Line by Line**

---

## Part 1: What is Ktor?

**Ktor = Kotlin asynchronous HTTP client/server framework**

Think of it as Kotlin's version of:
- **Retrofit** (for HTTP REST APIs)
- **OkHttp** (for low-level networking)
- **Socket.io** (for WebSockets)

But built specifically for Kotlin coroutines.

### Ktor Architecture

```
┌─────────────────────────────────────┐
│         Your Application            │
│   (GraphQLWebSocketClient)          │
└──────────────┬──────────────────────┘
               │ Uses
               ▼
┌─────────────────────────────────────┐
│         Ktor Client API             │
│  • HttpClient                       │
│  • webSocketSession()               │
│  • WebSockets plugin                │
└──────────────┬──────────────────────┘
               │ Built on
               ▼
┌─────────────────────────────────────┐
│      Ktor Engine (CIO/OkHttp)       │
│  • Manages actual TCP connections   │
│  • Handles WebSocket protocol       │
│  • Non-blocking I/O                 │
└──────────────┬──────────────────────┘
               │
               ▼
          [Network]
```

---

## Part 2: Our WebSocket Client - The Setup

### The HttpClient Instance

```kotlin
// In the test setup:
val httpClient = HttpClient(CIO) {
    install(WebSockets) {
        pingInterval = 20_000 // 20 seconds
    }
}
```

**What's happening:**
1. `HttpClient(CIO)` - Creates HTTP client using CIO engine
   - **CIO** = Coroutine-based I/O (pure Kotlin, no JVM blocking)
   - Alternative engines: OkHttp (wraps OkHttp library), Darwin (iOS)

2. `install(WebSockets)` - Adds WebSocket capability to client
   - This is a **Ktor plugin** that adds WebSocket-specific functionality
   - Without this, `webSocketSession()` won't exist

3. `pingInterval = 20_000` - Automatic keep-alive
   - Ktor automatically sends ping frames every 20 seconds
   - Server responds with pong (or closes connection if dead)

### Creating the WebSocket Session

```kotlin
// GraphQLWebSocketClient.kt line 70-75
session = httpClient.webSocketSession(
    urlString = url,
    block = {
        header(HttpHeaders.SecWebSocketProtocol, "graphql-transport-ws")
    }
)
```

**What's happening:**

```
Application Thread
    ↓
[Call webSocketSession()]
    ↓
Ktor creates coroutine for connection
    ↓
┌─────────────────────────────────┐
│  WebSocket Handshake Process    │
│  1. TCP connection              │
│  2. HTTP Upgrade request        │
│     GET /api/v3/graphql/ws      │
│     Upgrade: websocket          │
│     Sec-WebSocket-Protocol:     │
│        graphql-transport-ws     │
│  3. Server responds 101         │
│  4. Connection established      │
└─────────────────────────────────┘
    ↓
Returns: DefaultClientWebSocketSession
    ↓
[Assigned to session variable]
```

**Key Point:** `webSocketSession()` is a **suspend function**. It:
- Suspends until connection is established
- Returns a session object
- Does NOT block the thread (coroutine magic!)

---

## Part 3: Understanding Channels 🚰

**Channel = A pipe for sending data between coroutines**

Think of it like a water pipe:
- **Producer** pours water in one end
- **Consumer** drinks water from other end
- If pipe is full, producer waits
- If pipe is empty, consumer waits

### Channel Basics

```kotlin
// Create a channel
val channel = Channel<String>(capacity = 10)

// Producer coroutine
launch {
    channel.send("Hello")  // Suspends if channel is full
    channel.send("World")
}

// Consumer coroutine
launch {
    val msg1 = channel.receive()  // Suspends if channel is empty
    val msg2 = channel.receive()
    println("$msg1 $msg2")  // Prints: Hello World
}
```

**Key Properties:**
- **Thread-safe** - Multiple coroutines can send/receive safely
- **Suspending** - Doesn't block threads, just suspends coroutines
- **Buffered** - Can hold N items before producer must wait

### Channel Capacity Types

```kotlin
Channel<T>(UNLIMITED)  // Infinite buffer (dangerous!)
Channel<T>(RENDEZVOUS) // capacity=0, send waits for receive
Channel<T>(CONFLATED)  // capacity=1, new values overwrite old
Channel<T>(8)          // Fixed buffer of 8 items
```

---

## Part 4: Channels in Our WebSocket Client

### The Incoming Channel (session.incoming)

```kotlin
// Line 80-96 in our connect() function
scope.launch {
    for (frame in session!!.incoming) {
        when (frame) {
            is Frame.Text -> {
                val text = frame.readText()
                val message = parseMessage(text)
                if (message is GraphQLWebSocketMessage.ConnectionAck) {
                    connected.set(true)
                    ackReceived.complete(Unit)
                }
            }
            else -> { /* Ignore */ }
        }
    }
}
```

**What is `session.incoming`?**

```kotlin
// Inside Ktor's WebSocketSession interface:
val incoming: ReceiveChannel<Frame>
```

It's a **Channel<Frame>** that Ktor fills with incoming WebSocket frames.

**Flow:**
```
Server sends WebSocket frame
    ↓
[Network layer receives bytes]
    ↓
[Ktor engine decodes WebSocket frame]
    ↓
Ktor: channel.send(frame)
    ↓
[Frame sits in channel buffer]
    ↓
Our code: for (frame in channel)
    ↓
[We process frame]
```

**Why use Channel?**
- **Decoupling:** Network thread receives frames, our coroutine processes them
- **Buffering:** If we're slow processing, frames queue up (won't be dropped)
- **Backpressure:** If buffer is full, Ktor will slow down receiving

### The Outgoing Channel (session.outgoing)

```kotlin
// We don't use outgoing directly, but session.send() does:
session?.send(Frame.Text(json))

// Under the hood, Ktor does:
// session.outgoing.send(Frame.Text(json))
```

**Flow:**
```
Our code: session.send(Frame.Text(json))
    ↓
Ktor: outgoing.send(frame)
    ↓
[Frame sits in channel buffer]
    ↓
[Network coroutine picks up frame]
    ↓
[Ktor engine encodes WebSocket frame]
    ↓
[Sends bytes over network]
    ↓
Server receives frame
```

### Our Custom Channels (activeSubscriptions)

```kotlin
// Line 47
private val activeSubscriptions = mutableMapOf<String, Channel<JsonElement>>()

// Line 137 in subscribe() function
val channel = Channel<JsonElement>(Channel.UNLIMITED)
activeSubscriptions[operationId] = channel
```

**Why do we need this?**

Imagine multiple GraphQL subscriptions running at once:
```
Subscription 1: "Listen for new blocks"
Subscription 2: "Listen for transactions on address A"
Subscription 3: "Listen for transactions on address B"
```

**The Problem:**
- All messages come through ONE WebSocket connection
- Server sends: `{"id": "sub_1", "payload": {...}}` (for Subscription 1)
- Server sends: `{"id": "sub_2", "payload": {...}}` (for Subscription 2)
- We need to route messages to the correct subscription!

**The Solution:**
```
                     ┌─────────────────┐
Server ──────────────▶│ session.incoming│
                     └────────┬────────┘
                              │
                              ▼
                     [parseMessage(frame)]
                              │
                    ┌─────────┼─────────┐
                    │         │         │
                    ▼         ▼         ▼
          channel["sub_1"] ["sub_2"] ["sub_3"]
                    │         │         │
                    ▼         ▼         ▼
              Flow 1      Flow 2    Flow 3
                    │         │         │
                    ▼         ▼         ▼
            Consumer 1  Consumer 2  Consumer 3
```

**Code walkthrough:**

```kotlin
// When server sends a message:
private suspend fun handleMessage(message: GraphQLWebSocketMessage) {
    when (message) {
        is GraphQLWebSocketMessage.Next -> {
            // Message has: message.id = "sub_2"
            //             message.payload = {...}

            // Find the channel for this subscription
            val channel = activeSubscriptions[message.id]

            // Send payload to that specific channel
            channel?.send(message.payload)  // ← Only Flow 2 receives this!
        }
    }
}
```

---

## Part 5: Understanding Atomic Operations 🔒

**Problem:** Multiple threads/coroutines accessing shared state

### The Race Condition

```kotlin
// BAD CODE (not thread-safe):
private var connected: Boolean = false

// Thread 1:
if (!connected) {
    connected = true
    // Connect...
}

// Thread 2 (at same time):
if (!connected) {  // ← Both threads see false!
    connected = true  // ← Both threads try to connect!
    // Connect...       // ← Connection happens TWICE! 💥
}
```

**What went wrong:**
```
Time  │ Thread 1                │ Thread 2
──────┼─────────────────────────┼──────────────────────────
0ms   │ read connected = false  │
1ms   │                         │ read connected = false
2ms   │ write connected = true  │
3ms   │ start connecting...     │
4ms   │                         │ write connected = true
5ms   │                         │ start connecting...
      │                         │
      └──▶ TWO CONNECTIONS OPENED! 💥
```

### Atomic Operations to the Rescue

```kotlin
// GOOD CODE (thread-safe):
private val connected = AtomicBoolean(false)

// Thread 1:
if (connected.compareAndSet(false, true)) {  // Atomic!
    // Connect...
}

// Thread 2 (at same time):
if (connected.compareAndSet(false, true)) {  // Will return false!
    // This never executes
}
```

**What `compareAndSet` does:**
```kotlin
fun compareAndSet(expect: Boolean, update: Boolean): Boolean {
    // This entire operation is ATOMIC (indivisible)
    if (currentValue == expect) {
        currentValue = update
        return true  // Success
    } else {
        return false  // Someone else changed it
    }
}
```

**Atomic operations are indivisible:**
```
Time  │ Thread 1                      │ Thread 2
──────┼───────────────────────────────┼─────────────────────────
0ms   │ compareAndSet(false, true)    │
      │   ├─ read = false              │
      │   ├─ write = true              │
      │   └─ return true ✅            │
1ms   │                               │ compareAndSet(false, true)
      │                               │   ├─ read = true (already!)
      │                               │   ├─ no change
      │                               │   └─ return false ❌
2ms   │ start connecting...           │
3ms   │                               │ (connection skipped)
      │                               │
      └──▶ ONLY ONE CONNECTION! ✅
```

---

## Part 6: Atomic Variables in Our Code

### AtomicBoolean for Connection State

```kotlin
// Line 45
private val connected = AtomicBoolean(false)

// Line 63 - Checking if already connected
if (connected.get()) {
    throw IllegalStateException("Already connected")
}

// Line 85 - Marking as connected
connected.set(true)
```

**Why atomic?**

Scenario without atomic:
```kotlin
// Two coroutines call connect() simultaneously

// Coroutine 1:
if (!connected) {        // reads false
    connected = true     // sets true
    // Start connection
}

// Coroutine 2 (simultaneously):
if (!connected) {        // reads false (before Coroutine 1 sets it!)
    connected = true     // sets true
    // Start connection  // ← Opens SECOND connection! 💥
}
```

Scenario with atomic:
```kotlin
// Coroutine 1:
if (connected.get()) {              // reads false
    throw AlreadyConnected
}
// ... time passes ...
connected.set(true)                 // sets true

// Coroutine 2 (right after Coroutine 1 set it):
if (connected.get()) {              // reads true ✅
    throw AlreadyConnected          // ← Correctly prevents duplicate!
}
```

**Note:** Our current code isn't perfectly thread-safe because there's a gap between `get()` and `set()`. Better approach:

```kotlin
// More robust (but we didn't implement this):
if (!connected.compareAndSet(false, true)) {
    throw IllegalStateException("Already connected")
}
// Now connected is true, and we know no one else changed it
```

### AtomicInteger for Operation IDs

```kotlin
// Line 46
private val operationIdCounter = AtomicInteger(0)

// Line 188-190
private fun generateOperationId(): String {
    return "sub_${operationIdCounter.incrementAndGet()}"
}
```

**Why atomic?**

Scenario without atomic:
```kotlin
var counter = 0  // Not atomic

// Coroutine 1:
val id1 = counter++  // read=0, write=1, return 0

// Coroutine 2 (simultaneously):
val id2 = counter++  // read=0 (before write!), write=1, return 0

// Result: id1 = 0, id2 = 0  ← DUPLICATE IDs! 💥
```

Scenario with atomic:
```kotlin
val counter = AtomicInteger(0)  // Atomic

// Coroutine 1:
val id1 = counter.incrementAndGet()  // atomically: read=0, write=1, return 1

// Coroutine 2 (simultaneously):
val id2 = counter.incrementAndGet()  // atomically: read=1, write=2, return 2

// Result: id1 = 1, id2 = 2  ← UNIQUE IDs! ✅
```

**What `incrementAndGet()` does:**
```kotlin
fun incrementAndGet(): Int {
    // This entire operation is ATOMIC
    currentValue = currentValue + 1
    return currentValue
}
```

---

## Part 7: The Complete Flow - How It All Works Together

### Establishing Connection

```kotlin
suspend fun connect() {
    // Step 1: Check connection state (atomic read)
    if (connected.get()) {
        throw IllegalStateException("Already connected")
    }

    // Step 2: Open WebSocket connection
    session = httpClient.webSocketSession(
        urlString = url,
        block = {
            header(HttpHeaders.SecWebSocketProtocol, "graphql-transport-ws")
        }
    )
    // At this point: TCP connection established, WebSocket handshake complete

    // Step 3: Send connection_init message
    sendMessage(GraphQLWebSocketMessage.ConnectionInit())

    // Step 4: Wait for connection_ack (with timeout)
    withTimeout(connectionTimeout) {
        val ackReceived = CompletableDeferred<Unit>()

        // Step 4a: Launch coroutine to listen for messages
        scope.launch {
            for (frame in session!!.incoming) {  // ← Channel iteration
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        val message = parseMessage(text)
                        if (message is GraphQLWebSocketMessage.ConnectionAck) {
                            connected.set(true)  // ← Atomic write
                            ackReceived.complete(Unit)
                        }
                    }
                    else -> { /* Ignore */ }
                }
            }
        }

        // Step 4b: Wait for ack (suspends until coroutine completes ack)
        ackReceived.await()
    }

    // Step 5: Start message processing loop
    startMessageProcessing()
}
```

**Visual timeline:**
```
Time │ Main Coroutine              │ Listening Coroutine      │ Network
─────┼─────────────────────────────┼──────────────────────────┼──────────
0ms  │ connect() called            │                          │
1ms  │ webSocketSession()          │                          │
     │   [suspends]                │                          │ → TCP connect
50ms │                             │                          │ ← 101 Switching
51ms │   [resumes]                 │                          │
52ms │ sendMessage(ConnectionInit) │                          │ → {"type":"connection_init"}
53ms │ launch {listen}             │ ▶ Coroutine starts       │
54ms │ ackReceived.await()         │   for(frame in incoming) │
     │   [suspends]                │     [suspends]           │
100ms│                             │                          │ ← {"type":"connection_ack"}
101ms│                             │     [resumes]            │
102ms│                             │   parseMessage()         │
103ms│                             │   connected.set(true) ✅ │
104ms│                             │   ackReceived.complete() │
105ms│   [resumes]                 │                          │
106ms│ startMessageProcessing()    │                          │
107ms│ return ✅                   │                          │
```

### Creating a Subscription

```kotlin
fun subscribe(
    query: String,
    variables: Map<String, Any>? = null,
    operationName: String? = null
): Flow<JsonElement> = flow {
    // Step 1: Check connection (atomic read)
    if (!connected.get()) {
        throw IllegalStateException("Not connected")
    }

    // Step 2: Generate unique operation ID (atomic increment)
    val operationId = generateOperationId()  // "sub_1"

    // Step 3: Create channel for this subscription
    val channel = Channel<JsonElement>(Channel.UNLIMITED)
    activeSubscriptions[operationId] = channel
    // Now: activeSubscriptions = {"sub_1" -> channel}

    try {
        // Step 4: Send subscribe message
        sendMessage(GraphQLWebSocketMessage.Subscribe(id = operationId, ...))

        // Step 5: Emit results from channel as they arrive
        for (result in channel) {  // ← Channel iteration
            emit(result)  // ← Sends to Flow collector
        }
    } finally {
        // Step 6: Cleanup when Flow is cancelled
        activeSubscriptions.remove(operationId)
        sendMessage(GraphQLWebSocketMessage.Complete(id = operationId))
    }
}
```

**How messages flow to the right subscription:**

```
1. Background message processing loop:

startMessageProcessing() {
    scope.launch {
        for (frame in session!!.incoming) {  // ← Messages from server
            if (frame is Frame.Text) {
                val message = parseMessage(frame.readText())
                handleMessage(message)  // ← Route message
            }
        }
    }
}

2. Message routing:

handleMessage(message) {
    when (message) {
        is GraphQLWebSocketMessage.Next -> {
            // message.id = "sub_1"
            // message.payload = {...}

            val channel = activeSubscriptions["sub_1"]  // ← Find right channel
            channel?.send(message.payload)  // ← Send to channel
        }
    }
}

3. Subscriber receives:

// User code:
client.subscribe(query).collect { result ->
    // This receives from channel!
    println("Got result: $result")
}
```

**Complete data flow:**
```
Server
  ↓ (WebSocket frame)
session.incoming Channel
  ↓ (for frame in incoming)
Message Processing Loop
  ↓ (parseMessage)
GraphQLWebSocketMessage.Next
  ↓ (handleMessage)
activeSubscriptions["sub_1"] Channel
  ↓ (for result in channel)
Flow emitter
  ↓ (emit)
Flow collector
  ↓
User code (collect { })
```

---

## Part 8: Thread Safety Analysis

### What's Thread-Safe?

✅ **Channels** - Built-in thread safety
```kotlin
val channel = Channel<String>()

// Multiple coroutines can safely:
launch { channel.send("A") }  // Coroutine 1
launch { channel.send("B") }  // Coroutine 2
launch { channel.receive() }  // Coroutine 3
```

✅ **Atomic operations**
```kotlin
val counter = AtomicInteger(0)

// Multiple coroutines can safely:
launch { counter.incrementAndGet() }  // Coroutine 1
launch { counter.incrementAndGet() }  // Coroutine 2
// Results will be 1, 2 (order undefined but unique)
```

✅ **Immutable data**
```kotlin
val json = Json { encodeDefaults = true }  // Created once, never modified
// Safe to use from any coroutine
```

### What's NOT Thread-Safe?

⚠️ **MutableMap without synchronization**
```kotlin
private val activeSubscriptions = mutableMapOf<String, Channel<JsonElement>>()

// Problem: Two coroutines modifying map simultaneously
launch { activeSubscriptions["sub_1"] = channel1 }  // Coroutine 1
launch { activeSubscriptions["sub_2"] = channel2 }  // Coroutine 2
// Could corrupt internal map structure! 💥
```

**Why it's okay in our code:**
- All modifications happen in the **same coroutine context** (CoroutineScope)
- Coroutine dispatchers ensure operations don't overlap
- `subscribe()` runs in caller's context (sequential access per subscription)

**Better approach (if we had concurrent access):**
```kotlin
private val activeSubscriptions = ConcurrentHashMap<String, Channel<JsonElement>>()
// Or use Mutex for synchronization
```

⚠️ **Session state check and set**
```kotlin
// Current code has a race:
if (connected.get()) {  // ← Read
    throw AlreadyConnected
}
// ... time gap here ...
connected.set(true)  // ← Write

// Better:
if (!connected.compareAndSet(false, true)) {
    throw AlreadyConnected
}
```

---

## Part 9: Coroutine Scopes and Lifecycle

### The CoroutineScope

```kotlin
// Line 48
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
```

**What this means:**

```kotlin
CoroutineScope(
    Dispatchers.IO +      // Context: I/O dispatcher (optimized for network/disk)
    SupervisorJob()       // Job: Failures don't cancel siblings
)
```

**Dispatchers.IO:**
- Thread pool optimized for I/O operations (network, database)
- Vs `Dispatchers.Default` (CPU-intensive work)
- Vs `Dispatchers.Main` (Android UI thread)

**SupervisorJob:**
```kotlin
// Without SupervisorJob:
scope.launch {
    channel1.collect { }  // If this crashes...
}
scope.launch {
    channel2.collect { }  // ...this gets cancelled too! 💥
}

// With SupervisorJob:
scope.launch {
    channel1.collect { }  // If this crashes...
}
scope.launch {
    channel2.collect { }  // ...this keeps running ✅
}
```

### Launching Coroutines

```kotlin
// Line 79-96 in connect()
scope.launch {
    for (frame in session!!.incoming) {
        // Process frames...
    }
}

// Line 221-235 in startMessageProcessing()
scope.launch {
    try {
        session?.incoming?.consumeAsFlow()?.collect { frame ->
            // Handle frame...
        }
    } catch (e: Exception) {
        // Connection closed or error
    }
}
```

**What `scope.launch` does:**
```
Main Coroutine
    │
    ├─ scope.launch { }  ← Starts new coroutine
    │       │
    │       └─ Runs in background on Dispatchers.IO thread pool
    │
    └─ Continues immediately (doesn't wait for launch)
```

### Cleanup and Cancellation

```kotlin
suspend fun close() {
    if (!connected.get()) return

    // Complete all active subscriptions
    activeSubscriptions.values.forEach { it.close() }
    activeSubscriptions.clear()

    // Close WebSocket
    session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client closing"))
    session = null
    connected.set(false)

    // Cancel all coroutines in scope
    scope.cancel()  // ← Cancels all launched coroutines!
}
```

**What happens when scope.cancel():**
```
scope.cancel()
    ↓
[Cancels SupervisorJob]
    ↓
┌───────────────────────────────┐
│ All launched coroutines get   │
│ CancellationException         │
└───────────────────────────────┘
    ↓
for (frame in incoming) {  ← Exits loop
}
```

---

## Part 10: CompletableDeferred - The Promise Pattern

```kotlin
// Line 77-91 in connect()
withTimeout(connectionTimeout) {
    val ackReceived = CompletableDeferred<Unit>()

    scope.launch {
        // ... wait for connection_ack ...
        ackReceived.complete(Unit)  // ← Signal completion
    }

    ackReceived.await()  // ← Wait for signal
}
```

**CompletableDeferred = A one-time signal between coroutines**

Think of it like a promise:
```kotlin
// Create a promise
val promise = CompletableDeferred<String>()

// Coroutine 1: Does work and fulfills promise
launch {
    delay(1000)
    val result = doWork()
    promise.complete(result)  // ← "I'm done! Here's the result"
}

// Coroutine 2: Waits for promise
launch {
    val result = promise.await()  // ← Suspends until complete() is called
    println("Got result: $result")
}
```

**In our code:**
```
Main coroutine                    Listening coroutine
     │                                   │
     │ val ackReceived =                 │
     │   CompletableDeferred<Unit>()     │
     │                                   │
     │ scope.launch {                    │
     │   [starts listening coroutine] ───▶
     │                                   │
     │ ackReceived.await()               │
     │   [suspends]                      │
     │                                   │ for (frame in incoming) {
     │                                   │   ... wait for frame ...
     │                                   │ }
     │                                   │
     │                                   │ [Frame arrives!]
     │                                   │ parseMessage()
     │                                   │ if (ConnectionAck) {
     │                                   │   ackReceived.complete(Unit)
     │  [resumes] ◀──────────────────────┘
     │
     │ return (connected!)
     │
```

---

## Summary: Key Concepts

### 1. Ktor Client
- Asynchronous HTTP/WebSocket client for Kotlin
- Built on coroutines (suspend functions, non-blocking)
- Engine (CIO) handles low-level networking

### 2. Channels
- Thread-safe pipes for coroutine communication
- `session.incoming` - Receives frames from server
- `session.outgoing` - Sends frames to server
- `activeSubscriptions[id]` - Routes messages to specific subscriptions
- **Buffer** - Holds items until consumer is ready
- **Suspending** - Producer waits if full, consumer waits if empty

### 3. Atomic Operations
- `AtomicBoolean` - Thread-safe boolean (connected state)
- `AtomicInteger` - Thread-safe counter (operation IDs)
- **Indivisible** - Read-modify-write happens atomically
- **Prevents race conditions** - No overlapping modifications

### 4. Coroutines & Flows
- `scope.launch {}` - Start background coroutine
- `flow { emit(...) }` - Create cold stream
- `for (x in channel)` - Consume channel as sequence
- `suspend fun` - Can be paused and resumed

### 5. Data Flow
```
Server WebSocket
    ↓
session.incoming (Channel)
    ↓
Message Processing Loop
    ↓
parseMessage() + handleMessage()
    ↓
activeSubscriptions["sub_1"] (Channel)
    ↓
Flow emitter
    ↓
User's .collect { } block
```

### 6. Thread Safety
✅ Channels - Built-in
✅ Atomic variables - Built-in
✅ Immutable data - Safe everywhere
⚠️ MutableMap - Protected by coroutine dispatcher
⚠️ Session state - Could use compareAndSet

---

## Next Time You See...

**`for (frame in session.incoming)`**
→ "Iterating over a Channel, suspending when empty"

**`channel.send(value)`**
→ "Putting value in pipe, suspending if full"

**`connected.get()`**
→ "Atomic read, thread-safe"

**`operationIdCounter.incrementAndGet()`**
→ "Atomic increment, prevents duplicate IDs"

**`scope.launch {}`**
→ "Starting background coroutine on IO dispatcher"

**`flow { emit(x) }`**
→ "Creating cold stream that emits values"

**`ackReceived.await()`**
→ "Suspending until another coroutine calls complete()"

---

**Questions? Ask about any specific part!**
