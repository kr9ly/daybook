# 公開 API 表面（1.0.0 凍結）

このファイルは 1.0.0 でレビュー・凍結した公開 API のシグネチャ一覧。
公開シグネチャを変更・削除する変更はこのファイルの更新を伴い、後方互換性の裁定を明示的に受ける。
追加（新メンバー・新オーバーロード）は後方互換なので追記のみでよい。
KDoc の変更は凍結対象外。
機械検査（binary-compatibility-validator / KGP abiValidation）は AGP built-in Kotlin が未対応のため導入見送り中 — 対応され次第このファイルの検査版として導入する（DESIGN.md 参照）。

## daybook（io.github.kr9ly.daybook）

```kotlin
public class DaybookOptions(
    public val multiProcess: Boolean = false,
    public val importFromSharedPreferences: Boolean = false,
)

public fun Context.getDaybookSharedPreferences(name: String, options: DaybookOptions = DaybookOptions()): SharedPreferences
public fun Context.getDefaultDaybookSharedPreferences(options: DaybookOptions = DaybookOptions()): SharedPreferences

public fun Context.importSharedPreferencesIntoDaybook(name: String, deleteSource: Boolean = false): Boolean
public fun Context.importAllSharedPreferencesIntoDaybook(deleteSource: Boolean = false): List<String>
public fun Context.exportDaybookToSharedPreferences(name: String)
public fun Context.exportAllDaybookToSharedPreferences(): List<String>

public class PreferenceProperty<T> internal constructor : ReadWriteProperty<Any?, T> {
    public val preferences: SharedPreferences
    public val key: String
    public fun get(): T
    public fun set(value: T)
    override fun getValue(thisRef: Any?, property: KProperty<*>): T
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T)
    public fun <R> map(decode: (T) -> R, encode: (R) -> T): PreferenceProperty<R>
    public fun catch(handler: (Exception) -> T): PreferenceProperty<T>
}

public fun SharedPreferences.boolean(key: String, default: Boolean): PreferenceProperty<Boolean>
public fun SharedPreferences.int(key: String, default: Int): PreferenceProperty<Int>
public fun SharedPreferences.long(key: String, default: Long): PreferenceProperty<Long>
public fun SharedPreferences.float(key: String, default: Float): PreferenceProperty<Float>
public fun SharedPreferences.string(key: String, default: String): PreferenceProperty<String>
public fun SharedPreferences.string(key: String): PreferenceProperty<String?>
public fun SharedPreferences.stringSet(key: String, default: Set<String>): PreferenceProperty<Set<String>>
public fun SharedPreferences.stringSet(key: String): PreferenceProperty<Set<String>?>
```

凍結対象外（@RequiresOptIn ERROR で opt-in 必須、互換性保証なし）:

```kotlin
public annotation class DaybookInternalApi

@DaybookInternalApi
public object DaybookTestBridge {
    public fun createInMemorySharedPreferences(
        delivery: (Runnable) -> Unit,
        writeObserver: (clearRequested: Boolean, changes: Map<String, Any?>) -> Unit,
    ): SharedPreferences
}
```

## daybook-coroutines（io.github.kr9ly.daybook.coroutines）

```kotlin
public fun <T> PreferenceProperty<T>.asFlow(): Flow<T>
public fun SharedPreferences.changesAsFlow(): Flow<String?>
```

## daybook-test（io.github.kr9ly.daybook.test）

```kotlin
public class TestDaybook(packageName: String = "test") {
    public fun getSharedPreferences(name: String, multiProcess: Boolean = false): SharedPreferences
    public fun getDefaultSharedPreferences(multiProcess: Boolean = false): SharedPreferences
    public fun commits(name: String): List<RecordedCommit>
    public fun failNextWrite(name: String)
}

public class RecordedCommit(
    public val clearRequested: Boolean,
    public val changes: Map<String, Any?>,
) {
    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
    override fun toString(): String
}
```
