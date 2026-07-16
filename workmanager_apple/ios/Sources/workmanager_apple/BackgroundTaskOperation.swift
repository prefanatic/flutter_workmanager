//
//  BackgroundTaskOperation.swift
//  workmanager
//
//  Created by Sebastian Roth on 10/06/2021.
//

import Foundation

enum BackgroundTaskCompletion {
    static func isSuccessful(
        result: UIBackgroundFetchResult,
        isCancelled: Bool
    ) -> Bool {
        result != .failed && !isCancelled
    }
}

#if os(iOS)
import Flutter
#elseif os(macOS)
import FlutterMacOS
#else
#error("Unsupported platform.")
#endif

class BackgroundTaskOperation: Operation, @unchecked Sendable {

    private let identifier: String
    private let flutterPluginRegistrantCallback: FlutterPluginRegistrantCallback?
    private let inputData: [String: Any]?
    private let backgroundMode: BackgroundMode

    private(set) var result = UIBackgroundFetchResult.failed

    init(_ identifier: String,
         inputData: [String: Any]?,
         flutterPluginRegistrantCallback: FlutterPluginRegistrantCallback?,
         backgroundMode: BackgroundMode) {
        self.identifier = identifier
        self.inputData = inputData
        self.flutterPluginRegistrantCallback = flutterPluginRegistrantCallback
        self.backgroundMode = backgroundMode
    }

    override func main() {
        let semaphore = DispatchSemaphore(value: 0)
        let worker = BackgroundWorker(mode: self.backgroundMode,
                                      inputData: self.inputData,
                                      flutterPluginRegistrantCallback: self.flutterPluginRegistrantCallback)
        DispatchQueue.main.async {
            worker.performBackgroundRequest { result in
                self.result = result
                semaphore.signal()
            }
        }

        semaphore.wait()
    }
}
