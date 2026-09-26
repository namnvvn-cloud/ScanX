import AVFoundation
import SwiftUI
import UIKit

/// Lớp phủ vẽ bản dịch đè lên preview camera (FIT như preview .resizeAspect) — tương đương Canvas lớp phủ
/// của CameraTranslateScreen bên Android, dùng chung PhotoTranslateRenderer.drawBlock.
final class LiveOverlayView: UIView {
    var frameData: LiveTranslator.Frame? {
        didSet { setNeedsDisplay() }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        contentMode = .redraw
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) không dùng")
    }

    override func draw(_ rect: CGRect) {
        guard let f = frameData, f.width > 0, f.height > 0, let cg = UIGraphicsGetCurrentContext() else { return }
        let scale = min(bounds.width / CGFloat(f.width), bounds.height / CGFloat(f.height))
        let ox = (bounds.width - CGFloat(f.width) * scale) / 2
        let oy = (bounds.height - CGFloat(f.height) * scale) / 2
        cg.saveGState()
        cg.translateBy(x: ox, y: oy)
        cg.scaleBy(x: scale, y: scale)
        for b in f.blocks {
            if let text = b.translation, !text.isEmpty {
                PhotoTranslateRenderer.drawBlock(cg, block: b.block, text: text, colors: b.colors)
            }
        }
        cg.restoreGState()
    }
}

/// Nhận ảnh chụp độ phân giải cao (nút chụp → dịch online).
final class PhotoCaptureHandler: NSObject, AVCapturePhotoCaptureDelegate {
    private let completion: (UIImage?) -> Void

    init(completion: @escaping (UIImage?) -> Void) {
        self.completion = completion
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let image = photo.fileDataRepresentation().flatMap { UIImage(data: $0) }
        DispatchQueue.main.async { self.completion(image) }
    }
}

/// Camera: preview 4:3 (.resizeAspect), khung phân tích cho LiveTranslator, chụp ảnh cho dịch online.
final class LiveCameraViewController: UIViewController {
    let translator: LiveTranslator
    var onCapture: ((UIImage) -> Void)?
    var onStatus: ((String?) -> Void)?

    private let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let sessionQueue = DispatchQueue(label: "scanx.live.session")
    private let videoQueue = DispatchQueue(label: "scanx.live.video")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let overlay = LiveOverlayView(frame: .zero)
    private var captureHandler: PhotoCaptureHandler?

    init(translator: LiveTranslator) {
        self.translator = translator
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) không dùng")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspect
        view.layer.addSublayer(layer)
        previewLayer = layer
        view.addSubview(overlay)
        translator.onFrame = { [weak self] frame in
            self?.overlay.frameData = frame
            self?.onStatus?(frame?.status)
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    if granted {
                        self.startSession()
                    } else {
                        self.onStatus?("Chưa cấp quyền camera: vào Cài đặt → ScanX → Camera.")
                    }
                }
            }
        default:
            onStatus?("Chưa cấp quyền camera: vào Cài đặt → ScanX → Camera.")
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        overlay.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        translator.enabled = false
        let session = self.session
        sessionQueue.async { session.stopRunning() }
    }

    func capture() {
        let handler = PhotoCaptureHandler { [weak self] image in
            guard let self, let image else { return }
            self.onCapture?(image)
        }
        captureHandler = handler
        photoOutput.capturePhoto(with: AVCapturePhotoSettings(), delegate: handler)
    }

    private func startSession() {
        let session = self.session
        let photoOutput = self.photoOutput
        let videoOutput = self.videoOutput
        let translator = self.translator
        let videoQueue = self.videoQueue
        sessionQueue.async {
            session.beginConfiguration()
            session.sessionPreset = .photo
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input)
            else {
                session.commitConfiguration()
                return
            }
            session.addInput(input)
            videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.setSampleBufferDelegate(translator, queue: videoQueue)
            if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
            if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
            // Khung phân tích + ảnh chụp đều quay dọc → toạ độ khớp preview dọc.
            for output in [videoOutput as AVCaptureOutput, photoOutput as AVCaptureOutput] {
                guard let connection = output.connection(with: .video) else { continue }
                if #available(iOS 17.0, *) {
                    if connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
                } else if connection.isVideoOrientationSupported {
                    connection.videoOrientation = .portrait
                }
            }
            session.commitConfiguration()
            session.startRunning()
        }
    }
}

/// Giữ tham chiếu tới camera để nút SwiftUI gọi chụp.
final class LiveCameraBridge: ObservableObject {
    let translator = LiveTranslator()
    weak var controller: LiveCameraViewController?
    @Published var status: String?
}

private struct LiveCameraRepresentable: UIViewControllerRepresentable {
    let bridge: LiveCameraBridge
    let onCapture: (UIImage) -> Void

    func makeUIViewController(context: Context) -> LiveCameraViewController {
        let vc = LiveCameraViewController(translator: bridge.translator)
        vc.onCapture = onCapture
        vc.onStatus = { [weak bridge] status in bridge?.status = status }
        bridge.controller = vc
        return vc
    }

    func updateUIViewController(_ uiViewController: LiveCameraViewController, context: Context) {}
}

/// Màn "Dịch trực tiếp" — chip hệ chữ (Anh/Latin · 中文 · 日本語 · 한국어), bật/tắt dịch trực tiếp,
/// nút chụp → dịch online ảnh độ phân giải cao (như Android bản 1.1).
struct LiveTranslateScreen: View {
    let onCapture: (UIImage) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var bridge = LiveCameraBridge()
    @State private var script: LiveTranslator.Script = .latin
    @State private var liveOn = true

    var body: some View {
        ZStack {
            LiveCameraRepresentable(bridge: bridge, onCapture: onCapture)
                .ignoresSafeArea()

            VStack(spacing: 10) {
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.title3.bold())
                            .padding(10)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    Spacer()
                    Button {
                        liveOn.toggle()
                    } label: {
                        Text(liveOn ? "Dịch trực tiếp: Bật" : "Dịch trực tiếp: Tắt")
                            .font(.footnote.bold())
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(.ultraThinMaterial, in: Capsule())
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(LiveTranslator.Script.allCases) { s in
                            Button(s.label) { script = s }
                                .font(.footnote.bold())
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(script == s ? Color.accentColor : Color.black.opacity(0.4), in: Capsule())
                                .foregroundStyle(Color.white)
                        }
                    }
                }
                if let status = bridge.status, liveOn {
                    Text(status)
                        .font(.caption)
                        .multilineTextAlignment(.center)
                        .padding(8)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                }
                Spacer()
                Button {
                    bridge.controller?.capture()
                } label: {
                    Circle()
                        .strokeBorder(Color.white, lineWidth: 4)
                        .background(Circle().fill(Color.white.opacity(0.3)))
                        .frame(width: 72, height: 72)
                }
                .accessibilityLabel("Chụp để dịch chính xác hơn (online)")
                Text("Chụp để dịch chính xác hơn (online)")
                    .font(.caption)
                    .foregroundStyle(Color.white)
                    .shadow(radius: 2)
            }
            .padding()
        }
        .onChange(of: script) { newValue in
            bridge.translator.script = newValue
        }
        .onChange(of: liveOn) { newValue in
            bridge.translator.enabled = newValue
        }
    }
}
