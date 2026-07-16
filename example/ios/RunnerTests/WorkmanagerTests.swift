//
//  WorkmanagerTests.swift
//  WorkmanagerTests
//
//  Created by Sebastian Roth on 08/09/2021.
//  Copyright © 2021 The Chromium Authors. All rights reserved.
//

import XCTest

@testable import workmanager_apple

class WorkmanagerTests: XCTestCase {

    func testBackgroundTaskCompletionSucceedsForCompletedWork() {
        XCTAssertTrue(
            BackgroundTaskCompletion.isSuccessful(
                result: .newData,
                isCancelled: false
            )
        )
        XCTAssertTrue(
            BackgroundTaskCompletion.isSuccessful(
                result: .noData,
                isCancelled: false
            )
        )
    }

    func testBackgroundTaskCompletionFailsForFailedWork() {
        XCTAssertFalse(
            BackgroundTaskCompletion.isSuccessful(
                result: .failed,
                isCancelled: false
            )
        )
    }

    func testBackgroundTaskCompletionFailsForExpiredWork() {
        XCTAssertFalse(
            BackgroundTaskCompletion.isSuccessful(
                result: .newData,
                isCancelled: true
            )
        )
    }
}
