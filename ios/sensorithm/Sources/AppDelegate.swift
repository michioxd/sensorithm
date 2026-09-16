#if canImport(UIKit)
import Foundation
import UIKit
#if canImport(MetricKit)
import MetricKit
#endif

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        CrashReporter.install()
        UIApplication.shared.isIdleTimerDisabled = true
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = MainViewController()
        window.makeKeyAndVisible()
        self.window = window
        if let report = CrashReporter.lastReport {
            DispatchQueue.main.async {
                let alert = UIAlertController(title: "Previous crash detected", message: "Copy the crash report before continuing.", preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "Copy report", style: .default) { _ in UIPasteboard.general.string = report; CrashReporter.lastReport = nil })
                alert.addAction(UIAlertAction(title: "Keep", style: .cancel))
                window.rootViewController?.present(alert, animated: true)
            }
        }
        return true
    }
}

fileprivate enum CrashReporter {
    private static let key = "lastCrashReport"
#if canImport(MetricKit)
    @available(iOS 14.0, *) private static let diagnostics = CrashDiagnostics()
#endif

    fileprivate static var lastReport: String? {
        get { UserDefaults.standard.string(forKey: key) }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    static func install() {
        NSSetUncaughtExceptionHandler(recordUncaughtException)
#if canImport(MetricKit)
    if #available(iOS 14.0, *) { MXMetricManager.shared.add(diagnostics) }
#endif
    }
}

#if canImport(MetricKit)
@available(iOS 14.0, *)
private final class CrashDiagnostics: NSObject, MXMetricManagerSubscriber {
    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads where payload.crashDiagnostics != nil {
            CrashReporter.lastReport = String(data: payload.jsonRepresentation(), encoding: .utf8)
        }
    }
}
#endif

private func recordUncaughtException(_ exception: NSException) {
    CrashReporter.lastReport = """
    Date: \(Date())
    App: \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown") (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "Unknown"))
    OS: \(ProcessInfo.processInfo.operatingSystemVersionString)
    Exception: \(exception.name.rawValue): \(exception.reason ?? "No reason")
    Stack:\n\(exception.callStackSymbols.joined(separator: "\n"))
    """
}
#endif
