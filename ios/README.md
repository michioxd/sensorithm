# Sensorithm iOS

For running on iPhone. iOS 12 is the minimum supported version, but for best(-ish) UI experience, iOS 13 or later is recommended.

## Build

Open `Sensorithm.xcodeproj` in Xcode, select a development team, then run on a
physical iOS device. Camera processing requires a device.

Run the platform-independent tests from this directory:

```text
swift test
```

The Xcode installation must include an iOS SDK platform to build the app target.
