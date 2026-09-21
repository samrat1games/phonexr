import AppKit
import Network
import SwiftUI

@MainActor
final class ScreenStream: ObservableObject {
    @Published var running = false
    @Published var status = "Остановлено"
    private var listener: NWListener?
    private var clients: [NWConnection] = []
    private var timer: Timer?
    private var announceTimer: Timer?
    private var announcer: NWConnection?
    #if PHONEXR_LITE
    private let frameInterval = 1.0 / 10.0
    private let jpegQuality = 0.55
    private let maxWidth = 1280
    #else
    private let frameInterval = 1.0 / 20.0
    private let jpegQuality = 0.78
    private let maxWidth = 2560
    #endif

    func toggle() { running ? stop() : start() }

    private func start() {
        do {
            let listener = try NWListener(using: .tcp, on: 24820)
            listener.newConnectionHandler = { [weak self] connection in
                connection.start(queue: .main)
                Task { @MainActor in self?.clients.append(connection); self?.status = "PhoneXR подключён · передача экрана" }
            }
            listener.stateUpdateHandler = { [weak self] state in
                if case .failed(let error) = state { Task { @MainActor in self?.status = error.localizedDescription; self?.stop() } }
            }
            listener.start(queue: .main)
            self.listener = listener
            announcer = NWConnection(host: "255.255.255.255", port: 24819, using: .udp)
            announcer?.start(queue: .main)
            announceTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    let name = Host.current().localizedName ?? "Mac"
                    self?.announcer?.send(content: Data("PHONEXR_DESKTOP_V1 24820 \(name)".utf8), completion: .contentProcessed { _ in })
                }
            }
            running = true; status = "Ожидание PhoneXR в локальной сети…"
            timer = Timer.scheduledTimer(withTimeInterval: frameInterval, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.sendFrame() }
            }
        } catch { status = error.localizedDescription }
    }

    func stop() {
        timer?.invalidate(); timer = nil; announceTimer?.invalidate(); announceTimer = nil
        announcer?.cancel(); announcer = nil; listener?.cancel(); listener = nil
        clients.forEach { $0.cancel() }; clients.removeAll(); running = false; status = "Остановлено"
    }

    private func sendFrame() {
        guard !clients.isEmpty, let source = CGDisplayCreateImage(CGMainDisplayID()) else { return }
        let scale = min(1, CGFloat(maxWidth) / CGFloat(source.width))
        let widthPixels = Int(CGFloat(source.width) * scale), heightPixels = Int(CGFloat(source.height) * scale)
        guard let context = CGContext(data: nil, width: widthPixels, height: heightPixels, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
        context.interpolationQuality = .medium; context.draw(source, in: CGRect(x: 0, y: 0, width: widthPixels, height: heightPixels))
        guard let image = context.makeImage(), let jpeg = NSBitmapImageRep(cgImage: image).representation(using: .jpeg, properties: [.compressionFactor: jpegQuality]) else { return }
        let width = UInt16(clamping: image.width), height = UInt16(clamping: image.height), size = UInt32(jpeg.count)
        var packet = Data("PXS1".utf8)
        [UInt8(width >> 8), UInt8(width & 255), UInt8(height >> 8), UInt8(height & 255),
         UInt8(size >> 24), UInt8((size >> 16) & 255), UInt8((size >> 8) & 255), UInt8(size & 255)].forEach { packet.append($0) }
        packet.append(jpeg)
        clients.forEach { $0.send(content: packet, completion: .contentProcessed { _ in }) }
    }
}
