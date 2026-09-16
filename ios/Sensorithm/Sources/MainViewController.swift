#if canImport(UIKit)
import UIKit

final class MainViewController: UIViewController {
    private let camera = CameraController()
    private let processor = SensorProcessor()
    private let connection = ConnectionManager()
    private let status = UILabel()
    private let cameraContainer = UIView()
    private let preview = CameraPreviewView()
    private let overlay = ZoneOverlayView()
    private let watermark = UILabel()
    private let address = UITextField()
    private let port = UITextField()
    private let connect = UIButton(type: .system)
    private let torch = UIButton(type: .system)
    private let cameraButton = UIButton(type: .system)
    private let recalibrate = UIButton(type: .system)
    private let metrics = UILabel()
    private let repository = ConfigRepository()
    private var controls: UIStackView?
    private var settings: UIStackView?
    private var preferences: UIStackView?
    private let reconnectLabel = UILabel()
    private let autoConnectLabel = UILabel()
    private var config = AppConfig()
    private var zoneKey = ""
    private var sliders: [UISlider] = []
    private var values: [UILabel] = []
    private var latestFps: Float = 0
    private var torchEnabled = false
    private var frameGeometry: FrameGeometry?
    private var cameraAspectConstraint: NSLayoutConstraint?
    private var cameraTopConstraint: NSLayoutConstraint?
    private var cameraBottomConstraint: NSLayoutConstraint?
    private var cameraCenterConstraint: NSLayoutConstraint?
    private var telemetryTimer: Timer?

    override func viewDidLoad() {
        super.viewDidLoad()
        config = repository.load()
        view.backgroundColor = .black
        cameraContainer.translatesAutoresizingMaskIntoConstraints = false
        cameraContainer.clipsToBounds = true
        preview.translatesAutoresizingMaskIntoConstraints = false
        overlay.translatesAutoresizingMaskIntoConstraints = false
        watermark.translatesAutoresizingMaskIntoConstraints = false
        watermark.text = "sensorithm v" + (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown")
        watermark.textColor = UIColor.white.withAlphaComponent(0.5)
        watermark.font = .preferredFont(forTextStyle: .caption1)
        watermark.shadowColor = UIColor.black.withAlphaComponent(0.5)
        watermark.shadowOffset = CGSize(width: 2, height: 2)
        watermark.layer.shadowRadius = 4
        status.translatesAutoresizingMaskIntoConstraints = false
        status.text = "Disconnected"
        status.textColor = .systemRed
        status.font = .preferredFont(forTextStyle: .headline)
        metrics.translatesAutoresizingMaskIntoConstraints = false; metrics.text = "0.0 FPS  -- °C"; metrics.textColor = .white; metrics.font = .preferredFont(forTextStyle: .caption1); view.addSubview(metrics)
        address.placeholder = "Server IP"
        address.text = config.serverAddress
        address.textColor = .white
        address.borderStyle = .roundedRect
        address.keyboardType = .URL
        address.delegate = self
        port.placeholder = "Port"
        port.text = String(config.serverPort)
        port.textColor = .white
        port.borderStyle = .roundedRect
        port.keyboardType = .numberPad
        port.delegate = self
        let keyboardDone = UIToolbar(); keyboardDone.sizeToFit()
        keyboardDone.items = [UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil), UIBarButtonItem(barButtonSystemItem: .done, target: self, action: #selector(dismissKeyboard))]
        port.inputAccessoryView = keyboardDone
        connect.setTitle("Connect", for: .normal)
        connect.addTarget(self, action: #selector(toggleConnection), for: .touchUpInside)
        configureIconButton(torch, image: "bolt.slash.fill", label: "Toggle torch", action: #selector(toggleTorch))
        configureIconButton(cameraButton, image: "camera.fill", label: "Camera quality", action: #selector(selectCamera))
        configureIconButton(recalibrate, image: "arrow.triangle.2.circlepath", label: "Recalibrate sensors", action: #selector(recalibrateSensors))
        let controls = UIStackView(arrangedSubviews: [address, port, connect])
        self.controls = controls
        controls.translatesAutoresizingMaskIntoConstraints = false
        controls.spacing = 8
        port.widthAnchor.constraint(equalToConstant: 70).isActive = true
        [torch, cameraButton, recalibrate].forEach { $0.widthAnchor.constraint(equalToConstant: 32).isActive = true }
        let settings = makeSettings()
        let preferences = makeConnectionPreferences()
        self.settings = settings
        self.preferences = preferences
        view.addSubview(cameraContainer)
        cameraContainer.addSubview(preview)
        cameraContainer.addSubview(overlay)
        cameraContainer.addSubview(watermark)
        view.addSubview(status)
        view.addSubview(controls)
        view.addSubview(settings)
        view.addSubview(preferences)
        let tap = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboard)); tap.cancelsTouchesInView = false; view.addGestureRecognizer(tap)
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(toggleCameraFullscreen)); doubleTap.numberOfTapsRequired = 2; cameraContainer.addGestureRecognizer(doubleTap)
        let fittedWidth = cameraContainer.widthAnchor.constraint(equalTo: view.safeAreaLayoutGuide.widthAnchor)
        let fittedHeight = cameraContainer.heightAnchor.constraint(equalTo: view.safeAreaLayoutGuide.heightAnchor)
        fittedWidth.priority = .defaultHigh
        fittedHeight.priority = .defaultHigh - 1
        cameraTopConstraint = cameraContainer.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 36)
        cameraBottomConstraint = cameraContainer.bottomAnchor.constraint(lessThanOrEqualTo: settings.topAnchor, constant: -8)
        cameraCenterConstraint = cameraContainer.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor)
        NSLayoutConstraint.activate([
            cameraContainer.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            cameraContainer.widthAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.widthAnchor),
            cameraContainer.heightAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.heightAnchor),
            fittedWidth,
            fittedHeight,
            preview.topAnchor.constraint(equalTo: cameraContainer.topAnchor),
            preview.leadingAnchor.constraint(equalTo: cameraContainer.leadingAnchor),
            preview.trailingAnchor.constraint(equalTo: cameraContainer.trailingAnchor),
            preview.bottomAnchor.constraint(equalTo: cameraContainer.bottomAnchor),
            overlay.topAnchor.constraint(equalTo: preview.topAnchor),
            overlay.leadingAnchor.constraint(equalTo: preview.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: preview.trailingAnchor),
            overlay.bottomAnchor.constraint(equalTo: preview.bottomAnchor),
            watermark.trailingAnchor.constraint(equalTo: cameraContainer.trailingAnchor, constant: -12),
            watermark.bottomAnchor.constraint(equalTo: cameraContainer.bottomAnchor, constant: -12),
            status.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            status.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            metrics.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            metrics.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            controls.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            controls.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            controls.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8),
            settings.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            settings.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            preferences.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            preferences.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            preferences.bottomAnchor.constraint(equalTo: controls.topAnchor, constant: -8),
            settings.bottomAnchor.constraint(equalTo: preferences.topAnchor, constant: -8),
        ])
        cameraTopConstraint?.isActive = true
        cameraBottomConstraint?.isActive = true
        setCameraAspectRatio(9.0 / 16.0)
        camera.restore(config.camera)
        overlay.offsetX = config.zoneOffsetX
        overlay.offsetY = config.zoneOffsetY
        overlay.onOffsetChanged = { [weak self] in self?.updateZones() }
        overlay.onOffsetFinished = { [weak self] in self?.saveAndPublish() }
        connection.onStateChanged = { [weak self] state in
            switch state {
            case .disconnected: self?.status.text = "Disconnected"; self?.status.textColor = .systemRed; self?.connect.setTitle("Connect", for: .normal); self?.stopTelemetry()
            case .connecting: self?.status.text = "Connecting..."; self?.status.textColor = .systemYellow; self?.connect.setTitle("Cancel", for: .normal)
            case .connected(let version): self?.status.text = "Connected - v\(version)"; self?.status.textColor = .systemGreen; self?.connect.setTitle("Disconnect", for: .normal); self?.processor.resetOutput(); self?.saveAndPublish(); self?.startTelemetry()
            case .rejected(let message): self?.status.text = message; self?.status.textColor = .systemRed; self?.connect.setTitle("Connect", for: .normal)
            }
        }
        connection.onMessage = { [weak self] in self?.handle($0) }
        connection.autoReconnectEnabled = { [weak self] in self?.config.autoReconnect ?? false }
        camera.onError = { [weak self] message in DispatchQueue.main.async { self?.status.text = message } }
        camera.onPreview = { [weak self] requestID, result in
            switch result {
            case .success(let preview): self?.connection.send(.preview(requestID: requestID, width: preview.1, height: preview.2, jpegBytes: preview.0))
            case .failure(let error): self?.connection.send(.error(operation: "preview", requestID: requestID, message: error.localizedDescription))
            }
        }
        camera.onFps = { [weak self] fps in DispatchQueue.main.async { self?.latestFps = fps; self?.renderMetrics() } }
        camera.onFrameGeometry = { [weak self] width, height in
            DispatchQueue.main.async {
                let rotated = width > height
                self?.frameGeometry = FrameGeometry(previewWidth: rotated ? height : width, previewHeight: rotated ? width : height, rawWidth: width, rawHeight: height, rotationDegrees: rotated ? 90 : 0)
                self?.setCameraAspectRatio(CGFloat(rotated ? height : width) / CGFloat(rotated ? width : height))
                self?.updateZones()
            }
        }
        camera.onFrame = { [weak self] bytes, width, height, stride in
            guard let self else { return }
            guard let mask = self.processor.processFrame(bytes, width: width, height: height, rowStride: stride) else { return }
            self.connection.sendMask(mask)
            DispatchQueue.main.async { self.overlay.activeMask = mask }
        }
        processor.setThreshold(Float(config.zones.threshold))
        UIDevice.current.isBatteryMonitoringEnabled = true
        NotificationCenter.default.addObserver(self, selector: #selector(batteryChanged), name: UIDevice.batteryLevelDidChangeNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(batteryChanged), name: UIDevice.batteryStateDidChangeNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(thermalStateChanged), name: ProcessInfo.thermalStateDidChangeNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(resumeCamera), name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        renderMetrics()
        updateZones()
        if config.autoConnectOnStartup, let host = address.text, let value = port.text.flatMap(UInt16.init) { connection.connect(address: host, port: value) }
    }

    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); updateZones() }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }
    override var shouldAutorotate: Bool { false }
    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); camera.attachPreview(to: preview); camera.start() }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); camera.stop() }
    deinit { telemetryTimer?.invalidate(); NotificationCenter.default.removeObserver(self); UIDevice.current.isBatteryMonitoringEnabled = false }

    @objc private func toggleConnection() {
        if connect.title(for: .normal) == "Connect" {
            guard let host = address.text, !host.isEmpty, let value = port.text.flatMap(UInt16.init) else { return }
            config.serverAddress = host; config.serverPort = Int(value); repository.save(config)
            connection.connect(address: host, port: value)
        } else { connection.disconnect() }
    }

    @objc private func dismissKeyboard() { view.endEditing(true) }

    @objc private func toggleCameraFullscreen() {
        let fullscreen = controls?.isHidden != true
        [controls, settings, preferences].forEach { $0?.isHidden = fullscreen }
        cameraTopConstraint?.isActive = !fullscreen
        cameraBottomConstraint?.isActive = !fullscreen
        cameraCenterConstraint?.isActive = fullscreen
        UIView.animate(withDuration: 0.2) { self.view.layoutIfNeeded() }
    }

    private func setCameraAspectRatio(_ ratio: CGFloat) {
        cameraAspectConstraint?.isActive = false
        cameraAspectConstraint = cameraContainer.widthAnchor.constraint(equalTo: cameraContainer.heightAnchor, multiplier: ratio)
        cameraAspectConstraint?.isActive = true
    }

    private func configureIconButton(_ button: UIButton, image: String, label: String, action: Selector) {
        if #available(iOS 13.0, *) { button.setImage(UIImage(systemName: image), for: .normal) }
        else { button.setTitle(label, for: .normal) }
        button.accessibilityLabel = label
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    @objc private func recalibrateSensors() { processor.recalibrate() }

    @objc private func keyboardChanged(_ notification: Notification) {
        guard let controls, let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        let keyboard = view.convert(frame, from: nil)
        let overlap = max(0, controls.frame.maxY - keyboard.minY + 8)
        UIView.animate(withDuration: notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? TimeInterval ?? 0.25) { controls.transform = CGAffineTransform(translationX: 0, y: -overlap) }
    }

    @objc private func selectCamera() {
        let picker = CameraOptionsViewController(options: camera.options()) { [weak self] option in
            guard let self else { return }
            self.config.camera = CameraConfig(deviceID: option.deviceID, width: option.width, height: option.height, fps: option.fps)
            self.repository.save(self.config); self.camera.select(option)
        }
        present(UINavigationController(rootViewController: picker), animated: true)
    }

    private func updateZones() {
        precondition(Thread.isMainThread)
        let frame = frameGeometry ?? FrameGeometry(previewWidth: max(1, Int(preview.bounds.width)), previewHeight: max(1, Int(preview.bounds.height)), rawWidth: max(1, Int(preview.bounds.width)), rawHeight: max(1, Int(preview.bounds.height)), rotationDegrees: 0)
        let key = "\(frame):\(config.zones):\(overlay.offsetX):\(overlay.offsetY)"
        guard key != zoneKey else { return }
        zoneKey = key
        let layout = SensorZoneLayoutCalculator.calculate(frame: frame, settings: config.zones.zoneSettings, offsetX: overlay.offsetX, offsetY: overlay.offsetY)
        processor.configureZones(layout.nativeConfig)
        overlay.coordinateSize = CGSize(width: frame.previewWidth, height: frame.previewHeight)
        overlay.layout = layout
    }

    private func makeSettings() -> UIStackView {
        let names = ["Size X", "Size Y", "Spacing", "Angle", "Exposure", "Threshold"]
        let current = [config.zones.sizePercentX, config.zones.sizePercentY, config.zones.spacingPercent, config.zones.angleDegrees, config.exposure, config.zones.threshold]
        let maxima: [Float] = [100, 50, 100, 360, 100, 255]
        let stack = UIStackView(); stack.axis = .vertical; stack.spacing = 2; stack.translatesAutoresizingMaskIntoConstraints = false
        for index in names.indices {
            let label = UILabel(), value = UILabel(), slider = UISlider(), row = UIStackView()
            label.text = names[index]; label.textColor = .white; label.font = .preferredFont(forTextStyle: .caption1)
            label.widthAnchor.constraint(equalToConstant: 70).isActive = true
            value.textColor = .white; value.font = .preferredFont(forTextStyle: .caption1); value.textAlignment = .right; value.widthAnchor.constraint(equalToConstant: 42).isActive = true
            slider.maximumValue = maxima[index]; slider.value = Float(current[index]); slider.tag = index
            slider.addTarget(self, action: #selector(sliderChanged(_:)), for: .valueChanged)
            slider.addTarget(self, action: #selector(sliderFinished), for: [.touchUpInside, .touchUpOutside])
            row.spacing = 8; row.addArrangedSubview(label); row.addArrangedSubview(slider); row.addArrangedSubview(value)
            stack.addArrangedSubview(row); sliders.append(slider); values.append(value)
        }
        renderSliderValues()
        return stack
    }

    private func makeConnectionPreferences() -> UIStackView {
        let reconnect = UISwitch(), autoConnect = UISwitch()
        let flashMargin = UIView(); flashMargin.widthAnchor.constraint(equalToConstant: 16).isActive = true; flashMargin.setContentCompressionResistancePriority(.required, for: .horizontal)
        reconnect.isOn = config.autoReconnect; autoConnect.isOn = config.autoConnectOnStartup
        reconnect.tag = 0; autoConnect.tag = 1
        reconnect.addTarget(self, action: #selector(preferenceChanged(_:)), for: .valueChanged)
        autoConnect.addTarget(self, action: #selector(preferenceChanged(_:)), for: .valueChanged)
        reconnectLabel.text = "A.Reconnect"; autoConnectLabel.text = "A.Connect"
        [reconnectLabel, autoConnectLabel].forEach { $0.textColor = .white; $0.font = .preferredFont(forTextStyle: .caption1) }
        let stack = UIStackView(arrangedSubviews: [reconnectLabel, reconnect, autoConnectLabel, autoConnect, flashMargin, torch, cameraButton, recalibrate]); stack.translatesAutoresizingMaskIntoConstraints = false; stack.spacing = 8
        return stack
    }

    @objc private func preferenceChanged(_ sender: UISwitch) {
        if sender.tag == 0 { config.autoReconnect = sender.isOn } else { config.autoConnectOnStartup = sender.isOn }
        repository.save(config)
    }

    @objc private func sliderChanged(_ slider: UISlider) {
        switch slider.tag {
        case 0: config.zones.sizePercentX = Int(slider.value)
        case 1: config.zones.sizePercentY = Int(slider.value)
        case 2: config.zones.spacingPercent = Int(slider.value)
        case 3: config.zones.angleDegrees = Int(slider.value)
        case 4: config.exposure = Int(slider.value); camera.setExposure(config.exposure)
        default: config.zones.threshold = Int(slider.value); processor.setThreshold(Float(config.zones.threshold))
        }
        renderSliderValues(); updateZones()
    }

    @objc private func sliderFinished() { saveAndPublish() }

    private func renderSliderValues() {
        let current = [config.zones.sizePercentX, config.zones.sizePercentY, config.zones.spacingPercent, config.zones.angleDegrees, config.exposure, config.zones.threshold]
        for index in values.indices { values[index].text = index < 3 ? "\(current[index])%" : "\(current[index])" }
    }

    private func saveAndPublish() {
        config.zoneOffsetX = overlay.offsetX; config.zoneOffsetY = overlay.offsetY; repository.save(config)
        connection.send(.settings(SyncedSettings(sizeX: config.zones.sizePercentX, sizeY: config.zones.sizePercentY, spacing: config.zones.spacingPercent, angle: config.zones.angleDegrees, exposure: config.exposure, threshold: config.zones.threshold, offsetX: overlay.offsetX, offsetY: overlay.offsetY)))
    }

    private func handle(_ message: ServerMessage) {
        switch message {
        case .recalibrate: processor.recalibrate()
        case .settings(let settings):
            config.zones = ZoneSettingsCodable(sizePercentX: settings.sizeX, sizePercentY: settings.sizeY, spacingPercent: settings.spacing, angleDegrees: settings.angle, threshold: settings.threshold); config.exposure = settings.exposure; overlay.offsetX = settings.offsetX; overlay.offsetY = settings.offsetY; processor.setThreshold(Float(settings.threshold)); camera.setExposure(settings.exposure); for (index, value) in [settings.sizeX, settings.sizeY, settings.spacing, settings.angle, settings.exposure, settings.threshold].enumerated() { sliders[index].value = Float(value) }; renderSliderValues(); updateZones(); repository.save(config)
        case .setTorch(let enabled): if !camera.setTorch(enabled) { connection.send(.error(operation: "torch", requestID: nil, message: "Flash is unavailable")) }
        case .restartCamera: camera.stop(); camera.start()
        case .requestPreview(let requestID):
            if !camera.requestPreview(requestID) { connection.send(.error(operation: "preview", requestID: requestID, message: "Camera is unavailable or another preview is pending")) }
        }
    }

    @objc private func toggleTorch() {
        let enabled = !torchEnabled
        guard camera.setTorch(enabled) else { torch.isEnabled = false; return }
        torchEnabled = enabled
        if #available(iOS 13.0, *) { torch.setImage(UIImage(systemName: enabled ? "bolt.fill" : "bolt.slash.fill"), for: .normal) }
    }
    @objc private func resumeCamera() { camera.start() }
    private func startTelemetry() {
        batteryChanged()
        telemetryTimer?.invalidate()
        telemetryTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.batteryChanged() }
    }
    private func stopTelemetry() { telemetryTimer?.invalidate(); telemetryTimer = nil }
    @objc private func batteryChanged() { renderMetrics(); let level = max(0, Int((UIDevice.current.batteryLevel * 100).rounded())); let charging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full; connection.send(.telemetry(batteryPercent: level, temperatureCelsius: thermalState().celsius, charging: charging)) }
    @objc private func thermalStateChanged() { batteryChanged() }
    private func thermalState() -> (label: String, celsius: Float) {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: return ("Nominal", 30)
        case .fair: return ("Fair", 40)
        case .serious: return ("Serious", 50)
        case .critical: return ("Critical", 60)
        @unknown default: return ("Unknown", 0)
        }
    }
    private func renderMetrics() { metrics.text = String(format: "%.1f FPS  %@", latestFps, thermalState().label) }
}

private final class CameraOptionsViewController: UITableViewController {
    private let options: [CameraController.Option]
    private let select: (CameraController.Option) -> Void
    private var deviceID: String?
    private var fps: Int?

    init(options: [CameraController.Option], select: @escaping (CameraController.Option) -> Void) {
        self.options = options; self.select = select; super.init(style: .plain)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad(); title = "Camera"; navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(close))
    }

    private var visible: [CameraController.Option] {
        if let deviceID, let fps { return options.filter { $0.deviceID == deviceID && $0.fps == fps } }
        if let deviceID { return options.filter { $0.deviceID == deviceID } }
        return Array(Dictionary(grouping: options, by: \ .deviceID).values.compactMap(\.first)).sorted { $0.position == .back && $1.position != .back }
    }

    private var frameRates: [Int] { Array(Set(visible.map(\.fps))).sorted(by: >) }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        if deviceID != nil, fps == nil { return frameRates.count }
        return visible.count
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "option") ?? UITableViewCell(style: .default, reuseIdentifier: "option")
        if deviceID != nil, fps == nil { cell.textLabel?.text = "\(frameRates[indexPath.row]) FPS" }
        else if deviceID != nil { let option = visible.sorted { $0.width * $0.height > $1.width * $1.height }[indexPath.row]; cell.textLabel?.text = "\(option.width)×\(option.height)" }
        else { let option = visible[indexPath.row]; cell.textLabel?.text = option.position == .front ? "Front camera" : "Back camera" }
        cell.accessoryType = deviceID == nil || fps == nil ? .disclosureIndicator : .none
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        if deviceID == nil { deviceID = visible[indexPath.row].deviceID; title = "Frame rate" }
        else if fps == nil { fps = frameRates[indexPath.row]; title = "Resolution" }
        else { select(visible.sorted { $0.width * $0.height > $1.width * $1.height }[indexPath.row]); dismiss(animated: true) }
        tableView.reloadData()
    }
    @objc private func close() { dismiss(animated: true) }
}

extension MainViewController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool { dismissKeyboard(); return true }
}
#endif
