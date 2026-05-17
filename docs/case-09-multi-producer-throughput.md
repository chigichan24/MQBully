# Case 09: N-producer `post()` throughput

## 一言で

8 producer thread × 5,000 posts/producer = 40,000 件を **同時投入** した時の wall time を計測。
**Lock-free は Legacy の約 219 倍速い**。

## 使う API

- **公開**: `Handler.post`, `java.util.concurrent.CountDownLatch`

## コード抜粋

```kotlin
val producers = 8
val perProducer = 5_000
val startGate = CountDownLatch(1)
val doneGate = CountDownLatch(producers)
val mainHandler = Handler(Looper.getMainLooper())
val noop = Runnable {}

for (tid in 0 until producers) {
    Thread {
        startGate.await()
        repeat(perProducer) { mainHandler.post(noop) }
        doneGate.countDown()
    }.start()
}
val wallStartNs = System.nanoTime()
startGate.countDown()
doneGate.await()
val wallElapsedNs = System.nanoTime() - wallStartNs
```

## 再現

```bash
./scripts/run-attack.sh 9 8
```

## 実機計測

| 指標 | Legacy | Lock-free | 倍率 |
|---|---|---|---|
| Wall time | **3,955 ms** | **18 ms** | **約 219 倍速** |
| Throughput (wall basis) | 10,112 posts/sec | **2,162,183 posts/sec** | 約 214 倍 |
| avg post() per call (wall view) | 99 µs | **462 ns** | 約 214 倍 |
| per-thread max elapsed | 3,955 ms | 18 ms | 約 220 倍 |

公式 blog は「最大 5,000 倍」を謳ってるが、Pixel 10 の手元計測でも **200 倍超**は出る。

## 何が起きてるか

旧実装の `enqueueMessage` は `synchronized(this)` を取る。
8 producer がこの monitor を奪い合うので、**完全に直列化**される。

新実装の producer は Treiber stack に **CAS で push** するだけ。
公式 blog より:

> A lock-free stack. Any thread can push new Messages here without contention.

```text
Producer-A ----push----> [Treiber stack head] <----push---- Producer-B
                              ^
                              | (Looper が bulk transfer)
                              v
                         [min-heap (Looper 専有)]
```

producer 同士は monitor を取らないので並列に走れる。

## 関連

- [Case 02: 外部 synchronized](./case-02-external-synchronized.md) — 同じく monitor 撤廃の恩恵
- [Case 13: 単一 producer](./case-13-single-producer-regression.md) — 軽負荷時はどうなる
