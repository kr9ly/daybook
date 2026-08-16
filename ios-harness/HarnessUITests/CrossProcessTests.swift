import XCTest

/// App Group コンテナ上の実 2 プロセス共有の検証（シミュレータで成立する範囲の multiProcess 検証）。
///
/// HarnessApp と HelperApp（別 bundle ID・別プロセス・同一 App Group）を交互にアクティブにし、
/// 同じストアディレクトリへの読み書きを突き合わせる:
/// 1. コールドリード: Helper が書く → Main が別プロセスとして open し、ジャーナルリプレイで読めること
/// 2. watcher キャッチアップ: Main が書く → サスペンドから復帰した Helper が
///    dispatch source watcher の差分リプレイで追いつくこと
///
/// iOS はフォアグラウンドが常に 1 アプリで、裏のアプリはサスペンドされる。
/// 「2 プロセスが同時にライブで監視し合う」形は OS 上成立しないため、
/// 実需（extension が書く → 本体が復帰時に追いつく）と同じ交互アクティブ形でエンコードする。
final class CrossProcessTests: XCTestCase {

    private let mainAppId = "io.github.kr9ly.daybook.harness"
    private let helperAppId = "io.github.kr9ly.daybook.harness.helper"

    override func setUp() {
        continueAfterFailure = false
    }

    func testCrossProcessSharingOnAppGroupContainer() {
        // ストアはプロセス寿命でテスト間の分離ができないため、実行ごとに一意のディレクトリ
        let dir = "xproc-\(UUID().uuidString)"

        // 1. Helper（第 2 プロセス）が App Group コンテナ上のストアに書く
        let helper = XCUIApplication(bundleIdentifier: helperAppId)
        helper.launchEnvironment = ["HARNESS_DIR": dir, "HARNESS_WRITE": "from-helper"]
        helper.launch()
        let helperCurrent = helper.staticTexts["current"]
        XCTAssertTrue(helperCurrent.waitForExistence(timeout: 15))
        XCTAssertEqual(helperCurrent.label, "current: from-helper")

        // 2. Main が別プロセスとしてコールドオープン: Helper の書き込みがジャーナルリプレイで見える
        let main = XCUIApplication(bundleIdentifier: mainAppId)
        main.launchEnvironment = ["HARNESS_DIR": dir, "HARNESS_WRITE": "from-main"]
        main.launch()
        let mainInitial = main.staticTexts["initial"]
        XCTAssertTrue(mainInitial.waitForExistence(timeout: 15))
        XCTAssertEqual(mainInitial.label, "initial: from-helper", "cold read should see helper's write")
        XCTAssertEqual(main.staticTexts["current"].label, "current: from-main")

        // 3. Helper に戻る（プロセスは生存・ストアはキャッシュ済み）:
        //    復帰後、watcher の差分リプレイで Main の書き込みに追いつくまでポーリング
        helper.activate()
        XCTAssertTrue(helperCurrent.waitForExistence(timeout: 15))
        var caughtUp = false
        for _ in 0 ..< 30 {
            helper.buttons["refresh"].tap()
            if helperCurrent.label == "current: from-main" {
                caughtUp = true
                break
            }
            usleep(500_000)
        }
        XCTAssertTrue(caughtUp, "helper should catch up to main's write after resume (last: \(helperCurrent.label))")
    }
}
