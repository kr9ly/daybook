import XCTest
import DaybookHarnessKit

/// App Group コンテナの実パス解決と、コンテナ実パス上のストア動作の検証。
///
/// K/N の Gradle テスト（simctl spawn、app bundle なし）では
/// containerURLForSecurityApplicationGroupIdentifier が null を返すため、
/// app identity のあるホストアプリでだけ成立するレーン（実測 2026-08-16、
/// daybook-core の AppGroupContainerTest KDoc を参照）。
///
/// Daybook はプロセス寿命で close がないため、各テストは UUID サブディレクトリで
/// ストアを分離する（同一 (directory, schema) は同一インスタンスが返る）。
final class AppGroupStoreTests: XCTestCase {

    private let groupId = "group.io.github.kr9ly.daybook.harness"

    private func containerURL() throws -> URL {
        let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId)
        return try XCTUnwrap(url, "App Group container should resolve inside a host app")
    }

    private func freshStoreDirectory() throws -> URL {
        try containerURL().appendingPathComponent("daybook-harness-\(UUID().uuidString)")
    }

    /// スパイクの核心: ホストアプリなら containerURL が実パスに解決される。
    func testContainerURLResolves() throws {
        let url = try containerURL()
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: url.path),
            "container path should exist on disk: \(url.path)"
        )
    }

    /// App Group コンテナ実パス上で全 7 型の書き込み・読み出しが成立し、
    /// ジャーナルファイルがコンテナ上に実在する。
    func testStoreOperatesOnAppGroupContainer() throws {
        let dir = try freshStoreDirectory()
        let daybook = HarnessStore.shared.open(directory: dir.path, multiProcess: false)

        daybook.edit { editor in
            editor.putString(key: "label", value: "on app group")
            editor.putStringSet(key: "tags", value: ["a", "b"])
            editor.putInt(key: "count", value: 42)
            editor.putLong(key: "big", value: 1_234_567_890_123)
            editor.putFloat(key: "ratio", value: 0.5)
            editor.putDouble(key: "precise", value: 2.5)
            editor.putBoolean(key: "enabled", value: true)
        }

        XCTAssertEqual(daybook.getString(key: "label", default: nil), "on app group")
        XCTAssertEqual(daybook.getStringSet(key: "tags", default: nil), Set(["a", "b"]))
        XCTAssertEqual(daybook.getInt(key: "count", default: 0), 42)
        XCTAssertEqual(daybook.getLong(key: "big", default: 0), 1_234_567_890_123)
        XCTAssertEqual(daybook.getFloat(key: "ratio", default: 0), 0.5)
        XCTAssertEqual(daybook.getDouble(key: "precise", default: 0), 2.5)
        XCTAssertEqual(daybook.getBoolean(key: "enabled", default: false), true)

        // 読み出しはインメモリキャッシュ経由のため、永続の証拠としてジャーナルの実在も見る
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path)
        let journals = files.filter { $0.hasPrefix("harness.") && $0.hasSuffix(".journal") }
        XCTAssertFalse(journals.isEmpty, "journal file should exist under the container: \(files)")
        let journalPath = dir.appendingPathComponent(journals[0]).path
        let size = try XCTUnwrap(
            FileManager.default.attributesOfItem(atPath: journalPath)[.size] as? NSNumber
        )
        XCTAssertGreaterThan(size.int64Value, 0, "journal should be non-empty after an edit")
    }

    /// multiProcess = true（flock + ファイル監視）がコンテナ実パスの FS 上で成立する。
    /// 実 2 プロセス共有の検証は app extension / 第 2 アプリのハーネス拡張待ち。
    func testMultiProcessStoreWorksOnAppGroupContainer() throws {
        let dir = try freshStoreDirectory()
        let daybook = HarnessStore.shared.open(directory: dir.path, multiProcess: true)

        daybook.edit { editor in
            editor.putString(key: "label", value: "multi-process on app group")
        }

        XCTAssertEqual(daybook.getString(key: "label", default: nil), "multi-process on app group")
    }
}
