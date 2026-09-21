#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <string>

#ifdef _WIN32
#include <windows.h>
#include <shellapi.h>
#endif

namespace fs = std::filesystem;

static fs::path executable_dir(const char *argv0) {
#ifdef _WIN32
    char path[MAX_PATH]{};
    GetModuleFileNameA(nullptr, path, MAX_PATH);
    return fs::path(path).parent_path();
#else
    std::error_code error;
    const auto path = fs::read_symlink("/proc/self/exe", error);
    return error ? fs::absolute(argv0).parent_path() : path.parent_path();
#endif
}

int main(int argc, char **argv) {
    const fs::path root = executable_dir(argc ? argv[0] : "PhoneXR-SteamVR");
    const fs::path driver = root / "steamvr" / "phonexr";
    if (!fs::exists(driver / "driver.vrdrivermanifest")) {
        std::cerr << "PhoneXR SteamVR driver is missing next to the launcher.\n";
        return 2;
    }
#ifdef _WIN32
    const char *program_files = std::getenv("ProgramFiles(x86)");
    fs::path vrpathreg = fs::path(program_files ? program_files : "C:\\Program Files (x86)") /
        "Steam/steamapps/common/SteamVR/bin/win64/vrpathreg.exe";
    if (!fs::exists(vrpathreg)) {
        MessageBoxA(nullptr, "Install SteamVR in Steam first.", "PhoneXR Share", MB_OK | MB_ICONERROR);
        return 3;
    }
    const std::string command = "\"" + vrpathreg.string() + "\" adddriver \"" + driver.string() + "\"";
    if (std::system(command.c_str()) != 0) {
        MessageBoxA(nullptr, "SteamVR could not register the PhoneXR driver.", "PhoneXR Share", MB_OK | MB_ICONERROR);
        return 4;
    }
    ShellExecuteA(nullptr, "open", "steam://rungameid/250820", nullptr, nullptr, SW_SHOWNORMAL);
    MessageBoxA(nullptr, "PhoneXR driver registered. SteamVR is starting.", "PhoneXR Share", MB_OK | MB_ICONINFORMATION);
#else
    const std::string find = "command -v vrpathreg >/dev/null 2>&1";
    if (std::system(find.c_str()) != 0) {
        std::cerr << "Install SteamVR first; vrpathreg was not found in PATH.\n";
        return 3;
    }
    const std::string register_driver = "vrpathreg adddriver \"" + driver.string() + "\"";
    if (std::system(register_driver.c_str()) != 0) {
        std::cerr << "SteamVR could not register the PhoneXR driver.\n";
        return 4;
    }
    std::system("steam steam://rungameid/250820 >/dev/null 2>&1 &");
    std::cout << "PhoneXR driver registered. SteamVR is starting.\n";
#endif
    return 0;
}
