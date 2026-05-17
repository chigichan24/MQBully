# Case 01: Reflect `MessageQueue.mMessages`

## 一言で

`MessageQueue` の内部フィールド `mMessages` をリフレクションで読み取る。
Android 16 では linked-list の head である `Message` オブジェクトが取れる。
Android 17 では **`null`** しか返らない。

## 使う API

- **内部**: `MessageQueue.mMessages` (`@UnsupportedAppUsage`、light greylist)
- `Class.getDeclaredField("mMessages")` + `Field.setAccessible(true)` + `Field.get(queue)`

## コード抜粋

```kotlin
handler.postDelayed(parkedRunnable, 100_000L)   // 1 件積む

val queue = Looper.myQueue()
val field = MessageQueue::class.java.getDeclaredField("mMessages")
field.isAccessible = true
val value: Any? = field.get(queue)
// value は legacy なら Message オブジェクト、lock-free なら常に null
```

## 再現

```bash
./scripts/run-attack.sh 1
```

## 実機計測 (Pixel 10 / Android 17)

| | Legacy (`reset`) | Lock-free (`enable`) |
|---|---|---|
| `mMessages` の戻り値 | `android.os.Message@7586131` | `null` |

## 何が起きてるか

公式 ([behavior change guide](https://developer.android.com/about/versions/17/changes/messagequeue)):

> Android 17 keeps the `mMessages` field, but in the new implementation this field is **always null**, regardless of whether there are messages in the queue.

新実装 (DeliQueue) は内部データを **Treiber stack + min-heap** に置いている。
`mMessages` というフィールドは ABI 互換のために残ってるだけで、誰も書き込まない。

これが Espresso 3.6 以前 / Robolectric `@LooperMode(LEGACY)` を壊した最大の原因。

## 関連

- [Case 03: Message.next を辿る](./case-03-walk-message-chain.md) — このフィールドを起点に linked-list を traverse する古典手法
- [Case 04: `mMessages = null` を書き込む](./case-04-force-set-mmessages-null.md) — read だけでなく write したらどうなるか
