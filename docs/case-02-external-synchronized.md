# Case 02: External `synchronized(Looper.myQueue())`

## 一言で

`MessageQueue` インスタンスに対して **外部から `synchronized` ブロック**を掛けると、
旧実装では `enqueueMessage` 内の `synchronized(this)` と同じ monitor を奪い合うので
別スレッドからの `Handler.post()` が**ブロックされる**。
新実装は producer 側に lock を取らないので、外部 `synchronized` は何の効果も持たない。

## 使う API

- **公開** API: `Looper.getMainLooper().queue` (API 23+), `synchronized(...)`、`Handler.post`
- 内部 monitor を奪うこと自体は完全に合法な Java の挙動

## コード抜粋

```kotlin
val queue = Looper.getMainLooper().queue

// BG-A: queue の monitor を 3 秒間握る
Thread {
    synchronized(queue) { Thread.sleep(3_000L) }
}.start()

// BG-B: 200ms 後に main thread の Handler に post する
Thread {
    Thread.sleep(200L)
    val t0 = System.nanoTime()
    Handler(Looper.getMainLooper()).post { /* runs */ }
    // legacy: enqueueMessage が synchronized(this) で BG-A を待つ
    // lock-free: 関係なくスルー
}.start()
```

## 再現

```bash
./scripts/run-attack.sh 2
```

## 実機計測 (Pixel 10 / Android 17)

| | Legacy | Lock-free |
|---|---|---|
| `post() returned` (BG-B 側で計測) | **2804ms** | **0ms** |
| `post→dispatch latency` (main 側) | **2802ms** | **1ms** |

差は約 **2800 倍**。BG-A が monitor を握ってる 3 秒の間、Legacy は main thread の dispatch loop も `next()` の `synchronized(this)` で詰まる。

## 何が起きてるか

旧実装 ([Android 15 ソース](https://github.com/AndroidSDKSources/android-sdk-sources-for-api-level-30/blob/master/android/os/MessageQueue.java)):

```java
boolean enqueueMessage(Message msg, long when) {
    ...
    synchronized (this) {
        ...
    }
    return true;
}
```

`synchronized(this)` の `this` は `MessageQueue` インスタンス自体。
Java の monitor は object identity なので、外部から `synchronized(queue)` で掛けても同じ monitor を奪える。

新実装 (DeliQueue) の producer path は **Treiber stack に CAS で push** するだけで monitor を取らない。
公式 blog ("Under the hood: Android 17's lock-free MessageQueue") より:

> A lock-free stack. Any thread can push new Messages here without contention.

つまり外部からどれだけ `synchronized` でブロックしても producer の動きを止められない。

## 何が観測されているか

- Legacy: `Thread.holdsLock(queue)` を別スレッドで観測すれば `true` (BG-A が握ってる間)
- Lock-free: 内部実装に monitor がないので、`Thread.holdsLock(queue)` を観測しても producer は全員 `false` を返す

## 関連

- 公式 blog の Treiber stack 解説
- [Case 09: multi-producer throughput](./case-09-multi-producer-throughput.md) — この lock 撤廃の恩恵を数字で示す
