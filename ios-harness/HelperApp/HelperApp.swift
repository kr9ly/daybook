import SwiftUI

// 実 2 プロセス共有検証の「第 2 のプロセス」。HarnessApp と同じ App Group entitlement を持ち、
// XCUITest から launch environment で駆動される（StoreProbeView を参照）。
@main
struct HelperApp: App {
    var body: some Scene {
        WindowGroup {
            StoreProbeView()
        }
    }
}
