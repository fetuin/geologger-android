# GeoLogger

歩いた場所を Android 端末に記録し、自分のサーバーへ送り、CSV / GPX / GeoJSON で
書き出せる位置情報ロガー。Kotlin + Jetpack Compose の素の Android アプリで、
Google Play 開発者サービスは使っていません。

**[→ 配布ページ（使い方とダウンロード）](https://fetuin.github.io/geologger-android/)**
・ [最新の APK](https://github.com/fetuin/geologger-android/releases/latest)

Pebble ウォッチフェイス「GPS Trail」の送信部分を Android 単体で使えるように
作り直したものです（ウォッチフェイスの時計表示はありません）。

| | |
|---|---|
| 対応 | Android 8.0 以上（minSdk 26 / targetSdk 36） |
| 蓄積 | 端末内の SQLite。数十万点ためても耐える設計 |
| 送信 | 自分のサーバーへ POST。圏外ぶんは溜めて後で送る |
| 書き出し | CSV / GPX / GeoJSON、共有またはダウンロードへ保存 |
| 地図 | 記録した座標だけで軌跡を描画（タイルを読まないのでオフライン可） |

## ビルド

必要なのは JDK 17 以上（JDK 21 で確認）と Android SDK（platform 36 / build-tools 36.0.0）。
Gradle はラッパーが用意します。

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew testDebugUnitTest     # 単体テスト 18 件
./gradlew assembleDebug         # app/build/outputs/apk/debug/
./gradlew assembleRelease       # 署名設定があれば署名済みで出る
./gradlew lintRelease           # app/build/reports/lint-results-release.html
```

### 署名について

`assembleRelease` は、リポジトリ直下に `keystore.properties` があればそれを読んで署名します。

```properties
storeFile=geologger-release.jks
storePassword=...
keyAlias=geologger
keyPassword=...
```

**鍵ファイルと `keystore.properties` はリポジトリに含めていません**（`.gitignore` 済み）。
公開リポジトリに署名鍵を置くと、第三者があなたのアプリを装った更新版に署名できてしまうためです。
手元で保管してください。鍵を失うと、既存のインストールへ上書き更新できなくなります。

無い場合は未署名でビルドされます（`assembleDebug` は常に debug 鍵で署名されます）。

### 依存バージョン

AGP 8.13.2 / Kotlin 2.2.20 / Compose BOM 2025.09.00 / compileSdk 36 (Android 16) に
固定しています。これより新しい Compose BOM は **compileSdk 37 と AGP 9 系** を要求するので、
上げるときは AGP・Gradle・compileSdk をまとめて更新してください。
targetSdk 36 のアプリは Android 17 端末でもそのまま動きます。

`settings.gradle.kts` では Maven Central のミラー（Google 運用）を先に引くようにしています。
本家が 429 を返す回線で詰まったときの保険で、不要なら消して構いません。

### アイコン

`tools/make_icons.py` で各密度の前景 PNG と配布ページ用の見本を生成します。
アダプティブアイコンの前景に文字（usepocket）を入れたいので、ベクターではなく PNG です。

```bash
python3 tools/make_icons.py
```

## サーバーとのやりとり

```
POST application/x-www-form-urlencoded

1件  mode=add&id=..&pw=..&lat=35.680000&lon=139.761600[&c=A1B][&t=1791357596]
一括  mode=add&id=..&pw=..&d=35.680000,139.761600,A1B[,1791357596]&d=...
```

- `note` は送りません（受け側が 400 を返す仕様のため）
- `t`（測位時刻）は **Settings → Sending → Send timestamps** で切り替え。
  サーバーが未対応のうちに on にすると全送信が 400 で失敗します
- リダイレクトは追いません（`instanceFollowRedirects = false`）。`http://` だと 301 で
  `https://` に飛ばされ、POST が GET に変わって本文が失われるためです。URL は `https://` で
- 応答が 2xx なら送信済みとして記録。4xx は「直しても直らない」と判断して自動再送を止めます
  （設定を直すか「Upload now」で再開）。5xx や通信エラーは次の機会に自動で再送します

### Perl で受ける場合の注意

分類コードを空にすると、`t` を送らない設定では `d` の末尾がカンマになります
(`35.680000,139.761600,`)。**Perl の `split` は末尾の空フィールドを捨てる**ので、
項目数チェックが誤作動します。`split /,/, $d, -1` のように limit に `-1` を付けてください。

## 送信の方式

| | Send every fix | Send in batches |
|---|---|---|
| 通信回数 | 点ごとに1回 | 指定数ごとに1回 |
| サーバー上の時刻 | 実測時刻に近い | `t` を送らないと全点が同じ時刻になる |
| 電池 | やや食う | 有利 |

まとめ送りは **指定数（5〜50）に達したときだけ** 送ります。停止中は点が溜まらないので、
待ち続けて電池を無駄にすることはありません。その代わり、歩いて途中でやめると半端なぶん
（最大で設定数−1）は次に動くまで端末に残ります。すぐ送りたいときは Log タブの
「Upload now」を押してください。まとめ送りから毎回送信に切り替えたときも、
溜まっていたぶんはその場で送られます。

## 移動判定

両方式に共通で、**最後に記録した点から設定距離（既定30m）以上離れるまでは記録も送信も
しません。**

基準を「直前の測位」ではなく「最後に記録した点」にしているのが要点です。直前と比べる方式だと、
閾値未満の移動を繰り返したときに永遠に基準を超えず、何キロ歩いても1点も残りません。
この違いは単体テストで対比を取っています（29m刻みで145m進んだとき、正しい実装は3点、
直前比較だと1点）。

## 位置情報の取り方

Google Play 開発者サービスは使わず、プラットフォームの `LocationManager` で済ませています。
依存が増えず、GMS の無い端末でも動きます。

- Android 12 以降は `FUSED_PROVIDER`
- それ未満は `GPS_PROVIDER` と `NETWORK_PROVIDER` の両方を受ける
  （High accuracy を on にすると GPS のみ）

記録は前景サービス（`foregroundServiceType="location"`）で行い、`START_STICKY` なので
OS に止められても復帰します。

## ファイル構成

```
app/src/main/java/com/usepocket/geologger/
  MainActivity.kt            画面の土台、権限の要求、各操作の配線
  geo/Geo.kt                 距離・書式・移動判定          ← 単体テスト対象
  net/Protocol.kt            送信本文の組み立て・応答判定   ← 単体テスト対象
  net/Uploader.kt            送信キューを流す（HttpURLConnection）
  data/PointDb.kt            SQLite（SQLiteOpenHelper）
  data/Prefs.kt              設定（SharedPreferences）
  service/LocationService.kt 前景サービスと測位の取り込み
  export/Formats.kt          CSV / GPX / GeoJSON の整形    ← 単体テスト対象
  export/Exporter.kt         書き出し・共有・ダウンロード保存
  ui/                        Compose の画面（Log / Map / Export / Settings）
app/src/test/                単体テスト 18 件
docs/                        配布ページ（GitHub Pages）
tools/make_icons.py          アイコン生成
```

## 容量の目安

1点あたり約 50 バイト（索引込みで 80 バイト程度）。1分間隔で歩き続けても
1日あたり 1,440 点＝約 115KB なので、数年ぶん溜めても数十MBです。
Settings → Stored data から「30日より古い送信済みの点」をまとめて削除できます。

## 既知の制約

- メーカー独自の省電力機能（Xiaomi、Huawei など）が前景サービスを止めることがあります。
  Settings の一番下から「電池最適化の対象外」に設定できます
- 軌跡の描画は新しい 1,200 点まで。それ以上は古いぶんを省いて描きます
- 認証は ID とパスワードを本文に入れて送るだけです。HTTPS なので通信路では読まれませんが、
  端末内の設定には平文で入ります
- リリースビルドは難読化を切っています（縮小と最適化は有効）。不具合時に
  スタックトレースのクラス名がそのまま読めるようにするためです

## ライセンス

MIT License（[LICENSE](LICENSE)）

作: [usePocket](https://usepocket.com)
