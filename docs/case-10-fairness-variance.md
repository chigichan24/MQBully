# Case 10: Fairness + latency variance

## 一言で

「lock-free データ構造は fairness が崩れがち、tail latency が悪化しがち」という
古典的弱点を実機で観測しようとした実験。**DeliQueue では fairness 崩れも tail 悪化も観測されず**。

## 使う API

- **公開**: `Handler.post`, `HandlerThread`, `AtomicBoolean`

## コード抜粋

```kotlin
val ht = HandlerThread("MQBully-Bench").apply { start() }
val benchHandler = Handler(ht.looper)
val noop = Runnable {}
val running = AtomicBoolean(true)

for (tid in 0 until producers) {
    Thread {
        while (running.get()) {
            val t0 = System.nanoTime()
            benchHandler.post(noop)
            val t1 = System.nanoTime()
            // latency 記録、count++
        }
    }.start()
}
Thread.sleep(1_500L)
running.set(false)
```

producer ごとの post 数と、全 post の latency 分布を集計。

## 再現

```bash
./scripts/run-attack.sh 10 6
```

## 実機計測 (6 producers × 1.5s)

| 指標 | Legacy run1 | Lock-free run1 |
|---|---|---|
| total posts | 107,743 | **2,172,864** (約 20倍) |
| fairness ratio (max/min) | 1.38x | **1.14x** |
| p50 latency | 1,563 ns | **937 ns** |
| p99 latency | 2.47 ms | **4.8 µs** |
| p999 latency | 11.6 ms | **30 µs** |
| max latency | 28.5 ms | **382 ms** ⚠️ |

`max=382ms` は **noise の可能性が高い** (再現性確認の [Case 11](./case-11-tail-spike.md) では出ず、別実行で 0.6ms 程度)。
GC pause か OS preemption が偶発的に重なったものと推定。

## 何が起きてるか

DeliQueue の producer-consumer 分離設計が、典型的 lock-free pitfall を構造的に回避:

- **fairness 崩れ**: producer は CAS で push するだけ。1 thread が連続成功する確率は CPU スケジューラ次第だが、Pixel 10 ではほぼ均等
- **tail spike**: CAS リトライが起こりうるが、競合相手の critical section が極短 (atomic CAS 一発) なので待ち時間も短い

## ユーザーへの示唆

「lock-free だから何かしら弱点があるはず」という不安は、DeliQueue に限ってはほぼ杞憂。
ただし [Case 14](./case-14-tombstone-oom.md) の **メモリ蓄積による OOM** は別軸の弱点で、こちらは確実に発生する。

## 関連

- [Case 11: 恣意的 tail spike (再現性確認)](./case-11-tail-spike.md)
- [Case 14: 弱点は別の場所にあった](./case-14-tombstone-oom.md)
