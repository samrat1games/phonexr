import SwiftUI
import AppKit

#if PHONEXR_LITE
private let productName = "PhoneXR Lite Share"
#else
private let productName = "PhoneXR Share"
#endif

@main
struct PhoneXRShareApp: App {
    @StateObject private var model = ShareModel()
    var body: some Scene {
        WindowGroup(productName) { ShareView().environmentObject(model).frame(minWidth: 760, minHeight: 540) }
            .windowStyle(.titleBar).defaultSize(width: 900, height: 650)
    }
}

struct ShareView: View {
    @EnvironmentObject var model: ShareModel
    @StateObject private var stream = ScreenStream()
    var body: some View {
        NavigationSplitView {
            List(selection: $model.page) {
                Label("Передача экрана", systemImage: "display.and.arrow.down").tag(SharePage.screen)
                Label("PhoneXR для Android", systemImage: "visionpro").tag(SharePage.android)
            }.navigationTitle(productName)
        } detail: {
            switch model.page {
            case .screen: screenPage
            case .android: androidPage
            }
        }
        .onAppear {
            if let url = Bundle.main.url(forResource: "PhoneXRShare", withExtension: "png"),
               let icon = NSImage(contentsOf: url) { NSApp.applicationIconImage = icon }
        }
    }

    private var screenPage: some View {
        page("Передача экрана", "Покажите экран Mac в пространственном окне PhoneXR.") {
            GroupBox {
                VStack(alignment: .leading, spacing: 16) {
                    Label(stream.status, systemImage: stream.running ? "dot.radiowaves.left.and.right" : "display")
                    Button(stream.running ? "Остановить передачу" : "Передавать экран") { stream.toggle() }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                    Text("Mac и телефон должны быть в одной локальной сети. При первом запуске разрешите запись экрана.")
                        .font(.callout).foregroundStyle(.secondary)
                }.padding(10).frame(maxWidth: .infinity, alignment: .leading)
            }
            #if PHONEXR_LITE
            Label("Lite: 10 кадров/с, уменьшенное разрешение и JPEG 55% для слабых Mac.", systemImage: "leaf.fill")
                .foregroundStyle(.green)
            #else
            Text("Полная версия передаёт до 20 кадров/с с повышенным качеством.").foregroundStyle(.secondary)
            #endif
        }
    }

    private var androidPage: some View {
        page("PhoneXR для Android", "Свежий APK уже находится внутри приложения.") {
            GroupBox("Подключённый телефон") {
                VStack(alignment: .leading, spacing: 14) {
                    Picker("Устройство", selection: $model.selectedSerial) {
                        Text("Телефон не выбран").tag(nil as String?)
                        ForEach(model.devices) { Text($0.model).tag($0.serial as String?) }
                    }
                    HStack {
                        Button("Обновить список") { model.refresh() }
                        Button("Установить новый PhoneXR") { model.install() }
                            .buttonStyle(.borderedProminent).disabled(!model.canInstall)
                    }
                    Text(model.status).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                }.padding(10)
            }
            Text("На телефоне включите «Для разработчиков → Отладка по USB», подключите data‑кабель и подтвердите ключ RSA.")
                .font(.callout).foregroundStyle(.secondary)
        }
    }

    private func page<Content: View>(_ title: String, _ subtitle: String, @ViewBuilder content: () -> Content) -> some View {
        ScrollView { VStack(alignment: .leading, spacing: 20) {
            Text(title).font(.largeTitle.bold()); Text(subtitle).font(.title3).foregroundStyle(.secondary); content()
        }.padding(30).frame(maxWidth: 820, alignment: .leading) }.navigationTitle(title)
    }
}

enum SharePage: Hashable { case screen, android }

struct Device: Identifiable, Hashable {
    let serial: String; let model: String; let state: String
    var id: String { serial }
    var isReady: Bool { state == "device" }
}

@MainActor
final class ShareModel: ObservableObject {
    @Published var page: SharePage = .screen
    @Published var devices: [Device] = []
    @Published var selectedSerial: String?
    @Published var status = "Подключите Android по USB."
    @Published var busy = false
    private let bridge = AndroidBridge()
    var canInstall: Bool { !busy && devices.first { $0.serial == selectedSerial }?.isReady == true }

    init() { refresh() }
    func refresh() {
        busy = true; status = "Поиск Android…"; let bridge = bridge
        Task.detached {
            let result = Result { try bridge.devices() }
            await MainActor.run {
                self.busy = false
                switch result {
                case .success(let found):
                    self.devices = found; if self.selectedSerial == nil { self.selectedSerial = found.first?.serial }
                    self.status = found.isEmpty ? "Android не найден." : "Телефон найден. Можно установить PhoneXR."
                case .failure(let error): self.status = error.localizedDescription
                }
            }
        }
    }
    func install() {
        guard let serial = selectedSerial, let apk = Bundle.main.url(forResource: "PhoneXR", withExtension: "apk") else {
            status = "Встроенный PhoneXR.apk не найден."; return
        }
        busy = true; status = "Установка PhoneXR…"; let bridge = bridge
        Task.detached {
            let result = Result { try bridge.install(apk: apk, serial: serial) }
            await MainActor.run { self.busy = false; self.status = result.fold({ $0 }, { $0.localizedDescription }) }
        }
    }
}

private extension Result {
    func fold<T>(_ success: (Success) -> T, _ failure: (Failure) -> T) -> T {
        switch self { case .success(let value): return success(value); case .failure(let error): return failure(error) }
    }
}
