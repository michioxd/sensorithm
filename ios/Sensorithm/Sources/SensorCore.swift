import Foundation

public struct ZoneSettings: Equatable {
    public var sizePercentX = 15
    public var sizePercentY = 5
    public var spacingPercent = 10
    public var angleDegrees = 180
    public var threshold = 30
}

public struct FrameGeometry: Equatable {
    public let previewWidth: Int
    public let previewHeight: Int
    public let rawWidth: Int
    public let rawHeight: Int
    public let rotationDegrees: Int
}

public struct ZoneLayout: Equatable {
    public let sensorWidth: Int
    public let sensorHeight: Int
    public let spacing: Int
    public let centers: [(Int, Int)]
    public let nativeConfig: [Int]

    public static func == (lhs: ZoneLayout, rhs: ZoneLayout) -> Bool {
        lhs.sensorWidth == rhs.sensorWidth && lhs.sensorHeight == rhs.sensorHeight && lhs.spacing == rhs.spacing && lhs.nativeConfig == rhs.nativeConfig
    }
}

public enum SensorZoneLayoutCalculator {
    public static func calculate(frame: FrameGeometry, settings: ZoneSettings, offsetX: Float, offsetY: Float) -> ZoneLayout {
        precondition(frame.previewWidth > 0 && frame.previewHeight > 0)
        let width = max(1, Int(Float(settings.sizePercentX) / 100 * Float(frame.previewWidth)))
        let height = max(1, Int(Float(settings.sizePercentY) / 100 * Float(frame.previewHeight)))
        let spacing = Int(Float(settings.spacingPercent) / 100 * Float(frame.previewHeight))
        let xOffset = Int((offsetX - 0.5) * Float(frame.previewWidth))
        let yOffset = Int((offsetY - 0.5) * Float(frame.previewHeight))
        let radians = Float(settings.angleDegrees - 180) * .pi / 180
        let swapsAxes = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        let rawWidth = swapsAxes ? height : width
        let rawHeight = swapsAxes ? width : height
        var centers: [(Int, Int)] = []
        var config: [Int] = []

        for index in 0..<6 {
            let x = Int(Float(frame.previewWidth) / 2 + Float(xOffset) + (Float(index) - 2.5) * Float(spacing) * sin(radians))
            let y = Int(Float(frame.previewHeight) / 2 + Float(yOffset) + (Float(index) - 2.5) * Float(spacing) * cos(radians))
            centers.append((x, y))
            let raw: (Int, Int)
            switch frame.rotationDegrees {
            case 90: raw = (y, frame.rawHeight - 1 - x)
            case 180: raw = (frame.rawWidth - 1 - x, frame.rawHeight - 1 - y)
            case 270: raw = (frame.rawWidth - 1 - y, x)
            default: raw = (x, y)
            }
            config += [raw.0, raw.1, rawWidth, rawHeight]
        }
        return ZoneLayout(sensorWidth: width, sensorHeight: height, spacing: spacing, centers: centers, nativeConfig: config)
    }
}

public final class SensorProcessor {
    private struct Sensor { var x = 0; var y = 0; var halfWidth = 1; var halfHeight = 1; var reference: [Float] = []; var threshold: Float = 0 }
    private var sensors = Array(repeating: Sensor(), count: 6)
    private var config = Array(repeating: 0, count: 24)
    private var needsRecalibration = true
    private var bootstrapFrames = 0
    private var dimensions = (width: 0, height: 0)
    private var lastMask: UInt8?
    private let lock = NSLock()

    public init() {}

    public func configureZones(_ config: [Int]) {
        precondition(config.count == 24)
        lock.lock(); defer { lock.unlock() }
        self.config = config
        needsRecalibration = true
    }

    public func setThreshold(_ threshold: Float) {
        lock.lock(); defer { lock.unlock() }
        for index in sensors.indices { sensors[index].threshold = threshold }
    }

    public func recalibrate() {
        lock.lock(); defer { lock.unlock() }
        needsRecalibration = true
        bootstrapFrames = 0
    }

    public func resetOutput() { lock.lock(); defer { lock.unlock() }; lastMask = nil }

    public func processFrame(_ pixels: UnsafePointer<UInt8>, width: Int, height: Int, rowStride: Int) -> UInt8? {
        guard width > 0, height > 0 else { return nil }
        lock.lock(); defer { lock.unlock() }
        if dimensions.width != width || dimensions.height != height { dimensions = (width, height); needsRecalibration = true }
        if needsRecalibration {
            if bootstrapFrames < 5 { bootstrapFrames += 1; return nil }
            for index in sensors.indices {
                let base = index * 4
                let halfWidth = max(1, config[base + 2] / 2)
                let halfHeight = max(1, config[base + 3] / 2)
                var sensor = sensors[index]
                sensor.x = config[base]; sensor.y = config[base + 1]; sensor.halfWidth = halfWidth; sensor.halfHeight = halfHeight; sensor.reference = []
                for row in (sensor.y - halfHeight)...(sensor.y + halfHeight) where row >= 0 && row < height {
                    for column in (sensor.x - halfWidth)...(sensor.x + halfWidth) where column >= 0 && column < width {
                        sensor.reference.append(Float(pixels[row * rowStride + column]) / 255)
                    }
                }
                sensors[index] = sensor
            }
            needsRecalibration = false; bootstrapFrames = 0
            return emit(0)
        }
        var mask: UInt8 = 0
        for index in sensors.indices {
            let sensor = sensors[index]
            var hits = 0; var valid = 0; var referenceIndex = 0
            for row in (sensor.y - sensor.halfHeight)...(sensor.y + sensor.halfHeight) where row >= 0 && row < height {
                for column in (sensor.x - sensor.halfWidth)...(sensor.x + sensor.halfWidth) where column >= 0 && column < width {
                    guard referenceIndex < sensor.reference.count else { continue }
                    let current = Float(pixels[row * rowStride + column]) / 255
                    if abs(current - sensor.reference[referenceIndex]) * 255 > sensor.threshold { hits += 1 }
                    referenceIndex += 1; valid += 1
                }
            }
            if valid > 0 && Float(hits) > Float(valid) * 0.15 { mask |= UInt8(1 << index) }
        }
        return emit(mask)
    }

    private func emit(_ mask: UInt8) -> UInt8? {
        guard lastMask != mask else { return nil }
        lastMask = mask
        return mask
    }
}
