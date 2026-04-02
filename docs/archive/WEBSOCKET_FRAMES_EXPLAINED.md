# WebSocket Frames - The Complete Picture

**Understanding WebSockets from the Ground Up**

---

## Part 1: What is a WebSocket? 🔌

### Traditional HTTP (Request-Response)

```
Client                              Server
  │                                   │
  │──── GET /api/data ───────────────▶│
  │                                   │ (Server processes)
  │◀──── 200 OK {data: "..."}────────│
  │                                   │
  │ (Connection closes)               │

  │ (Want more data? Start over!)    │
  │──── GET /api/data ───────────────▶│
  │◀──── 200 OK {data: "..."}────────│
```

**Problems:**
- ❌ One direction at a time (client asks, server responds)
- ❌ Connection closes after each request
- ❌ Server can't push data (must wait for client to ask)
- ❌ Overhead: HTTP headers sent with EVERY request

### WebSocket (Persistent Connection)

```
Client                              Server
  │                                   │
  │──── HTTP Upgrade Request ────────▶│
  │◀──── 101 Switching Protocols ────│
  │                                   │
  │════════ WebSocket Open ═══════════│
  │                                   │
  │──── Message 1 ───────────────────▶│
  │◀──── Message 2 ───────────────────│
  │◀──── Message 3 ───────────────────│  (Server pushes!)
  │──── Message 4 ───────────────────▶│
  │◀──── Message 5 ───────────────────│
  │                                   │
  │ (Connection stays open)           │
  │                                   │
  │──── Close ───────────────────────▶│
```

**Benefits:**
- ✅ Full-duplex: Both sides can send anytime
- ✅ Persistent: Connection stays open
- ✅ Server can push: No need to poll
- ✅ Less overhead: Headers sent once during handshake

---

## Part 2: What Are Frames? 📦

**Frame = The atomic unit of data in WebSocket protocol**

Think of sending a package:
- **Frame** = The physical box
- **Message** = The actual item inside
- **Protocol** = The shipping company's rules

### Frame Structure (Simplified)

```
┌────────────────────────────────────────────┐
│              WebSocket Frame               │
├────────────────────────────────────────────┤
│                                            │
│  Header (2-14 bytes)                       │
│  ┌──────────────────────────────────────┐  │
│  │ FIN  │ OpCode │ Mask │ Length │ ... │  │
│  └──────────────────────────────────────┘  │
│                                            │
│  Payload Data (0 - N bytes)                │
│  ┌──────────────────────────────────────┐  │
│  │ "Hello, World!"                      │  │
│  │ or                                   │  │
│  │ { "type": "message", "data": "..." }│  │
│  └──────────────────────────────────────┘  │
│                                            │
└────────────────────────────────────────────┘
```

### Frame Header Fields

**1. FIN bit (1 bit)**
- `1` = This is the final frame of the message
- `0` = More frames coming (message is fragmented)

**2. OpCode (4 bits) - Frame Type**
```
0x0 = Continuation (part of fragmented message)
0x1 = Text frame (UTF-8 text)
0x2 = Binary frame (raw bytes)
0x8 = Close frame (connection closing)
0x9 = Ping frame (keep-alive check)
0xA = Pong frame (response to ping)
```

**3. Mask bit (1 bit)**
- `1` = Payload is masked (client → server, required by spec)
- `0` = Payload not masked (server → client)

**4. Payload Length (7 bits, or extended)**
- `0-125` = Actual length
- `126` = Next 16 bits contain length
- `127` = Next 64 bits contain length

---

## Part 3: Frame Types in Detail

### 1. Text Frame (OpCode 0x1)

**Purpose:** Send UTF-8 encoded text

```
Client sends:
┌────────────────────────────────────────────┐
│ FIN=1, OpCode=0x1 (Text)                   │
│ Payload: {"type":"connection_init"}        │
└────────────────────────────────────────────┘

Server receives:
│
├─ Ktor decodes: Frame.Text
│
└─ Our code: frame.readText() = '{"type":"connection_init"}'
```

**In our code:**
```kotlin
when (frame) {
    is Frame.Text -> {
        val text = frame.readText()  // "{"type":"connection_init"}"
        println("Received text: $text")
    }
}
```

### 2. Binary Frame (OpCode 0x2)

**Purpose:** Send raw binary data

```
┌────────────────────────────────────────────┐
│ FIN=1, OpCode=0x2 (Binary)                 │
│ Payload: [0x89, 0x50, 0x4E, 0x47, ...]     │
└────────────────────────────────────────────┘
```

**Example use cases:**
- Sending images
- Protobuf/MessagePack encoded data
- File transfers

**In our code (we don't use binary):**
```kotlin
when (frame) {
    is Frame.Binary -> {
        val bytes = frame.readBytes()  // ByteArray
    }
}
```

### 3. Close Frame (OpCode 0x8)

**Purpose:** Gracefully close connection

```
┌────────────────────────────────────────────┐
│ FIN=1, OpCode=0x8 (Close)                  │
│ Payload: [Code: 1000, Reason: "Normal"]   │
└────────────────────────────────────────────┘
```

**Close codes:**
```
1000 = Normal closure
1001 = Going away (server shutting down)
1002 = Protocol error
1003 = Unsupported data
1006 = Abnormal closure (no close frame)
1008 = Policy violation
1009 = Message too big
1011 = Server error
```

**In our code:**
```kotlin
when (frame) {
    is Frame.Close -> {
        val reason = frame.readReason()
        println("Connection closed: ${reason?.code} - ${reason?.message}")
    }
}
```

### 4. Ping Frame (OpCode 0x9)

**Purpose:** Keep-alive / check if connection is alive

```
Client sends:
┌────────────────────────────────────────────┐
│ FIN=1, OpCode=0x9 (Ping)                   │
│ Payload: (optional data)                   │
└────────────────────────────────────────────┘

Server must respond:
┌────────────────────────────────────────────┐
│ FIN=1, OpCode=0xA (Pong)                   │
│ Payload: (same data from ping)             │
└────────────────────────────────────────────┘
```

**Why ping/pong?**
- Detect dead connections (no response = connection is dead)
- Keep connection alive (some proxies close idle connections)
- Measure latency

**In our code (Ktor handles this automatically):**
```kotlin
// When creating client:
HttpClient(CIO) {
    install(WebSockets) {
        pingInterval = 20_000  // Ktor sends ping every 20 seconds automatically
    }
}

// If we want to send manual ping:
wsClient.ping()  // Sends ping frame
```

### 5. Pong Frame (OpCode 0xA)

**Purpose:** Response to Ping

**In our code (Ktor handles automatically):**
```kotlin
// When we receive Ping, we respond with Pong
private suspend fun handleMessage(message: GraphQLWebSocketMessage) {
    when (message) {
        is GraphQLWebSocketMessage.Ping -> {
            sendMessage(GraphQLWebSocketMessage.Pong())  // Respond to ping
        }
    }
}
```

---

## Part 4: Message Fragmentation 🧩

**Large messages can be split into multiple frames**

### Example: Sending Large JSON (5KB)

```
Client wants to send: {"data": "...(5000 bytes)..."}

Split into 3 frames:

Frame 1:
┌────────────────────────────────────────────┐
│ FIN=0, OpCode=0x1 (Text, not final)        │
│ Payload: {"data": "...(2000 bytes)         │
└────────────────────────────────────────────┘

Frame 2:
┌────────────────────────────────────────────┐
│ FIN=0, OpCode=0x0 (Continuation)           │
│ Payload: ...(2000 bytes)...                │
└────────────────────────────────────────────┘

Frame 3:
┌────────────────────────────────────────────┐
│ FIN=1, OpCode=0x0 (Continuation, final)    │
│ Payload: ...(1000 bytes)..."}              │
└────────────────────────────────────────────┘

Server reassembles: {"data": "...(5000 bytes)..."}
```

**Ktor handles this for us!** When we do `frame.readText()`, Ktor has already reassembled fragmented frames.

---

## Part 5: The WebSocket Handshake 🤝

**How HTTP becomes WebSocket**

### Step 1: Client Sends Upgrade Request

```http
GET /api/v3/graphql/ws HTTP/1.1
Host: indexer.preview.midnight.network
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
Sec-WebSocket-Protocol: graphql-transport-ws
```

**Key headers:**
- `Upgrade: websocket` - "I want to switch to WebSocket"
- `Connection: Upgrade` - "Keep connection open for upgrade"
- `Sec-WebSocket-Key` - Random value for security (prevents caching)
- `Sec-WebSocket-Protocol` - Sub-protocol we want to use

### Step 2: Server Responds with 101 Switching Protocols

```http
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
Sec-WebSocket-Protocol: graphql-transport-ws
```

**Key headers:**
- `101 Switching Protocols` - "OK, switching!"
- `Sec-WebSocket-Accept` - Derived from client's key (proves server understands WebSocket)
- `Sec-WebSocket-Protocol` - Sub-protocol agreed upon

### Step 3: Connection Upgraded - Now Speaking WebSocket

```
[HTTP connection is now WebSocket connection]
[Can send frames in both directions]
```

**In our code (Ktor does handshake):**
```kotlin
session = httpClient.webSocketSession(
    urlString = url,
    block = {
        // This becomes the Sec-WebSocket-Protocol header
        header(HttpHeaders.SecWebSocketProtocol, "graphql-transport-ws")
    }
)
// When this returns, handshake is complete and connection is upgraded ✅
```

---

## Part 6: What Ktor Does For Us 🎁

### Raw WebSocket (Without Ktor)

```kotlin
// Pseudo-code of what we'd have to do manually:

// 1. Open TCP socket
val socket = Socket("indexer.midnight.network", 443)

// 2. Do TLS handshake (for wss://)
val tlsSocket = SSLSocket(socket)

// 3. Send HTTP Upgrade request
tlsSocket.write("""
    GET /api/v3/graphql/ws HTTP/1.1
    Host: indexer.midnight.network
    Upgrade: websocket
    Connection: Upgrade
    Sec-WebSocket-Key: ${generateRandomKey()}
    Sec-WebSocket-Version: 13
    Sec-WebSocket-Protocol: graphql-transport-ws
""")

// 4. Read HTTP response
val response = tlsSocket.readLine()
if (response != "HTTP/1.1 101 Switching Protocols") {
    throw Exception("Handshake failed")
}

// 5. Manually encode/decode WebSocket frames
fun sendText(text: String) {
    val frame = ByteArray(2 + text.length)
    frame[0] = 0x81.toByte()  // FIN=1, OpCode=0x1 (Text)
    frame[1] = text.length.toByte()  // Length (simplified)
    // ... copy text bytes ...
    // ... apply masking (required for client->server) ...
    socket.write(frame)
}

// 6. Read and decode frames
fun readFrame(): Frame {
    val header = socket.read(2)  // Read header
    val fin = (header[0].toInt() and 0x80) != 0
    val opcode = header[0].toInt() and 0x0F
    val masked = (header[1].toInt() and 0x80) != 0
    val length = header[1].toInt() and 0x7F
    // ... handle extended length ...
    // ... unmask payload if masked ...
    // ... reassemble fragmented messages ...
    return Frame(fin, opcode, payload)
}

// 7. Handle ping/pong
// 8. Handle close frames
// 9. Handle connection errors
// 10. Thread management
// ... 500+ lines of boilerplate ...
```

### With Ktor ✨

```kotlin
// All the above in 3 lines:
val httpClient = HttpClient(CIO) {
    install(WebSockets)
}

val session = httpClient.webSocketSession(url) {
    header(HttpHeaders.SecWebSocketProtocol, "graphql-transport-ws")
}

// Send text frame:
session.send(Frame.Text("Hello"))

// Receive frames:
for (frame in session.incoming) {
    when (frame) {
        is Frame.Text -> println(frame.readText())
    }
}
```

**What Ktor handles:**
- ✅ TCP connection
- ✅ TLS/SSL (for wss://)
- ✅ HTTP handshake
- ✅ Frame encoding/decoding
- ✅ Message fragmentation/reassembly
- ✅ Masking (client → server)
- ✅ Ping/pong keep-alive
- ✅ Connection management
- ✅ Error handling
- ✅ Thread safety
- ✅ Coroutine integration

---

## Part 7: Frame Flow in Our Code

### Complete Journey of a Frame

```
1. We call:
   session.send(Frame.Text('{"type":"connection_init"}'))

2. Ktor encodes:
   ┌────────────────────────────────────────────┐
   │ FIN=1, OpCode=0x1, Mask=1, Length=28      │
   │ {"type":"connection_init"}                 │
   └────────────────────────────────────────────┘

3. Ktor sends bytes over network:
   [0x81, 0x9C, 0xMASK, ...masked payload...]

4. Server receives and decodes:
   ← Frame: Text, FIN=1, payload='{"type":"connection_init"}'

5. Server processes and responds:
   → Frame: Text, FIN=1, payload='{"type":"connection_ack"}'

6. Ktor receives bytes:
   [0x81, 0x14, ...payload...]

7. Ktor decodes and puts in channel:
   session.incoming.send(Frame.Text('{"type":"connection_ack"}'))

8. Our coroutine reads from channel:
   for (frame in session.incoming) {
       // Receives: Frame.Text('{"type":"connection_ack"}')
   }

9. We extract text:
   val text = frame.readText()  // '{"type":"connection_ack"}'

10. We parse JSON:
    val message = json.decodeFromString<GraphQLWebSocketMessage>(text)
    // GraphQLWebSocketMessage.ConnectionAck
```

### Frame Types in Our Application

```kotlin
// LINE 80-90 in GraphQLWebSocketClient.kt
for (frame in session!!.incoming) {
    when (frame) {
        is Frame.Text -> {
            // Received text frame
            // OpCode 0x1, contains JSON string
            val text = frame.readText()
            // text = '{"type":"connection_ack"}'
            // or '{"id":"sub_1","payload":{...}}'
        }

        is Frame.Close -> {
            // Received close frame
            // OpCode 0x8, connection closing
            val reason = frame.readReason()
            // reason = CloseReason(code=1000, message="Normal")
        }

        is Frame.Binary -> {
            // Received binary frame
            // OpCode 0x2, raw bytes
            // We don't use this in GraphQL-WS
        }

        is Frame.Ping -> {
            // Received ping frame
            // OpCode 0x9, keep-alive check
            // Ktor auto-responds with Pong
        }

        is Frame.Pong -> {
            // Received pong frame
            // OpCode 0xA, response to our ping
            // Confirms connection is alive
        }
    }
}
```

---

## Part 8: Why Frames Matter 🎯

### 1. Multiplexing Multiple Data Types

```
Same connection, different frame types:

Time │ Direction │ Frame Type  │ Purpose
─────┼───────────┼─────────────┼────────────────────────
0ms  │ Client→   │ Text        │ Send GraphQL query
50ms │ ←Server   │ Text        │ Query result
100ms│ Client→   │ Ping        │ Keep-alive check
101ms│ ←Server   │ Pong        │ Connection alive
200ms│ ←Server   │ Text        │ Subscription update
1000ms│ Client→  │ Close       │ Closing connection
```

### 2. Efficient Data Transfer

**Without frames (like raw TCP):**
```
Send: "Message1Message2Message3"
Receive: "Message1Mes"... wait ... "sage2Message3"

Problem: Where does Message1 end and Message2 begin? 🤔
```

**With frames:**
```
Frame 1: "Message1" (length=8)
Frame 2: "Message2" (length=8)
Frame 3: "Message3" (length=8)

Clear boundaries! ✅
```

### 3. Control Messages

```
Text/Binary = Application data
Ping/Pong = Connection health
Close = Graceful shutdown

Mix them freely without confusion!
```

---

## Part 9: Common Frame Scenarios

### Scenario 1: Sending a Subscription

```kotlin
// We call:
val query = """
    subscription {
        blocks { height }
    }
"""
sendMessage(GraphQLWebSocketMessage.Subscribe(id="sub_1", query=query))

// Behind the scenes:

1. Serialize to JSON:
   '{"type":"subscribe","id":"sub_1","payload":{...}}'

2. Create Frame.Text:
   Frame.Text(text='{"type":"subscribe",...}')

3. Ktor encodes frame:
   [Header: FIN=1, OpCode=0x1, Length=123]
   [Payload: {"type":"subscribe",...}]

4. Send over network:
   [Bytes transmitted via TCP]

5. Server receives frame:
   [Decodes header and payload]

6. Server processes subscription:
   [Registers subscription with ID "sub_1"]

7. Server sends acknowledgment:
   Frame.Text('{"type":"complete","id":"sub_1"}')
```

### Scenario 2: Receiving Subscription Data

```
1. Server has new data for subscription "sub_1"

2. Server creates frame:
   Frame.Text('{"type":"next","id":"sub_1","payload":{...}}')

3. Server sends bytes over network

4. Ktor receives and decodes:
   Frame.Text with payload

5. Puts in incoming channel:
   session.incoming.send(frame)

6. Our coroutine wakes up:
   for (frame in session.incoming) {
       // frame is Frame.Text
   }

7. We extract text:
   val text = frame.readText()

8. Parse message:
   val msg = parseMessage(text)
   // GraphQLWebSocketMessage.Next(id="sub_1", payload={...})

9. Route to subscription:
   val channel = activeSubscriptions["sub_1"]
   channel.send(msg.payload)

10. User's Flow receives data:
    subscribe(query).collect { data ->
        println("New block: $data")
    }
```

### Scenario 3: Connection Closed Unexpectedly

```
1. Network drops (WiFi disconnected)

2. TCP connection breaks

3. Ktor detects error while reading frame

4. Ktor closes incoming channel:
   session.incoming.close(exception)

5. Our for-loop exits:
   for (frame in session.incoming) {
       // Loop terminates when channel closes
   }

6. Our code detects closure:
   connected.set(false)
   activeSubscriptions.values.forEach { it.close() }

7. User's Flow completes:
   subscribe(query).collect { data ->
       // No more data, Flow completes
   }
```

---

## Part 10: Frame Size Limits

### Maximum Frame Size

```kotlin
HttpClient(CIO) {
    install(WebSockets) {
        maxFrameSize = Long.MAX_VALUE  // Default: unlimited
    }
}
```

**Why limit frame size?**
- Memory protection (prevent sending 1GB frame)
- Latency (small frames = more responsive)
- Fairness (don't hog connection with huge frame)

**Our GraphQL messages are small:**
```
Typical frame sizes:
- connection_init: ~50 bytes
- subscription query: 100-500 bytes
- Result payload: 1KB-10KB
- Ping/Pong: 0-125 bytes

Maximum we'd see: ~50KB (large transaction data)
```

---

## Part 11: Debugging Frames

### Seeing Raw Frame Data

```kotlin
// Add logging interceptor:
HttpClient(CIO) {
    install(WebSockets) {
        contentConverter = object : WebsocketContentConverter {
            override suspend fun serialize(...) {
                println("→ Sending frame: $data")
                // ...
            }
            override suspend fun deserialize(...) {
                println("← Received frame: $data")
                // ...
            }
        }
    }
}
```

### Frame Inspection

```kotlin
when (frame) {
    is Frame.Text -> {
        println("Frame Type: Text")
        println("Fin: ${frame.fin}")
        println("Data: ${frame.readText()}")
    }
    is Frame.Close -> {
        val reason = frame.readReason()
        println("Frame Type: Close")
        println("Code: ${reason?.code}")
        println("Message: ${reason?.message}")
    }
    is Frame.Binary -> {
        println("Frame Type: Binary")
        println("Size: ${frame.data.size} bytes")
    }
}
```

---

## Summary: Frame Hierarchy

```
WebSocket Connection (TCP socket)
    │
    └─▶ Stream of Frames
            │
            ├─▶ Text Frame (OpCode 0x1)
            │       └─▶ UTF-8 string payload
            │           └─▶ JSON in our case
            │               └─▶ GraphQL-WS message
            │
            ├─▶ Binary Frame (OpCode 0x2)
            │       └─▶ Raw bytes
            │
            ├─▶ Close Frame (OpCode 0x8)
            │       └─▶ Reason code + message
            │
            ├─▶ Ping Frame (OpCode 0x9)
            │       └─▶ Optional payload
            │
            └─▶ Pong Frame (OpCode 0xA)
                    └─▶ Echo of ping payload
```

**In our application:**
```
Midnight Indexer Server
    ↓ (WebSocket frames)
Ktor Client
    ↓ (Frame objects)
session.incoming Channel
    ↓ (Frame.Text, Frame.Close, etc.)
Our message processing loop
    ↓ (frame.readText())
JSON string
    ↓ (JSON.decodeFromString)
GraphQLWebSocketMessage
    ↓ (handleMessage)
activeSubscriptions[id] Channel
    ↓ (Flow)
User code
```

---

## Quick Reference

**What is a frame?**
→ Atomic unit of WebSocket communication (like a packet)

**What types exist?**
→ Text, Binary, Close, Ping, Pong, Continuation

**How are messages sent?**
→ Application data → JSON → String → Frame.Text → Bytes → Network

**How are messages received?**
→ Network → Bytes → Frame.Text → String → JSON → Application data

**Who handles frames?**
→ Ktor encodes/decodes automatically

**What do we see?**
→ Clean Frame objects: `Frame.Text("...")`, `Frame.Close(reason)`

**Why channels?**
→ Decouple frame receiving (network) from frame processing (our code)

---

**Now the crash course makes sense! 🎉**

Frames are the protocol-level envelopes that carry our messages. Channels are how Ktor delivers those envelopes to our code. Atomics keep everything thread-safe when multiple coroutines are involved.
