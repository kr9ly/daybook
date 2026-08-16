import SwiftUI
import DaybookHarnessKit

/// HarnessApp / HelperApp の両方から使うプローブ画面。
/// XCUITest（CrossProcessTests）が launch environment で指示を渡し、画面のテキストで結果を回収する:
/// - HARNESS_DIR: App Group コンテナ直下のストアディレクトリ名。未指定なら何もしない（unit test ホスト時）
/// - HARNESS_WRITE: 指定があれば起動時に label キーへ書き込む値
///
/// 表示: initial = 自プロセスの書き込み前に読めた値（別プロセスの書き込みのコールドリード検証）、
/// current = 最新の読み出し値（refresh ボタンで再読み — watcher のキャッチアップ検証）。
struct StoreProbeView: View {
    private let groupId = "group.io.github.kr9ly.daybook.harness"

    @State private var status = "launching"
    @State private var initialValue = "-"
    @State private var currentValue = "-"
    // Daybook の型名（ObjC export 名）に依存しないよう、読み出しはクロージャに閉じ込める
    @State private var readLabel: (() -> String)?

    var body: some View {
        VStack(spacing: 12) {
            Text("status: \(status)").accessibilityIdentifier("status")
            Text("initial: \(initialValue)").accessibilityIdentifier("initial")
            Text("current: \(currentValue)").accessibilityIdentifier("current")
            Button("refresh") {
                currentValue = readLabel?() ?? "-"
            }
            .accessibilityIdentifier("refresh")
        }
        .onAppear { setUp() }
    }

    private func setUp() {
        let env = ProcessInfo.processInfo.environment
        guard let dirName = env["HARNESS_DIR"] else {
            status = "idle"
            return
        }
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: groupId
        ) else {
            status = "no-container"
            return
        }
        let dir = container.appendingPathComponent(dirName).path
        let daybook = HarnessStore.shared.open(directory: dir, multiProcess: true)
        readLabel = { daybook.getString(key: "label", default: "<absent>") ?? "<absent>" }

        initialValue = readLabel?() ?? "-"
        if let value = env["HARNESS_WRITE"] {
            daybook.edit { editor in
                editor.putString(key: "label", value: value)
            }
        }
        currentValue = readLabel?() ?? "-"
        status = "ready"
    }
}
