// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "sensorithm",
    platforms: [.macOS(.v13)],
    products: [.library(name: "sensorithmCore", targets: ["sensorithmCore"])],
    targets: [
        .target(name: "sensorithmCore", path: "sensorithm/Sources"),
        .testTarget(name: "sensorithmCoreTests", dependencies: ["sensorithmCore"], path: "sensorithmTests"),
    ]
)
