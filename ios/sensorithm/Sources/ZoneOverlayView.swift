#if canImport(UIKit)
import UIKit

final class ZoneOverlayView: UIView {
    var offsetX: Float = 0.5 { didSet { setNeedsDisplay() } }
    var offsetY: Float = 0.5 { didSet { setNeedsDisplay() } }
    var coordinateSize = CGSize.zero { didSet { setNeedsDisplay() } }
    var videoRect = CGRect.zero { didSet { setNeedsDisplay() } }
    var layout: ZoneLayout? { didSet { activeMask = 0; setNeedsDisplay() } }
    var activeMask: UInt8 = 0 { didSet { setNeedsDisplay() } }
    var onOffsetChanged: (() -> Void)?
    var onOffsetFinished: (() -> Void)?
    private var lastTouch = CGPoint.zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func draw(_ rect: CGRect) {
        UIGraphicsGetCurrentContext()?.clear(bounds)
        guard let layout, coordinateSize.width > 0, coordinateSize.height > 0 else { return }
        let videoRect = videoRect.isEmpty ? bounds : videoRect
        let scale = min(videoRect.width / coordinateSize.width, videoRect.height / coordinateSize.height)
        let offset = CGPoint(x: videoRect.midX - coordinateSize.width * scale / 2, y: videoRect.midY - coordinateSize.height * scale / 2)
        let width = CGFloat(layout.sensorWidth) * scale, height = CGFloat(layout.sensorHeight) * scale
        for (index, center) in layout.centers.enumerated() {
            let box = CGRect(x: offset.x + CGFloat(center.0) * scale - width / 2, y: offset.y + CGFloat(center.1) * scale - height / 2, width: width, height: height)
            let active = activeMask & (1 << index) != 0
            let color: UIColor = active ? (index == 5 ? .systemBlue : .systemRed) : .lightGray
            color.withAlphaComponent(active ? 0.3 : 0.25).setFill(); UIRectFill(box)
            color.withAlphaComponent(0.85).setStroke(); UIBezierPath(rect: box).stroke()
            let text = String(6 - index) as NSString
            let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: max(10, min(width, height) * 0.7)), .foregroundColor: UIColor.white]
            let size = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: box.midX - size.width / 2, y: box.midY - size.height / 2), withAttributes: attributes)
        }
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) { lastTouch = touches.first?.location(in: self) ?? .zero }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let point = touches.first?.location(in: self), coordinateSize.width > 0, coordinateSize.height > 0 else { return }
        let videoRect = videoRect.isEmpty ? bounds : videoRect
        let scale = min(videoRect.width / coordinateSize.width, videoRect.height / coordinateSize.height)
        offsetX = min(1, max(0, offsetX + Float((point.x - lastTouch.x) / (coordinateSize.width * scale))))
        offsetY = min(1, max(0, offsetY + Float((point.y - lastTouch.y) / (coordinateSize.height * scale))))
        lastTouch = point; onOffsetChanged?()
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { onOffsetFinished?() }
}
#endif
