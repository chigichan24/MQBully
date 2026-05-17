# Case 06: Same-`when` dispatch order

## 一言で

同じ `when` (uptimeMillis) で N 件を `postAtTime` した時、dispatch される順序が
新実装でも挿入順 (FIFO) を保つかを観測。**結論: 両実装ともきっちり FIFO 維持**。

## 使う API

- **公開**: `Handler.postAtTime(Runnable, uptimeMillis)`, `SystemClock.uptimeMillis()`

## コード抜粋

```kotlin
val deliverAt = SystemClock.uptimeMillis() + 300L
val observed = Collections.synchronizedList(ArrayList<Int>(50))

for (i in 0 until 50) {
    handler.postAtTime({ observed.add(i) }, deliverAt)
}

handler.postAtTime({
    val expected = (0 until 50).toList()
    val inOrder = observed == expected
    // → inOrder=true なら FIFO が保たれている
}, deliverAt + 1L)
```

## 再現

```bash
./scripts/run-attack.sh 6
```

## 実機計測

| | Legacy | Lock-free |
|---|---|---|
| dispatch 順 | `[0, 1, 2, ..., 49]` | `[0, 1, 2, ..., 49]` |
| `inOrder` 判定 | `true` | `true` |

## 何が起きてるか

公式 blog より:

> Secondary queue order is by insert sequence. If two messages were inserted with the same `when`, the one inserted first should come first in the queue.

DeliQueue の min-heap は **deadline をプライマリキー、挿入順をセカンダリキー** にソートしている。
同 `when` の Message は挿入順 (insert sequence) で tie-broken される。
そのため公開 API レベルでは挙動が変わらない。

これは AOSP チームが公開 API のセマンティクスを **執念で守った**結果。
内部実装は完全に違うのに、観測上は同じ。

## ユーザーへの示唆

- 自分のアプリで「同 `when` の post に依存」してても安全
- ただし**ドキュメントには「挿入順保証」と明記されていない**ので、依存自体は推奨されない

## 関連

- [Case 08: postAtFrontOfQueue](./case-08-postAtFrontOfQueue.md) — もう 1 つの順序系
