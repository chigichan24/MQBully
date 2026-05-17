# Case 04: Force-set `mMessages = null`

## 一言で

`MessageQueue.mMessages` を**リフレクションで `null` に上書き**する。
旧実装では linked-list の head が消えるので**直後から main Looper が完全死亡**する。
新実装は `mMessages` を内部で使ってないので、書き換えても**完全に無視**される。

これが本リポジトリで最も「**Android 16 で動いて 17 で動かない**」を直球で示す検証ケース。

## 使う API

- **内部**: `Field.set(queue, null)` で `mMessages` を上書き
- 観測には `Handler.postDelayed` + `AtomicInteger`

## コード抜粋

```kotlin
val fired = AtomicInteger(0)

// 5 件の victim を 400-800ms 後に発火するよう積む
repeat(5) { i ->
    handler.postDelayed({ fired.incrementAndGet() }, 400L + i * 100L)
}

// 50ms 後に mMessages を破壊
handler.postDelayed({
    val field = MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
    field.set(queue, null)
}, 50L)

// BG-watchdog で 2.5s 後に victim 配信数を観測
Thread {
    Thread.sleep(2_500L)
    Log.i(TAG, "victims fired = ${fired.get()} / 5")
}.start()
```

main thread が死ぬので、`handler.postDelayed` 内のサマリーログは出ない。
**BG-watchdog から見るのがミソ**。

## 再現

```bash
./scripts/run-attack.sh 4
```

## 実機計測 (Pixel 10 / Android 17)

| | Legacy | Lock-free |
|---|---|---|
| `before set` の `mMessages` | `Message` オブジェクト | `null` (もともと) |
| `after set` の `mMessages` | `null` | `null` |
| **victim 配信数** (BG 観測) | **0 / 5** | **5 / 5** |
| main Looper の状態 | **死亡** (BG-watchdog のみ生存) | 平常運転 |

実例:

```
LEGACY:
  [attack4] before set=Message, after set=null
  [attack4] (BG-watchdog) Victims fired: 0 / 5 → main Looper is dead

LOCK-FREE:
  [attack4] before set=null, after set=null
  [attack4] victim 0 fired (cumulative=1)
  [attack4] victim 1 fired (cumulative=2)
  ...
  [attack4] (BG-watchdog) Victims fired: 5 / 5
```

## 何が起きてるか

旧実装の `MessageQueue.next()` は `mMessages` を起点に linked-list を消費して次の `Message` を返す。
`mMessages = null` にすると next() は「もう待つしかない」と判断して `nativeWake` を待ち続ける。
そして既に enqueue されていた victim 5 件は孤児 (orphan) になり、永久に dispatch されない。

新実装は producer の push 先が Treiber stack、consumer の pop 元が min-heap。
`mMessages` を書き換えても **内部状態が一切変わらない**ので何の害もない。

## 観察される非対称性

「Android 17 にしたらクラッシュも止まらないし、なんとなく動くんですよね」と Espresso 系の OSS が泣いた理由が、この型の操作に対する **挙動の非対称性** にある。
旧では「壊れて動かなくなる」が観測可能、新では「内部状態に触れず無効化される」。
壊そうとして壊せない → 静かに無効化される、というほうが実は怖い。

## 関連

- [Case 01: read だけ](./case-01-reflect-mmessages.md)
- [Case 03: チェーン辿り](./case-03-walk-message-chain.md)
