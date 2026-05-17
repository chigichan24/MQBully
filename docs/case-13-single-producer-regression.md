# Case 13: Single-producer regression probe

## 一言で

公式 blog が **「branchless approach は分岐が予測可能な場合かえって遅くなる」** と書いていたので、
それを最も突きやすい「単一 producer、idle consumer」シナリオで計測した。
**結論: 新実装が全パーセンタイルで勝つ。regression は見つからず**。

## 使う API

- **公開**: `Handler.post`, `HandlerThread`

## コード抜粋

```kotlin
val ht = HandlerThread("MQBully-Single").apply { start() }
val bh = Handler(ht.looper)
val noop = Runnable {}

repeat(20_000) { bh.post(noop) }  // warm-up
Thread.sleep(300)

val iters = 200_000
val latencies = LongArray(iters)
for (i in 0 until iters) {
    val t0 = System.nanoTime()
    bh.post(noop)
    val t1 = System.nanoTime()
    latencies[i] = t1 - t0
}
```

## 再現

```bash
./scripts/run-attack.sh 13 8
```

## 実機計測 (200,000 iters)

| 指標 | Legacy | Lock-free |
|---|---|---|
| avg | 836 ns | **629 ns** |
| p50 | 755 ns | **547 ns** |
| p90 | 990 ns | **703 ns** |
| p99 | 2,240 ns | **1,901 ns** |
| p999 | 11,589 ns | **6,979 ns** |
| max | 1,058,125 ns | **362,109 ns** |
| wall | 199 ms | **150 ms** |
| throughput | 1,000,289 posts/sec | **1,327,510 posts/sec** |

→ **すべての指標で Lock-free が勝つ**。

## 何が起きてるか

公式 blog の警告:

> Branchless approaches generally require doing work that will be thrown away,
> and if the branch is predictable most of the time, that wasted work can slow your code down.

理論上は単一 producer で branch が予測可能な状況だと branch-free 比較がオーバーヘッドになるはず。
だが Pixel 10 上の実測では、CAS push のコストが synchronized monitor 取得より十分軽いため、
total としては Lock-free が勝つ。

## ユーザーへの示唆

「軽負荷ユースケースなら旧実装の方が速い」という仮説は **否定された**。
Lock-free は重負荷だけでなく軽負荷でも速い (= 公式の "competitive average performance" は控えめな表現で、実際は競争どころか勝ち)。

## 関連

- [Case 09: 重負荷時の同じ計測](./case-09-multi-producer-throughput.md)
