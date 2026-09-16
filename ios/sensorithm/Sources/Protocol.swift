import Foundation

public struct SyncedSettings: Codable, Equatable {
    public var sizeX: Int
    public var sizeY: Int
    public var spacing: Int
    public var angle: Int
    public var exposure: Int
    public var threshold: Int
    public var offsetX: Float
    public var offsetY: Float

    enum CodingKeys: String, CodingKey {
        case sizeX = "size_x", sizeY = "size_y", spacing, angle, exposure, threshold
        case offsetX = "offset_x", offsetY = "offset_y"
    }

    public var isValid: Bool {
        (0...100).contains(sizeX) && (0...50).contains(sizeY) &&
            (0...100).contains(spacing) && (0...360).contains(angle) &&
            (0...100).contains(exposure) && (0...255).contains(threshold) &&
            (0...1).contains(offsetX) && (0...1).contains(offsetY)
    }
}

public enum ServerMessage: Equatable {
    case recalibrate
    case settings(SyncedSettings)
    case setTorch(Bool)
    case restartCamera
    case requestPreview(String)
}

public enum ClientMessage: Equatable {
    case settings(SyncedSettings)
    case torchState(available: Bool, enabled: Bool)
    case telemetry(batteryPercent: Int, temperatureCelsius: Float?, charging: Bool)
    case preview(requestID: String, width: Int, height: Int, jpegBytes: Data)
    case error(operation: String, requestID: String?, message: String)
}

public enum ProtocolError: Error, Equatable {
    case malformedMessage
    case unsupportedMessage
    case invalidSettings
    case invalidFrameLength
}

public enum ClientProtocol {
    public static let controlFramePrefix: UInt8 = 0xff
    public static let maxControlFrameBytes = 2 * 1024 * 1024

    public static func parseHello(_ line: String) throws -> (version: String, accepted: Bool, message: String?) {
        let object = try jsonObject(line)
        guard let accepted = object["accepted"] as? Bool else { throw ProtocolError.malformedMessage }
        return (object["version"] as? String ?? "Unknown", accepted, object["message"] as? String)
    }

    public static func parseServerMessage(_ line: String) throws -> ServerMessage {
        if line.trimmingCharacters(in: .whitespacesAndNewlines) == "RECALIBRATE" { return .recalibrate }
        let object = try jsonObject(line)
        guard let type = object["type"] as? String else { throw ProtocolError.malformedMessage }
        switch type {
        case "recalibrate": return .recalibrate
        case "settings": return .settings(try settings(object["settings"]))
        case "set_torch":
            guard let enabled = object["enabled"] as? Bool else { throw ProtocolError.malformedMessage }
            return .setTorch(enabled)
        case "restart_camera": return .restartCamera
        case "request_preview":
            guard let requestID = object["request_id"] as? String else { throw ProtocolError.malformedMessage }
            return .requestPreview(requestID)
        default: throw ProtocolError.unsupportedMessage
        }
    }

    public static func controlFrame(for message: ClientMessage) throws -> Data {
        let payload = try JSONSerialization.data(withJSONObject: clientObject(message), options: [])
        guard !payload.isEmpty && payload.count <= maxControlFrameBytes else { throw ProtocolError.invalidFrameLength }
        var frame = Data([controlFramePrefix])
        var length = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: &length) { frame.append(contentsOf: $0) }
        frame.append(payload)
        return frame
    }

    private static func jsonObject(_ line: String) throws -> [String: Any] {
        guard let data = line.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ProtocolError.malformedMessage
        }
        return object
    }

    private static func settings(_ value: Any?) throws -> SyncedSettings {
        guard let data = try? JSONSerialization.data(withJSONObject: value as Any),
              let settings = try? JSONDecoder().decode(SyncedSettings.self, from: data), settings.isValid else {
            throw ProtocolError.invalidSettings
        }
        return settings
    }

    private static func clientObject(_ message: ClientMessage) -> [String: Any] {
        switch message {
        case .settings(let settings): return ["type": "settings", "settings": settingsObject(settings)]
        case .torchState(let available, let enabled): return ["type": "torch_state", "available": available, "enabled": enabled]
        case .telemetry(let percent, let temperature, let charging):
            return ["type": "telemetry", "battery_percent": percent, "temperature_celsius": temperature ?? NSNull(), "charging": charging]
        case .preview(let requestID, let width, let height, let jpegBytes):
            return ["type": "preview", "request_id": requestID, "width": width, "height": height, "image_base64": jpegBytes.base64EncodedString()]
        case .error(let operation, let requestID, let message):
            return ["type": "error", "operation": operation, "request_id": requestID ?? NSNull(), "message": message]
        }
    }

    private static func settingsObject(_ settings: SyncedSettings) -> [String: Any] {
        ["size_x": settings.sizeX, "size_y": settings.sizeY, "spacing": settings.spacing, "angle": settings.angle,
         "exposure": settings.exposure, "threshold": settings.threshold, "offset_x": settings.offsetX, "offset_y": settings.offsetY]
    }
}
