# Case 03: Walk Message chain via `.next`

## 一言で

`MessageQueue.mMessages` から `Message.next` フィールドを辿って、
pending message を全部 traverse する古典テクニック。
旧実装ではぞろぞろ取れる、新実装では `mMessages == null` で 0 件。

## 使う API

- **内部**: `MessageQueue.mMessages`, `Message.next`, `Message.when`
- これらは Espresso/Robolectric が UI idle 判定に使っていた経路

## コード抜粋

```kotlin
val mMessagesField = MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
val nextField     = Message::class.java.getDeclaredField("next").apply { isAccessible = true }
val whenField     = Message::class.java.getDeclaredField("when").apply { isAccessible = true }

var current = mMessagesField.get(queue) as? Message
var count = 0
while (current != null && count < 16) {
    val whenMs = whenField.getLong(current)
    // → "deliver in N ms"
    current = nextField.get(current) as? Message
    count++
}
```

## 再現

```bash
./scripts/run-attack.sh 3
```

## 実機計測 (Pixel 10 / Android 17)

事前に 3 件 `postDelayed` した状態で:

| | Legacy | Lock-free |
|---|---|---|
| 辿れた件数 | **11** (システム自身の Choreographer message なども含む) | **0** |

実例 (Legacy):

```
Walked 11 pending messages from mMessages head.
  #0: deliver in 299ms
  #1: deliver in 395ms
  #2: deliver in 1228ms
  #3: deliver in 2635ms
  #4: deliver in 2635ms
  #5: deliver in 99799ms
  #6: deliver in 110000ms
  #7: deliver in 210000ms
  #8: deliver in 310000ms
```

## 何が起きてるか

旧実装の `MessageQueue` は **単方向 linked-list**。`mMessages` がリストの head で、各 `Message` が `next` で次を指す。
新実装は heap (配列) なので linked-list 自体が存在しない。
`Message.next` フィールドは ABI 互換のため残るが、`mMessages` が常に `null` なので **辿り始めの起点がない**。

これを使ってた典型ライブラリ:

- **Espresso 3.6 以前**: pending message を traverse して "UI idle" を判定 → 3.7.0 で `TestLooperManager` 経由に移行
- **Robolectric `@LooperMode(LEGACY)`**: 同様にチェーンを直接覗いていた → 4.17 で `@LooperMode(PAUSED)` 必須化

## 関連

- [Case 01: mMessages を read](./case-01-reflect-mmessages.md)
- [Case 04: mMessages を write](./case-04-force-set-mmessages-null.md) — 読むだけでなく書いたら？
