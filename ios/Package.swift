// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "Sensorithm",
    platforms: [.macOS(.v13)],
    products: [.library(name: "SensorithmCore", targets: ["SensorithmCore"])],
    targets: [
        .target(name: "SensorithmCore", path: "Sensorithm/Sources"),
        .testTarget(name: "SensorithmCoreTests", dependencies: ["SensorithmCore"], path: "SensorithmTests"),
    ]
)
