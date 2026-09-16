import Foundation

public struct AppConfig: Codable, Equatable {
    public var serverAddress = ""
    public var serverPort = 4420
    public var autoReconnect = false
    public var autoConnectOnStartup = false
    public var zones = ZoneSettingsCodable()
    public var exposure = 10
    public var zoneOffsetX: Float = 0.5
    public var zoneOffsetY: Float = 0.5
    public var camera = CameraConfig()
}

public struct CameraConfig: Codable, Equatable {
    public var deviceID = ""
    public var width = 0
    public var height = 0
    public var fps = 0
}

public struct ZoneSettingsCodable: Codable, Equatable {
    public var sizePercentX = 15
    public var sizePercentY = 5
    public var spacingPercent = 10
    public var angleDegrees = 180
    public var threshold = 30

    public var zoneSettings: ZoneSettings {
        ZoneSettings(sizePercentX: sizePercentX, sizePercentY: sizePercentY, spacingPercent: spacingPercent, angleDegrees: angleDegrees, threshold: threshold)
    }
}

public final class ConfigRepository {
    private let defaults: UserDefaults
    private let key = "SensorithmConfig"

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public func load() -> AppConfig {
        guard let data = defaults.data(forKey: key), let config = try? JSONDecoder().decode(AppConfig.self, from: data) else { return AppConfig() }
        return config
    }

    public func save(_ config: AppConfig) { defaults.set(try? JSONEncoder().encode(config), forKey: key) }
}

public final class ClientStateSynchronizer {
    private let send: (ClientMessage) -> Void
    private let applyRemote: (SyncedSettings) -> Void

    public init(send: @escaping (ClientMessage) -> Void, applyRemote: @escaping (SyncedSettings) -> Void) {
        self.send = send; self.applyRemote = applyRemote
    }

    public func publishLocalSettings(_ settings: SyncedSettings) {
        if settings.isValid { send(.settings(settings)) }
    }

    @discardableResult public func applyRemoteSettings(_ settings: SyncedSettings) -> Bool {
        guard settings.isValid else { return false }
        applyRemote(settings)
        return true
    }
}
