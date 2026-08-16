import SwiftUI

// XCTest のホストになるだけの最小アプリ。画面は使わない。
@main
struct HarnessApp: App {
    var body: some Scene {
        WindowGroup {
            Text("Daybook Harness")
        }
    }
}
