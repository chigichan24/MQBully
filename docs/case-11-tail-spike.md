# Case 11: Deliberate tail spike (CAS retry storm)

## 一言で

32 noise thread と 1 victim thread を CountDownLatch で **同時 wake** して
CAS retry 嵐を意図的に発生させる。それを 50 round 繰り返して、
victim の post() レイテンシ分布を集計する。

[Case 10](./case-10-fairness-variance.md) で出た `max=382ms` が
再現可能か、それとも偶発 noise かを判別する目的。

**結論: 偶発 noise だった。DeliQueue は CAS 嵐でも安定**。

## 使う API

- **公開**: `Handler.post`, `HandlerThread`, `CountDownLatch`

## コード抜粋

```kotlin
for (round in 0 until 50) {
    val startGate = CountDownLatch(1)
    val readyGate = CountDownLatch(noiseThreads + 1)
    val doneGate = CountDownLatch(noiseThreads + 1)
    val victimLatency = AtomicLong(-1)

    for (n in 0 until noiseThreads) {
        Thread {
            readyGate.countDown(); startGate.await()
            repeat(burstSize) { benchHandler.post(noop) }
            doneGate.countDown()
        }.start()
    }
    Thread {  // victim
        readyGate.countDown(); startGate.await()
        val t0 = System.nanoTime()
        benchHandler.post(noop)
        victimLatency.set(System.nanoTime() - t0)
        doneGate.countDown()
    }.start()

    readyGate.await()
    startGate.countDown()
    doneGate.await()
    // → round の victim latency を集計
}
```

## 再現

```bash
./scripts/run-attack.sh 11 12
```

## 実機計測 (50 round)

| 計測 | Legacy run1 | Lock-free run1 | Lock-free run2 | Lock-free run3 |
|---|---|---|---|---|
| victim min | 5,235 ns | 2,839 ns | 3,282 ns | 2,552 ns |
| victim p50 | 184,635 ns | 123,828 ns | 114,505 ns | 80,417 ns |
| victim p99 | **9.5 ms** | 0.54 ms | 0.45 ms | 0.63 ms |
| victim **max** | **9.5 ms** | **0.54 ms** | **0.45 ms** | **0.63 ms** |

→ Lock-free が **tail まで含めて 3 回全勝**。Case 10 で観測した 382ms は再現せず、CAS retry 嵐でも 0.6ms 程度しか詰まらない。

## 何が起きてるか

DeliQueue の producer 経路:

1. 新しい head 候補を準備 (`new Message`)
2. 現在の head pointer を read
3. CAS で `(head, new)` を試行
4. 失敗したら 2. に戻る

critical section が極短なので、リトライしてもすぐ通る。
32 thread + victim が同時に CAS しても、CPU サイクルレベルの遅延しか発生しない。

旧実装の `synchronized(this)` は monitor を取るので、競合時はカーネル/JVM の待ち行列に入る。
そのため tail が伸びる。

## ユーザーへの示唆

CAS リトライ嵐を恣意的に作っても DeliQueue は折れない。
**ただし [Case 14](./case-14-tombstone-oom.md) のメモリリークは別軸の弱点で、こちらは確実に再現する**。

## 関連

- [Case 10: fairness + variance (元の noise)](./case-10-fairness-variance.md)
- [Case 14: 本当の弱点](./case-14-tombstone-oom.md)
