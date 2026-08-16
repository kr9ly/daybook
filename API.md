# 公開 API 表面（2.0.0 凍結）

このファイルは 2.0.0 でレビュー・凍結した公開 API のシグネチャ一覧。
公開シグネチャを変更・削除する変更はこのファイルの更新を伴い、後方互換性の裁定を明示的に受ける。
追加（新メンバー・新オーバーロード）は後方互換なので追記のみでよい。
KDoc の変更は凍結対象外。
機械検査（binary-compatibility-validator / KGP abiValidation）は AGP built-in Kotlin が未対応のため導入見送り中 — 対応され次第このファイルの検査版として導入する（DESIGN.md 参照）。

1.0.0 凍結分（:daybook / daybook-coroutines の Android 面 / daybook-test の 1.x 面）は 2.0.0 でもシグネチャ無変更で維持している。
本ファイルは 2.0.0 時点の全公開面を再掲した完全版で、1.0.0 版は git 履歴（v1.0.0 タグ）が正。

## daybook-core — common（io.github.kr9ly.daybook.kv）

対応ターゲット: jvm / iosArm64 / iosSimulatorArm64 / linuxX64（linuxX64 は検証用ターゲット。Android は :daybook が core の JVM 成果物を通常の Java ライブラリとして消費する）。

```kotlin
public interface Daybook {
    public val schema: DaybookSchema
    public val keys: Set<String>
    public fun getString(key: String, default: String?): String?
    public fun getStringSet(key: String, default: Set<String>?): Set<String>?
    public fun getInt(key: String, default: Int): Int
    public fun getLong(key: String, default: Long): Long
    public fun getFloat(key: String, default: Float): Float
    public fun getDouble(key: String, default: Double): Double
    public fun getBoolean(key: String, default: Boolean): Boolean
    public fun contains(key: String): Boolean
    public fun edit(block: DaybookEditor.() -> Unit)
    public fun addChangeListener(listener: DaybookChangeListener)
    public fun removeChangeListener(listener: DaybookChangeListener)

    public companion object {
        public fun open(
            directory: String,
            schema: DaybookSchema,
            configure: DaybookOpenOptions.() -> Unit = {},
        ): Daybook
    }
}

public interface DaybookEditor {
    public fun putString(key: String, value: String?)
    public fun putStringSet(key: String, value: Set<String>?)
    public fun putInt(key: String, value: Int)
    public fun putLong(key: String, value: Long)
    public fun putFloat(key: String, value: Float)
    public fun putDouble(key: String, value: Double)
    public fun putBoolean(key: String, value: Boolean)
    public fun remove(key: String)
    public fun clear()
}

public fun interface DaybookChangeListener {
    public fun onChange(key: String, newValue: Any?)
}

public class DaybookOpenOptions internal constructor() {
    public var durability: Durability   // 既定 Durability.ASYNC
    public var multiProcess: Boolean    // 既定 false
    public var migrations: List<MigrationSource>   // 既定 emptyList()
}

public enum class Durability {
    SYNC,
    ASYNC,
}

public abstract class DaybookSchema(name: String) {
    public val storeName: String
    protected fun boolean(key: String): DaybookKey<Boolean>
    protected fun int(key: String): DaybookKey<Int>
    protected fun long(key: String): DaybookKey<Long>
    protected fun float(key: String): DaybookKey<Float>
    protected fun double(key: String): DaybookKey<Double>
    protected fun string(key: String): StringKey
    protected fun stringSet(key: String): StringSetKey
}

public open class DaybookKey<T : Any> internal constructor {
    public val schema: DaybookSchema
    public val name: String
}

public class StringKey internal constructor : DaybookKey<String>
public class StringSetKey internal constructor : DaybookKey<Set<String>>

public class DaybookProperty<T> internal constructor : ReadWriteProperty<Any?, T> {
    public val daybook: Daybook
    public val key: String
    public fun get(): T
    public fun set(value: T)
    override fun getValue(thisRef: Any?, property: KProperty<*>): T
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T)
    public fun <R> map(decode: (T) -> R, encode: (R) -> T): DaybookProperty<R>
    public fun catch(handler: (Exception) -> T): DaybookProperty<T>
}

public fun <T : Any> Daybook.property(key: DaybookKey<T>, default: T): DaybookProperty<T>
public fun Daybook.property(key: StringKey): DaybookProperty<String?>
public fun Daybook.property(key: StringSetKey): DaybookProperty<Set<String>?>

public interface MigrationSource {
    public val id: String
    public fun read(environment: MigrationEnvironment): Map<String, Any>?

    public companion object {
        public fun daybook1xJournal(): MigrationSource
    }
}

public class MigrationEnvironment internal constructor(
    public val directory: String,
    public val name: String,
)

public enum class MigrationMode {
    STRICT,
    LENIENT,
}

public class MigrationException(message: String, cause: Throwable? = null) : RuntimeException
```

## daybook-core — apple のみ（io.github.kr9ly.daybook.kv）

```kotlin
public class UserDefaultsSuite private constructor {
    public val suiteName: String?

    public companion object {
        public val standard: UserDefaultsSuite
        public fun named(suiteName: String): UserDefaultsSuite
    }

    public fun boolean(key: String): UserDefaultsSourceKey<Boolean>
    public fun int(key: String): UserDefaultsSourceKey<Int>
    public fun long(key: String): UserDefaultsSourceKey<Long>
    public fun float(key: String): UserDefaultsSourceKey<Float>
    public fun double(key: String): UserDefaultsSourceKey<Double>
    public fun string(key: String): UserDefaultsSourceKey<String>
    public fun stringSet(key: String): UserDefaultsSourceKey<Set<String>>
}

public class UserDefaultsSourceKey<T : Any> internal constructor {
    public val suite: UserDefaultsSuite
    public val key: String
}

public class UserDefaultsMigrationSkip internal constructor(
    public val suiteName: String?,
    public val key: String,
    public val value: Any,
    public val expectedType: String,
)

public fun NSUserDefaultsMigrationSource(
    mode: MigrationMode = MigrationMode.STRICT,
    id: String = "nsuserdefaults",
    available: () -> Boolean = { true },
    configure: NSUserDefaultsMigrationBuilder.() -> Unit,
): MigrationSource

public class NSUserDefaultsMigrationBuilder internal constructor() {
    public var onSkipped: (UserDefaultsMigrationSkip) -> Unit   // 既定 {}
    public fun <T : Any> migrate(source: UserDefaultsSourceKey<T>, into: DaybookKey<T>)
}
```

## daybook — Android アダプタ（io.github.kr9ly.daybook）

1.0.0 凍結分（DaybookOptions / Context 拡張 / PreferenceProperty 一式）はシグネチャ無変更で維持。
2.0.0 の追加は Context.openDaybook と SharedPreferencesMigrationSource 一式。

```kotlin
public fun Context.openDaybook(
    schema: DaybookSchema,
    configure: DaybookOpenOptions.() -> Unit = {},
): Daybook

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

public class SharedPreferencesFile(public val fileName: String) {
    public fun boolean(key: String): SharedPreferencesSourceKey<Boolean>
    public fun int(key: String): SharedPreferencesSourceKey<Int>
    public fun long(key: String): SharedPreferencesSourceKey<Long>
    public fun float(key: String): SharedPreferencesSourceKey<Float>
    public fun string(key: String): SharedPreferencesSourceKey<String>
    public fun stringSet(key: String): SharedPreferencesSourceKey<Set<String>>
}

public class SharedPreferencesSourceKey<T : Any> internal constructor {
    public val file: SharedPreferencesFile
    public val key: String
}

public class SharedPreferencesMigrationSkip internal constructor(
    public val fileName: String,
    public val key: String,
    public val value: Any,
    public val expectedType: String,
)

public fun SharedPreferencesMigrationSource(
    context: Context,
    mode: MigrationMode = MigrationMode.STRICT,
    id: String = "shared-preferences",
    configure: SharedPreferencesMigrationBuilder.() -> Unit,
): MigrationSource

public class SharedPreferencesMigrationBuilder internal constructor() {
    public var onSkipped: (SharedPreferencesMigrationSkip) -> Unit   // 既定 {}
    public fun <T : Any> migrate(source: SharedPreferencesSourceKey<T>, into: DaybookKey<T>)
    public fun importAllKeys(file: SharedPreferencesFile)
}
```

## daybook-coroutines（io.github.kr9ly.daybook.coroutines）

common（全ターゲット）:

```kotlin
public fun Daybook.changesAsFlow(): Flow<String>
public fun <T> DaybookProperty<T>.asFlow(): Flow<T>
```

Android のみ（1.0.0 凍結分の維持）:

```kotlin
public fun <T> PreferenceProperty<T>.asFlow(): Flow<T>
public fun SharedPreferences.changesAsFlow(): Flow<String?>
```

## daybook-test（io.github.kr9ly.daybook.test）

common（expect）:

```kotlin
public expect class TestDaybook(packageName: String = "test") {
    public fun getDaybook(schema: DaybookSchema, multiProcess: Boolean = false): Daybook
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

Android actual の追加メンバー（1.0.0 凍結分の維持）:

```kotlin
public fun getSharedPreferences(name: String, multiProcess: Boolean = false): SharedPreferences
public fun getDefaultSharedPreferences(multiProcess: Boolean = false): SharedPreferences
```

## daybook-multiplatform-settings（io.github.kr9ly.daybook.settings）

公開面はコンストラクタと multiplatform-settings のインターフェース実装のみで、独自メンバーの追加はない。
インターフェースのメンバーシグネチャは依存先（com.russhwolf.settings）の宣言が正。

```kotlin
public class DaybookSettings(daybook: Daybook) : ObservableSettings

@ExperimentalSettingsApi
public class DaybookFlowSettings(daybook: Daybook) : FlowSettings
```

## 凍結対象外（@RequiresOptIn ERROR で opt-in 必須、互換性保証なし）

daybook-core の内部公開面（io.github.kr9ly.daybook.internal.DaybookInternalApi が付く全宣言）:
KvStore / DaybookRegistry / KvOperation / ChangeNotificationDelivery / KvStore.asDaybook /
SchemaTargetedMigrationSource / journal・io・concurrent パッケージの全 public 宣言。
これらは daybook 自身の成果物（:daybook / daybook-test）がモジュール境界を越えるためだけに public。

```kotlin
// daybook-core（io.github.kr9ly.daybook.internal）
public annotation class DaybookInternalApi
```

:daybook 側の同名アノテーション（別物）とテストブリッジ:

```kotlin
// :daybook（io.github.kr9ly.daybook）
public annotation class DaybookInternalApi

@DaybookInternalApi
public object DaybookTestBridge {
    public fun wrapAsSharedPreferences(
        store: KvStore,
        delivery: (Runnable) -> Unit,
    ): SharedPreferences
}
```
