// iOS 학생 앱 (SwiftUI, iOS 16+)
// 필요: Xcode > Signing & Capabilities > "Family Controls (Distribution)" 권한
//       (Apple에 별도 신청/승인 필요: developer.apple.com/contact/request/family-controls-distribution)
import SwiftUI
import FamilyControls
import ManagedSettings

final class ShieldManager: ObservableObject {
    private let store = ManagedSettingsStore()
    @Published var selection = FamilyActivitySelection()   // 학생이 미리 고르는 '수업 중 허용 앱'

    func authorize() async {
        try? await AuthorizationCenter.shared.requestAuthorization(for: .individual)
    }
    func lock(allowPreset: Bool) {
        let allowed = allowPreset ? selection.applicationTokens : []
        store.shield.applicationCategories = .all(except: allowed)
        store.shield.webDomainCategories = .all()
        store.application.denyAppRemoval = true          // 수업 중 앱 삭제 금지
    }
    func unlock() { store.clearAllSettings() }
}

@MainActor
final class ClassClient: ObservableObject {
    @Published var status = "미연결"
    @Published var active = false
    let shield = ShieldManager()
    private var task: URLSessionWebSocketTask?
    private var params: (url: String, code: String, name: String)?
    private let deviceId: String = {
        if let v = UserDefaults.standard.string(forKey: "id") { return v }
        let v = UUID().uuidString; UserDefaults.standard.set(v, forKey: "id"); return v
    }()

    func connect(url: String, code: String, name: String) {
        params = (url, code, name)
        guard let u = URL(string: url) else { status = "주소 오류"; return }
        task?.cancel()
        task = URLSession.shared.webSocketTask(with: u)
        task?.resume()
        send(["type": "hello", "role": "student", "platform": "ios", "code": code, "name": name, "id": deviceId])
        status = "연결됨"
        listen()
    }
    private func send(_ o: [String: Any]) {
        guard let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return }
        task?.send(.string(s)) { _ in }
    }
    private func listen() {
        task?.receive { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .success(.string(let s)): self.handle(s); self.listen()
                case .success: self.listen()
                case .failure:
                    self.status = "끊김 - 재연결 중"
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    if let p = self.params { self.connect(url: p.url, code: p.code, name: p.name) }
                }
            }
        }
    }
    private func handle(_ s: String) {
        guard let d = s.data(using: .utf8), let m = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              m["type"] as? String == "state" else { return }
        active = m["active"] as? Bool ?? false
        if active { shield.lock(allowPreset: m["iosAllowPreset"] as? Bool ?? false) } else { shield.unlock() }
    }
}

struct ContentView: View {
    @StateObject private var c = ClassClient()
    @State private var url = "wss://YOUR_SERVER/ws"
    @State private var code = ""
    @State private var name = ""
    @State private var picking = false

    var body: some View {
        Form {
            Section("수업 연결") {
                TextField("서버 주소", text: $url).textInputAutocapitalization(.never)
                TextField("수업 코드", text: $code).keyboardType(.numberPad)
                TextField("이름", text: $name)
                Button("연결") { c.connect(url: url, code: code, name: name) }
                Text("상태: \(c.status)\(c.active ? " · 📵 수업 중" : "")")
            }
            Section("준비 (수업 전에 한 번)") {
                Button("스크린 타임 권한 허용") { Task { await c.shield.authorize() } }
                Button("수업 중 허용할 앱 고르기") { picking = true }
            }
        }
        .familyActivityPicker(isPresented: $picking, selection: $c.shield.selection)
    }
}

@main struct ClassFocusApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}
