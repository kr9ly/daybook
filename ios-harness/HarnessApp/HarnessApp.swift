import SwiftUI

// XCTest（unit test）のホスト + XCUITest（CrossProcessTests）の主アプリ。
// launch environment なしで起動されたときは何もしない（StoreProbeView を参照）。
@main
struct HarnessApp: App {
    var body: some Scene {
        WindowGroup {
            StoreProbeView()
        }
    }
}
