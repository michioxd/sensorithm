import XCTest
@testable import SensorithmCore

final class SensorithmCoreTests: XCTestCase {
    private let settings = ZoneSettings(sizePercentX: 20, sizePercentY: 10, spacingPercent: 10, angleDegrees: 180, threshold: 30)

    func testZoneLayoutMatchesAndroidOrdering() {
        let layout = SensorZoneLayoutCalculator.calculate(frame: FrameGeometry(previewWidth: 200, previewHeight: 100, rawWidth: 200, rawHeight: 100, rotationDegrees: 0), settings: settings, offsetX: 0.5, offsetY: 0.5)
        XCTAssertEqual(layout.nativeConfig, [100, 25, 40, 10, 100, 35, 40, 10, 100, 45, 40, 10, 100, 55, 40, 10, 100, 65, 40, 10, 100, 75, 40, 10])
    }

    func testNinetyDegreeLayoutMatchesAndroid() {
        let layout = SensorZoneLayoutCalculator.calculate(frame: FrameGeometry(previewWidth: 100, previewHeight: 200, rawWidth: 200, rawHeight: 100, rotationDegrees: 90), settings: settings, offsetX: 0.5, offsetY: 0.5)
        XCTAssertEqual(Array(layout.nativeConfig[0..<4]), [50, 49, 20, 20])
        XCTAssertEqual(Array(layout.nativeConfig[20..<24]), [150, 49, 20, 20])
    }

    func testControlFrameUsesServerWireFormat() throws {
        let settings = SyncedSettings(sizeX: 15, sizeY: 5, spacing: 10, angle: 180, exposure: 10, threshold: 30, offsetX: 0.5, offsetY: 0.5)
        let frame = try ClientProtocol.controlFrame(for: .settings(settings))
        XCTAssertEqual(frame.first, 0xff)
        let length = frame.dropFirst().prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        XCTAssertEqual(Int(length), frame.count - 5)
        let object = try JSONSerialization.jsonObject(with: frame.dropFirst(5)) as? [String: Any]
        XCTAssertEqual(object?["type"] as? String, "settings")
    }

    func testLegacyRecalibrateAndInvalidSettings() throws {
        XCTAssertEqual(try ClientProtocol.parseServerMessage("RECALIBRATE"), .recalibrate)
        XCTAssertThrowsError(try ClientProtocol.parseServerMessage("{\"type\":\"settings\",\"settings\":{\"size_x\":-1}}"))
    }

    func testSensorCalibrationAndDuplicateSuppression() {
        let processor = SensorProcessor()
        processor.configureZones([1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1])
        processor.setThreshold(30)
        var frame = Array(repeating: UInt8(0), count: 9)
        for _ in 0..<5 { frame.withUnsafeBufferPointer { XCTAssertNil(processor.processFrame($0.baseAddress!, width: 3, height: 3, rowStride: 3)) } }
        frame.withUnsafeBufferPointer { XCTAssertEqual(processor.processFrame($0.baseAddress!, width: 3, height: 3, rowStride: 3), 0) }
        frame.withUnsafeBufferPointer { XCTAssertNil(processor.processFrame($0.baseAddress!, width: 3, height: 3, rowStride: 3)) }
        frame[3] = 255
        frame[4] = 255
        frame.withUnsafeBufferPointer { XCTAssertEqual(processor.processFrame($0.baseAddress!, width: 3, height: 3, rowStride: 3), 63) }
    }
}
