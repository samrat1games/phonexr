#include "openvr_driver.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
using SocketHandle = SOCKET;
static constexpr SocketHandle INVALID_SOCKET_HANDLE = INVALID_SOCKET;
static void close_socket(SocketHandle socket) { closesocket(socket); }
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
using SocketHandle = int;
static constexpr SocketHandle INVALID_SOCKET_HANDLE = -1;
static void close_socket(SocketHandle socket) { close(socket); }
#endif

namespace {
constexpr uint16_t kPort = 24821;

struct Quaternion { double x = 0, y = 0, z = 0, w = 1; };
struct Body {
    bool present = false;
    double x = 0, y = 0, z = 0;
    Quaternion q;
    float trigger = 0, grip = 0;
};
struct TrackingState {
    Body head, left, right;
    std::chrono::steady_clock::time_point received{};
};

std::mutex state_mutex;
TrackingState state;
std::atomic<bool> receiving{false};
std::thread receiver;
SocketHandle receiver_socket = INVALID_SOCKET_HANDLE;

bool read_body(std::istringstream &input, Body &body) {
    int present = 0;
    if (!(input >> present >> body.x >> body.y >> body.z >> body.q.x >> body.q.y >> body.q.z >> body.q.w
          >> body.trigger >> body.grip)) return false;
    body.present = present != 0;
    return true;
}

void receive_loop() {
#ifdef _WIN32
    WSADATA data{};
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) return;
#endif
    receiver_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (receiver_socket == INVALID_SOCKET_HANDLE) return;
    int reuse = 1;
    setsockopt(receiver_socket, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char *>(&reuse), sizeof(reuse));
#ifdef _WIN32
    DWORD timeout = 250;
    setsockopt(receiver_socket, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char *>(&timeout), sizeof(timeout));
#else
    timeval timeout{0, 250000};
    setsockopt(receiver_socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
#endif
    sockaddr_in address{};
    address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_ANY); address.sin_port = htons(kPort);
    if (bind(receiver_socket, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0) {
        close_socket(receiver_socket); receiver_socket = INVALID_SOCKET_HANDLE; return;
    }
    char buffer[2048];
    while (receiving.load()) {
        const int count = recv(receiver_socket, buffer, sizeof(buffer) - 1, 0);
        if (count <= 0) continue;
        buffer[count] = 0;
        std::istringstream input(buffer);
        std::string magic; TrackingState next; input >> magic;
        if (magic != "PXRVR1") continue;
        next.head.present = true;
        if (!(input >> next.head.x >> next.head.y >> next.head.z >> next.head.q.x >> next.head.q.y >> next.head.q.z >> next.head.q.w)) continue;
        if (!read_body(input, next.left) || !read_body(input, next.right)) continue;
        next.received = std::chrono::steady_clock::now();
        std::lock_guard lock(state_mutex); state = next;
    }
    if (receiver_socket != INVALID_SOCKET_HANDLE) close_socket(receiver_socket);
    receiver_socket = INVALID_SOCKET_HANDLE;
#ifdef _WIN32
    WSACleanup();
#endif
}

Quaternion multiply(const Quaternion &a, const Quaternion &b) {
    return {a.w*b.x + a.x*b.w + a.y*b.z - a.z*b.y,
            a.w*b.y - a.x*b.z + a.y*b.w + a.z*b.x,
            a.w*b.z + a.x*b.y - a.y*b.x + a.z*b.w,
            a.w*b.w - a.x*b.x - a.y*b.y - a.z*b.z};
}

void rotate(const Quaternion &q, double x, double y, double z, double &out_x, double &out_y, double &out_z) {
    const Quaternion point{x, y, z, 0};
    const Quaternion inverse{-q.x, -q.y, -q.z, q.w};
    const Quaternion result = multiply(multiply(q, point), inverse);
    out_x = result.x; out_y = result.y; out_z = result.z;
}

class Display final : public vr::IVRDisplayComponent {
public:
    void GetWindowBounds(int32_t *x, int32_t *y, uint32_t *width, uint32_t *height) override {
        *x = 0; *y = 0; *width = 1920; *height = 1080;
    }
    bool IsDisplayOnDesktop() override { return false; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t *width, uint32_t *height) override { *width = 1512; *height = 1680; }
    void GetEyeOutputViewport(vr::EVREye eye, uint32_t *x, uint32_t *y, uint32_t *width, uint32_t *height) override {
        *x = eye == vr::Eye_Left ? 0 : 960; *y = 0; *width = 960; *height = 1080;
    }
    void GetProjectionRaw(vr::EVREye, float *left, float *right, float *top, float *bottom) override {
        *left = -1.f; *right = 1.f; *top = -1.f; *bottom = 1.f;
    }
    vr::DistortionCoordinates_t ComputeDistortion(vr::EVREye, float u, float v) override {
        vr::DistortionCoordinates_t out{};
        out.rfRed[0] = out.rfGreen[0] = out.rfBlue[0] = u;
        out.rfRed[1] = out.rfGreen[1] = out.rfBlue[1] = v;
        return out;
    }
    bool ComputeInverseDistortion(vr::HmdVector2_t *out, vr::EVREye, uint32_t, float u, float v) override {
        out->v[0] = u; out->v[1] = v; return true;
    }
};

enum class DeviceKind { Head, Left, Right };
class Device final : public vr::ITrackedDeviceServerDriver {
public:
    explicit Device(DeviceKind kind) : kind_(kind) {}
    vr::EVRInitError Activate(uint32_t id) override {
        id_ = id;
        const auto props = vr::VRProperties()->TrackedDeviceToPropertyContainer(id);
        vr::VRProperties()->SetStringProperty(props, vr::Prop_TrackingSystemName_String, "phonexr");
        vr::VRProperties()->SetStringProperty(props, vr::Prop_ManufacturerName_String, "PhoneXR");
        vr::VRProperties()->SetStringProperty(props, vr::Prop_ModelNumber_String, kind_ == DeviceKind::Head ? "PhoneXR Cardboard" : "PhoneXR Hand");
        if (kind_ == DeviceKind::Head) {
            vr::VRProperties()->SetFloatProperty(props, vr::Prop_UserIpdMeters_Float, .064f);
            vr::VRProperties()->SetFloatProperty(props, vr::Prop_DisplayFrequency_Float, 60.f);
            vr::VRProperties()->SetUint64Property(props, vr::Prop_CurrentUniverseId_Uint64, 2);
        } else {
            const auto role = kind_ == DeviceKind::Left ? vr::TrackedControllerRole_LeftHand : vr::TrackedControllerRole_RightHand;
            vr::VRProperties()->SetInt32Property(props, vr::Prop_ControllerRoleHint_Int32, role);
            vr::VRProperties()->SetStringProperty(props, vr::Prop_ControllerType_String, "phonexr_controller");
            vr::VRProperties()->SetStringProperty(props, vr::Prop_InputProfilePath_String, "{phonexr}/input/phonexr_controller_profile.json");
            vr::VRDriverInput()->CreateScalarComponent(props, "/input/trigger/value", &trigger_, vr::VRScalarType_Absolute, vr::VRScalarUnits_NormalizedOneSided);
            vr::VRDriverInput()->CreateBooleanComponent(props, "/input/trigger/click", &trigger_click_);
            vr::VRDriverInput()->CreateScalarComponent(props, "/input/squeeze/value", &grip_, vr::VRScalarType_Absolute, vr::VRScalarUnits_NormalizedOneSided);
        }
        return vr::VRInitError_None;
    }
    void Deactivate() override { id_ = vr::k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void *GetComponent(const char *name) override {
        return kind_ == DeviceKind::Head && std::strcmp(name, vr::IVRDisplayComponent_Version) == 0 ? &display_ : nullptr;
    }
    void DebugRequest(const char *, char *response, uint32_t size) override { if (size) response[0] = 0; }
    vr::DriverPose_t GetPose() override {
        TrackingState current; { std::lock_guard lock(state_mutex); current = state; }
        const bool recent = current.received.time_since_epoch().count() != 0 &&
            std::chrono::steady_clock::now() - current.received < std::chrono::milliseconds(700);
        const Body *body = kind_ == DeviceKind::Head ? &current.head : kind_ == DeviceKind::Left ? &current.left : &current.right;
        vr::DriverPose_t pose{};
        pose.qWorldFromDriverRotation.w = pose.qDriverFromHeadRotation.w = 1;
        pose.deviceIsConnected = recent;
        pose.poseIsValid = recent && body->present;
        pose.result = pose.poseIsValid ? vr::TrackingResult_Running_OK : vr::TrackingResult_Uninitialized;
        if (kind_ == DeviceKind::Head) {
            pose.vecPosition[0] = body->x; pose.vecPosition[1] = body->y; pose.vecPosition[2] = body->z;
            pose.qRotation = {body->q.w, body->q.x, body->q.y, body->q.z};
        } else {
            double x, y, z; rotate(current.head.q, body->x, body->y, body->z, x, y, z);
            pose.vecPosition[0] = current.head.x + x; pose.vecPosition[1] = current.head.y + y; pose.vecPosition[2] = current.head.z + z;
            const Quaternion q = multiply(current.head.q, body->q);
            pose.qRotation = {q.w, q.x, q.y, q.z};
        }
        return pose;
    }
    void update() {
        if (id_ == vr::k_unTrackedDeviceIndexInvalid) return;
        vr::VRServerDriverHost()->TrackedDevicePoseUpdated(id_, GetPose(), sizeof(vr::DriverPose_t));
        if (kind_ != DeviceKind::Head) {
            TrackingState current; { std::lock_guard lock(state_mutex); current = state; }
            const Body &body = kind_ == DeviceKind::Left ? current.left : current.right;
            vr::VRDriverInput()->UpdateScalarComponent(trigger_, body.trigger, 0);
            vr::VRDriverInput()->UpdateBooleanComponent(trigger_click_, body.trigger > .75f, 0);
            vr::VRDriverInput()->UpdateScalarComponent(grip_, body.grip, 0);
        }
    }
private:
    DeviceKind kind_; uint32_t id_ = vr::k_unTrackedDeviceIndexInvalid; Display display_;
    vr::VRInputComponentHandle_t trigger_ = 0, trigger_click_ = 0, grip_ = 0;
};

class Provider final : public vr::IServerTrackedDeviceProvider {
public:
    vr::EVRInitError Init(vr::IVRDriverContext *context) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(context);
        receiving = true; receiver = std::thread(receive_loop);
        vr::VRServerDriverHost()->TrackedDeviceAdded("PhoneXR-HMD", vr::TrackedDeviceClass_HMD, &head_);
        vr::VRServerDriverHost()->TrackedDeviceAdded("PhoneXR-Left", vr::TrackedDeviceClass_Controller, &left_);
        vr::VRServerDriverHost()->TrackedDeviceAdded("PhoneXR-Right", vr::TrackedDeviceClass_Controller, &right_);
        return vr::VRInitError_None;
    }
    void Cleanup() override {
        receiving = false;
        if (receiver_socket != INVALID_SOCKET_HANDLE) close_socket(receiver_socket);
        if (receiver.joinable()) receiver.join();
        VR_CLEANUP_SERVER_DRIVER_CONTEXT();
    }
    const char *const *GetInterfaceVersions() override { return vr::k_InterfaceVersions; }
    void RunFrame() override { head_.update(); left_.update(); right_.update(); }
    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}
private:
    Device head_{DeviceKind::Head}, left_{DeviceKind::Left}, right_{DeviceKind::Right};
};

Provider provider;
}

VR_INTERFACE void *VR_CALLTYPE HmdDriverFactory(const char *interface_name, int *return_code) {
    if (std::strcmp(interface_name, vr::IServerTrackedDeviceProvider_Version) == 0) return &provider;
    if (return_code) *return_code = vr::VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
