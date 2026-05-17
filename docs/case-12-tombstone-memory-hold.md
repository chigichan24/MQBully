# Case 12: Tombstone memory hold (1 round)

## 一言で

200,000 件の Message を `sendMessageDelayed` で積み、`removeCallbacksAndMessages(token)` で
**全削除** してから JVM heap delta を計測する。
**Legacy は drain で baseline に戻るが、Lock-free は 20MB が残ったまま**。

これが [Case 14: OOM クラッシュ](./case-14-tombstone-oom.md) の前哨戦。

## 使う API

- **公開**: `Handler.sendMessageDelayed`, `Message.obtain`, `removeCallbacksAndMessages(token)`, `Runtime.totalMemory/freeMemory`

## コード抜粋

```kotlin
val ht = HandlerThread("MQBully-Tomb").apply { start() }
val bh = Handler(ht.looper)
val token = Any()
val cycles = 200_000

System.gc(); Thread.sleep(200)
val memBefore = rt.totalMemory() - rt.freeMemory()

for (i in 0 until cycles) {
    val m = Message.obtain(bh) { /* noop */ }
    m.obj = token
    bh.sendMessageDelayed(m, 1_000_000L + i)
}
val memAfterPost = ...

bh.removeCallbacksAndMessages(token)
val memAfterRemove = ...

Thread.sleep(200); System.gc(); Thread.sleep(200)
val memAfterDrain = ...
```

## 再現

```bash
./scripts/run-attack.sh 12 6
```

## 実機計測 (200,000 件、3 回再現性確認済)

| メモリ計測点 | Legacy 3 回平均 | Lock-free 3 回平均 |
|---|---|---|
| post all 所要時間 | 約 110 ms | 約 110 ms |
| removeAll 所要時間 | 6 ms | 3 ms |
| post 直後 mem | 24,165 KB | 24,361 KB |
| removeAll 直後 mem | 24,165 KB | 24,361 KB |
| **drain後 mem** | **約 2,238 KB (= baseline に戻る)** | **約 22,750 KB (20MB 残存)** |

3 回中 3 回で **legacy はほぼ完全に解放、lock-free は約 20MB が居座る**。
これは noise ではなく構造的挙動。

## 何が起きてるか

公式 blog の "Logical removal" と "Deferred cleanup" の組み合わせ:

> **Logical removal**: the thread uses a CAS to atomically set the Message's removal flag from false to true.
> The Message remains in the data structure as evidence of its pending removal, a so-called "tombstone".
>
> **Deferred cleanup**: The actual removal from the data structure is the responsibility of the Looper thread, and is deferred until later.

`removeCallbacksAndMessages(token)` は新実装では:

1. Treiber stack / min-heap を walk
2. token が一致する Message に **tombstone マーク**を立てる
3. **物理的にはまだ stack/heap に存在し続ける**
4. Looper thread が次回 cleanup pass を回した時にやっと free される

旧実装は linked-list から物理的に unlink するので、Message オブジェクトへの参照が即切れて GC 対象になる。

## ユーザーへの示唆

`HandlerThread` の `Looper` が idle 状態を長く保つアプリ (例: background worker thread に大量 post → cancel するパターン) では **メモリが高どまり**する。
このシナリオを延長すると [Case 14: OOM](./case-14-tombstone-oom.md) に至る。

## 関連

- [Case 14: 同じ検証を 40 round 連続 → OOM](./case-14-tombstone-oom.md)
- [Case 07: bulk cleanup latency](./case-07-bulk-cleanup-latency.md)
