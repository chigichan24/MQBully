# Case 08: `postAtFrontOfQueue` ordering

## 一言で

`postAtFrontOfQueue(r)` を **連続 N 回** 呼んだ時、dispatch される順序は
旧実装では「後入れ先出し (LIFO)」になる。新実装でもまったく同じ LIFO が再現される。

## 使う API

- **公開**: `Handler.postAtFrontOfQueue(Runnable)`

## コード抜粋

```kotlin
val n = 8
val observed = Collections.synchronizedList(ArrayList<Int>(n))
for (i in 0 until n) {
    val idx = i
    handler.postAtFrontOfQueue { observed.add(idx) }
}
handler.postDelayed({
    // observed の中身を確認
}, 50L)
```

## 再現

```bash
./scripts/run-attack.sh 8
```

## 実機計測

| | Legacy | Lock-free |
|---|---|---|
| dispatch 順 | `[7, 6, 5, 4, 3, 2, 1, 0]` | `[7, 6, 5, 4, 3, 2, 1, 0]` |
| LIFO 維持 | `true` | `true` |

## 何が起きてるか

旧実装の `postAtFrontOfQueue` は内部的に `enqueueMessage(msg, 0)` を呼ぶ。
linked-list の head に挿入されるので、5 回連続で呼ぶと **後入れが先頭** になる。

新実装は heap だが、`when=0` (もしくは現在時刻) が共通なので
[Case 06](./case-06-same-when-order.md) と同じく **挿入順** で tie-break される。
そのため `postAtFrontOfQueue` の連続呼び出しは、**LIFO に観測される**よう挿入順が逆向きに記録される。

公式 blog より:

> Secondary queue order is by insert sequence.

つまり「先頭に置く」操作を 5 回繰り返したら、論理的には:

1. 1 回目: [r0]
2. 2 回目: [r1, r0]  ← r1 が前
3. 3 回目: [r2, r1, r0]
4. ...

旧実装も新実装もこの論理に従う。違うのはデータ構造の表現だけ。

## ユーザーへの示唆

`postAtFrontOfQueue` を多用するコード (アニメーション系で割り込み描画など) は **安全**。

## 関連

- [Case 06: same-when 順序](./case-06-same-when-order.md)
