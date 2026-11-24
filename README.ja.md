<div align="center">

[English](README.md) | **日本語**

# AutoChunkLoader

**短いコマンドで、チャンクをロードしたままにする。近くに誰もいなくても。**

プラグイン用チャンクチケットでチャンクを維持する Paper プラグインです。ホッパーやレッドストーン、仕分け機が、プレイヤーから離れても止まりません。

<a href="https://papermc.io/"><img src="https://img.shields.io/badge/Paper-26.3-4b8bbe?style=flat-square" alt="Paper 26.3"></a>
<a href="https://adoptium.net/"><img src="https://img.shields.io/badge/Java-25-e8833a?style=flat-square&logo=openjdk&logoColor=white" alt="Java 25"></a>
<img src="https://img.shields.io/badge/Minecraft-Java%20Edition-62b47a?style=flat-square" alt="Minecraft Java Edition">
<img src="https://img.shields.io/badge/Languages-ja%20%7C%20en-97ca00?style=flat-square" alt="ja | en">

[Quick start](#quick-start) · [コマンド](#コマンド) · [設定](#設定) · [制限](#制限)

</div>

## 概要

今いるチャンクを `/acl add` で登録すると、削除するまでロードされ続けます。グループは複数チャンクにでき(半径、または 2 つのブロック座標の範囲)、オーバーワールド・ネザー・エンドで使えます。

## 特長

- 今いるチャンク向けの短いコマンド: `/acl add`、`/acl remove`、`/acl here`
- 半径、チャンク座標、ブロック座標 2 点(`~` 可、y は無視)、`pos1` / `pos2` で範囲を指定
- `list`・`info`・`tp`・`resize`・`expand`・`rename`。クリックできるボタン、ホバー説明、ページ送りつき
- Inspector(`/acl i`): 維持中のチャンクの外周をパーティクルで表示。自分にだけ見えます
- チケットは参照カウントで管理するので、グループが重なっても安全
- 再起動後も、すべてのワールドで復元
- 日本語と英語のメッセージ。言語ファイルは編集できます

## Quick start

Paper 26.3 と Java 25 が必要です。

1. `AutoChunkLoader-0.1.0.jar` を `plugins/` に入れて、サーバーを起動します。
2. チャンクの中で `/acl add` を実行し、`/acl here` で確かめます。
3. 外すときは `/acl remove` です。

## コマンド

`/acl` と `/autochunkloader` は同じです。

<details>
<summary>コマンド一覧</summary>

| コマンド | 内容 |
|---|---|
| `/acl here` | 今いるチャンクの状態(登録の有無・グループ・ロードレベル) |
| `/acl add [名前] [半径]` | 今いるチャンクを登録(名前を省くと自動命名) |
| `/acl add <名前> <x> <z> [半径] [ワールド]` | チャンク座標で登録 |
| `/acl add <名前> <x1> <y1> <z1> <x2> <y2> <z2> [ワールド]` | ブロック座標 2 点の範囲のチャンクをすべて登録 |
| `/acl pos1`、`/acl pos2`、`/acl add <名前> sel` | 2 点を選んでから登録 |
| `/acl remove [名前]` | 削除してチケットを外す(複数チャンクは確認あり) |
| `/acl list [ページ]` | 一覧。ワールド名はディメンションで色分け |
| `/acl info [名前]` | グループの詳細 |
| `/acl tp <名前>` | グループへ移動(別ワールドも可) |
| `/acl resize <名前> <+n/-n>` | 全方向に n チャンク広げる/縮める |
| `/acl expand [名前]` | 今いるチャンクを含むように広げる |
| `/acl rename <旧> <新>` | 名前変更 |
| `/acl inspector`、`/acl i` | 外周表示の ON/OFF |
| `/acl reload` | 設定と言語ファイルの再読み込み |

</details>

### 権限

| 権限 | 内容 | 既定 |
|---|---|---|
| `autochunkloader.use` | list・info・here・inspector・help | 全員 |
| `autochunkloader.manage` | 自分のグループの add・remove・resize・expand・rename | OP |
| `autochunkloader.tp` | tp | OP |
| `autochunkloader.admin` | 全グループの操作、reload、上限の無視 | OP |

## 設定

`plugins/AutoChunkLoader/config.yml`: `language`(`ja` / `en`)、`limits`(半径・1 グループのチャンク数・全体のチャンク数・1 人あたりのグループ数)、`inspector`(半径・高さ・色・粒の上限)、`list.page-size`。メッセージは `lang/<言語コード>.yml` を編集して変えられます。

## 制限

> [!IMPORTANT]
> チケットはチャンクをロードして動かし続けますが、バニラはランダムティックとモブ湧きを近くのプレイヤーに結びつけています。Paper 26.3 での実測(プレイヤーから約 3000 ブロック離れたチャンク、ロードレベル `ENTITY_TICKING`):
>
> - ホッパーは動き続けた。
> - 遠方の作物は育たなかった(3 分で age 0。プレイヤーの近くの同じ作物は age 7 まで成長)。
> - 遠方のグループではモブが湧かなかった(プレイヤーの近くでは湧いた)。
>
> アイテムを運ぶ装置は動きます。湧きやランダムティックに頼る農場・モブトラップは動かない見込みです。

> [!NOTE]
> 未検証: 遠方のレッドストーン時計、プラグイン起動後に読み込まれるワールド、ネザー・エンド以外の追加ワールド。
