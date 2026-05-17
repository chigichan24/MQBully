# Case 07: Bulk cleanup latency

## 一言で

10,000 件の `postDelayed` をした後、`removeCallbacksAndMessages(null)` で
全部削除するのに何 ms かかるかを比較。新実装の方が **約 2 倍速い**。

## 使う API

- **公開**: `Handler.postDelayed`, `Handler.removeCallbacksAndMessages(null)`

## コード抜粋

```kotlin
val sentinel = Runnable { /* never fires within test horizon */ }
for (i in 0 until 10_000) handler.postDelayed(sentinel, 1_000_000L + i)

val startNs = System.nanoTime()
handler.removeCallbacksAndMessages(null)
val elapsedUs = (System.nanoTime() - startNs) / 1_000
```

## 再現

```bash
./scripts/run-attack.sh 7
```

## 実機計測

| | Legacy | Lock-free |
|---|---|---|
| 10k 件 → 全 remove | **3,296 µs (3 ms)** | **1,569 µs (1 ms)** |

## 何が起きてるか

旧実装: linked-list を head から線形に walk して全 unlink、O(N)。
新実装: 各 Message が **back-pointer**を持つので、削除自体は O(1) per element。
ただし `removeCallbacksAndMessages(null)` は全削除なので結局は O(N) の walk が必要。

公式 blog より:

> back-pointers are added during traversal to enable O(1) removal from arbitrary stack positions.

なので大幅な改善ではないが、Treiber stack walk は monitor 取得を伴わないぶん速い。
**重要な副作用**: 削除された Message は **tombstone として残る** (logical removal)。
これが [Case 12](./case-12-tombstone-memory-hold.md) / [Case 14](./case-14-tombstone-oom.md) で観測される **メモリリーク**につながる。

## 関連

- [Case 12: tombstone memory hold](./case-12-tombstone-memory-hold.md)
- [Case 14: tombstone → OOM](./case-14-tombstone-oom.md)
