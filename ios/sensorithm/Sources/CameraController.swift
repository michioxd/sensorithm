#if canImport(AVFoundation) && canImport(UIKit)
import AVFoundation
import UIKit

final class CameraPreviewView: UIView {
    private let previewLayer = AVCaptureVideoPreviewLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        previewLayer.videoGravity = .resizeAspectFill
        layer.addSublayer(previewLayer)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layoutSubviews() { super.layoutSubviews(); previewLayer.frame = bounds }
    func display(session: AVCaptureSession) { previewLayer.session = session; previewLayer.connection?.videoOrientation = .portrait }
}

final class CameraController: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    struct Option: Equatable {
        let deviceID: String
        let position: AVCaptureDevice.Position
        let title: String
        let width: Int
        let height: Int
        let fps: Int
    }
    var onFrame: ((UnsafePointer<UInt8>, Int, Int, Int) -> Void)?
    var onError: ((String) -> Void)?
    var onPreview: ((String, Result<(Data, Int, Int), Error>) -> Void)?
    var onFps: ((Float) -> Void)?
    var onFrameGeometry: ((Int, Int) -> Void)?
    var onTorchAvailabilityChanged: ((Bool) -> Void)?
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "ch.michioxd.sensorithm.camera")
    private weak var previewView: CameraPreviewView?
    private let ciContext = CIContext()
    private var pendingPreviewID: String?
    private var frameCount = 0
    private var fpsStart = Date()
    private var desiredDeviceID = ""
    private var desiredWidth = 0
    private var desiredHeight = 0
    private var desiredFps = 0
    private var frameSize = (width: 0, height: 0)

    override init() {
        super.init()
    }

    func attachPreview(to preview: CameraPreviewView) {
        previewView = preview
        preview.display(session: session)
    }


    func start() {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard granted else { self?.onError?("Camera permission is required") ; return }
            self?.queue.async { self?.configureAndStart() }
        }
    }

    func stop() { queue.async { if self.session.isRunning { self.session.stopRunning() }; self.resetFps() } }

    func requestPreview(_ requestID: String) -> Bool {
        queue.sync {
            guard session.isRunning, pendingPreviewID == nil else { return false }
            pendingPreviewID = requestID
            return true
        }
    }

    func setTorch(_ enabled: Bool) -> Bool {
        queue.sync {
            guard let device = session.inputs.compactMap({ ($0 as? AVCaptureDeviceInput)?.device }).first, device.hasTorch else { return false }
            do {
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }
                if enabled { try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel) }
                else { device.torchMode = .off }
                return true
            } catch { return false }
        }
    }

    func setExposure(_ percentage: Int) {
        queue.async {
            guard let device = self.session.inputs.compactMap({ ($0 as? AVCaptureDeviceInput)?.device }).first else { return }
            do {
                try device.lockForConfiguration()
                device.setExposureTargetBias(device.minExposureTargetBias + Float(percentage) / 100 * (device.maxExposureTargetBias - device.minExposureTargetBias))
                device.unlockForConfiguration()
            } catch { self.onError?(error.localizedDescription) }
        }
    }

    var torchAvailable: Bool { session.inputs.compactMap { ($0 as? AVCaptureDeviceInput)?.device }.first?.hasTorch == true }

    func options() -> [Option] {
        var result: [Option] = []
        for device in AVCaptureDevice.devices(for: .video) {
            for format in device.formats {
                let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                for range in format.videoSupportedFrameRateRanges {
                    let fps = Int(range.maxFrameRate.rounded(.down))
                    if fps > 0 { result.append(Option(deviceID: device.uniqueID, position: device.position, title: "\(device.localizedName)", width: Int(dimensions.width), height: Int(dimensions.height), fps: fps)) }
                }
            }
        }
        return result.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    func select(_ option: Option) {
        queue.async {
            self.desiredDeviceID = option.deviceID; self.desiredWidth = option.width; self.desiredHeight = option.height; self.desiredFps = option.fps
            self.restart()
        }
    }

    func restore(_ config: CameraConfig) {
        desiredDeviceID = config.deviceID; desiredWidth = config.width; desiredHeight = config.height; desiredFps = config.fps
    }

    private func configureAndStart() {
        guard !session.isRunning else { return }
        session.beginConfiguration()
        var shouldStart = false
        defer {
            session.commitConfiguration()
            if shouldStart {
                resetFps()
                session.startRunning()
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    self.previewView?.display(session: self.session)
                }
            }
        }
        let device = AVCaptureDevice.devices(for: .video).first { $0.uniqueID == desiredDeviceID } ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
        guard let device else { onError?("Camera unavailable"); return }
        do {
            session.sessionPreset = desiredWidth > 0 ? .inputPriority : .high
            let format = desiredWidth > 0 ? device.formats.first(where: { format in
                let size = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                return size.width == desiredWidth && size.height == desiredHeight && format.videoSupportedFrameRateRanges.contains { $0.minFrameRate <= Double(desiredFps) && Double(desiredFps) <= $0.maxFrameRate }
            }) : nil
            session.inputs.forEach(session.removeInput)
            let input = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(input) else { onError?("Cannot configure camera input"); return }
            session.addInput(input)
            onTorchAvailabilityChanged?(device.hasTorch)
            if let format {
                do {
                    try device.lockForConfiguration()
                    device.activeFormat = format
                    if desiredFps > 0 { let duration = CMTime(value: 1, timescale: CMTimeScale(desiredFps)); device.activeVideoMinFrameDuration = duration; device.activeVideoMaxFrameDuration = duration }
                    device.unlockForConfiguration()
                } catch { onError?("Selected camera format is unavailable; using the default format") }
            }
            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]
            output.setSampleBufferDelegate(self, queue: queue)
            guard session.canAddOutput(output) else { onError?("Cannot configure camera output"); return }
            session.addOutput(output)
            output.connection(with: .video)?.videoOrientation = .portrait
            shouldStart = true
        } catch { onError?(error.localizedDescription) }
    }

    private func restart() {
        if session.isRunning { session.stopRunning() }
        resetFps()
        session.inputs.forEach(session.removeInput)
        session.outputs.forEach(session.removeOutput)
        configureAndStart()
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer), CVPixelBufferGetPlaneCount(buffer) > 0 else { return }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0)?.assumingMemoryBound(to: UInt8.self) else { return }
        let width = CVPixelBufferGetWidthOfPlane(buffer, 0), height = CVPixelBufferGetHeightOfPlane(buffer, 0)
        if frameSize.width != width || frameSize.height != height { frameSize = (width, height); onFrameGeometry?(width, height) }
        onFrame?(base, width, height, CVPixelBufferGetBytesPerRowOfPlane(buffer, 0))
        frameCount += 1
        let elapsed = -fpsStart.timeIntervalSinceNow
        if elapsed >= 0.5 { onFps?(Float(frameCount) / Float(elapsed)); frameCount = 0; fpsStart = Date() }
        guard let requestID = pendingPreviewID else { return }
        pendingPreviewID = nil
        do { onPreview?(requestID, .success(try encodePreview(buffer))) }
        catch { onPreview?(requestID, .failure(error)) }
    }

    private func resetFps() { frameCount = 0; fpsStart = Date() }

    private func encodePreview(_ buffer: CVPixelBuffer) throws -> (Data, Int, Int) {
        let image = CIImage(cvPixelBuffer: buffer)
        let extent = image.extent
        let scale = min(1, 640 / max(extent.width, extent.height))
        let output = scale == 1 ? image : image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let width = Int(output.extent.width.rounded()), height = Int(output.extent.height.rounded())
        guard let cgImage = ciContext.createCGImage(output, from: output.extent), let jpeg = UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.7) else { throw PreviewError.encodingFailed }
        return (jpeg, width, height)
    }

    private enum PreviewError: Error { case encodingFailed }
}
#endif
