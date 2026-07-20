#include "spotify_control.h"

// =============================================================
// CLASSIC COM SECTION (NO WinRT NAMESPACE POLLUTION)
// =============================================================
#include <windows.h>
#include <mmdeviceapi.h>
#include <endpointvolume.h>
#include <functiondiscoverykeys_devpkey.h>

// Manually define IIDs/CLSIDs to avoid linking issues
const CLSID CLSID_MMDeviceEnumerator_Local = __uuidof(MMDeviceEnumerator);
const IID IID_IMMDeviceEnumerator_Local = __uuidof(IMMDeviceEnumerator);
const IID IID_IAudioEndpointVolume_Local = __uuidof(IAudioEndpointVolume);

extern "C" __declspec(dllexport) void Spotify_SetVolume(int vol) {
    if (vol < 0) vol = 0;
    if (vol > 100) vol = 100;

    HRESULT hr = CoInitialize(NULL);
    
    IMMDeviceEnumerator* deviceEnumerator = NULL;
    hr = CoCreateInstance(CLSID_MMDeviceEnumerator_Local, NULL, CLSCTX_INPROC_SERVER, IID_IMMDeviceEnumerator_Local, (LPVOID*)&deviceEnumerator);
    
    if (SUCCEEDED(hr) && deviceEnumerator) {
        IMMDevice* defaultDevice = NULL;
        hr = deviceEnumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, &defaultDevice);
        
        if (SUCCEEDED(hr) && defaultDevice) {
            IAudioEndpointVolume* endpointVolume = NULL;
            hr = defaultDevice->Activate(IID_IAudioEndpointVolume_Local, CLSCTX_INPROC_SERVER, NULL, (LPVOID*)&endpointVolume);
            
            if (SUCCEEDED(hr) && endpointVolume) {
                float currentVol = (float)vol / 100.0f;
                endpointVolume->SetMasterVolumeLevelScalar(currentVol, NULL);
                endpointVolume->Release();
            }
            defaultDevice->Release();
        }
        deviceEnumerator->Release();
    }
    CoUninitialize();
}

extern "C" __declspec(dllexport) void Spotify_VolumeDown() {
    keybd_event(VK_VOLUME_DOWN, 0, 0, 0);
    keybd_event(VK_VOLUME_DOWN, 0, KEYEVENTF_KEYUP, 0);
}

extern "C" __declspec(dllexport) void Spotify_VolumeUp() {
    keybd_event(VK_VOLUME_UP, 0, 0, 0);
    keybd_event(VK_VOLUME_UP, 0, KEYEVENTF_KEYUP, 0);
}

// =============================================================
// WinRT SECTION
// =============================================================
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Storage.Streams.h>
#include <winrt/Windows.Graphics.Imaging.h>
#include <fstream>
#include <string>

// Link windowsapp.lib for WinRT
#pragma comment(lib, "windowsapp.lib")

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Media::Control;
using namespace Windows::Storage::Streams;
using namespace Windows::Graphics::Imaging;

// Global buffer'lar (Java'dan okunacak)
static char g_trackName[256] = "No track playing";
static char g_artistName[256] = "Unknown";
static char g_albumArtPath[512] = "";
static bool g_isPlaying = false;
static bool g_threadRunning = false;
static long g_position = 0; // saniye
static long g_duration = 0; // saniye

// Wide string'i UTF-8 char*'a çevir
void WideToChar(const wchar_t* wide, char* buffer, size_t bufferSize) {
    if (!wide || bufferSize == 0) {
        if (buffer && bufferSize > 0) buffer[0] = '\0';
        return;
    }
    int result = WideCharToMultiByte(CP_UTF8, 0, wide, -1, buffer, (int)bufferSize, NULL, NULL);
    if (result == 0) {
        buffer[0] = '\0';
    }
}

// Album art'ı PNG olarak kaydet
void SaveAlbumArt(IRandomAccessStreamReference thumbnail) {
    try {
        if (!thumbnail) return;
        auto stream = thumbnail.OpenReadAsync().get();
        if (!stream) return;

        auto decoder = BitmapDecoder::CreateAsync(stream).get();
        auto softwareBitmap = decoder.GetSoftwareBitmapAsync().get();

        InMemoryRandomAccessStream outStream;
        auto encoder = BitmapEncoder::CreateAsync(BitmapEncoder::PngEncoderId(), outStream).get();
        encoder.SetSoftwareBitmap(softwareBitmap);
        encoder.FlushAsync().get();

        auto size = outStream.Size();
        if (size == 0) return;

        Buffer buffer((uint32_t)size);
        outStream.ReadAsync(buffer, (uint32_t)size, InputStreamOptions::None).get();

        CreateDirectoryA("C:\\pewa", NULL);
        std::string path = "C:\\pewa\\spotify_art.png";
        
        // Basit kontrol: boyut aynıysa yazma
        std::ifstream currentFile(path, std::ios::binary | std::ios::ate);
        if (currentFile.good()) {
            if (std::abs((long long)currentFile.tellg() - (long long)size) < 100) {
                 // Skip writing
            }
        }
        currentFile.close();

        std::string tempPath = "C:\\pewa\\spotify_art.tmp";
        std::string finalPath = "C:\\pewa\\spotify_art.png";

        std::ofstream file(tempPath, std::ios::binary);
        if (file.is_open()) {
            file.write((char*)buffer.data(), buffer.Length());
            file.close();
            remove(finalPath.c_str());
            rename(tempPath.c_str(), finalPath.c_str());
            strcpy_s(g_albumArtPath, finalPath.c_str());
        }
    } catch (...) {}
}

// Media bilgilerini güncelle
void UpdateMediaInfo() {
    try {
        auto sessionManager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
        if (!sessionManager) return;
        
        auto session = sessionManager.GetCurrentSession();
        if (!session) {
            strcpy_s(g_trackName, "No track playing");
            strcpy_s(g_artistName, "Unknown");
            g_isPlaying = false;
            g_position = 0;
            g_duration = 0;
            return;
        }
        
        auto mediaProps = session.TryGetMediaPropertiesAsync().get();
        if (mediaProps) {
            WideToChar(mediaProps.Title().c_str(), g_trackName, 256);
            WideToChar(mediaProps.Artist().c_str(), g_artistName, 256);
            auto thumbnail = mediaProps.Thumbnail();
            if (thumbnail) SaveAlbumArt(thumbnail);
        }
        
        auto playbackInfo = session.GetPlaybackInfo();
        if (playbackInfo) {
            auto status = playbackInfo.PlaybackStatus();
            g_isPlaying = (status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing);
        }

        // Timeline — 100ns tick → ms (Java tarafı ms bekliyor)
        auto timeline = session.GetTimelineProperties();
        if (timeline) {
            g_position = (long)(timeline.Position().count() / 10000);   // ms
            g_duration = (long)(timeline.EndTime().count()  / 10000);   // ms
        }
        
    } catch (...) {}
}

// Background thread
DWORD WINAPI SpotifyUpdateThread(LPVOID param) {
    init_apartment();
    while (g_threadRunning) {
        UpdateMediaInfo();
        Sleep(1000);
    }
    uninit_apartment();
    return 0;
}

// Export fonksiyonlar (WinRT kullananlar)
extern "C" {
    __declspec(dllexport) const char* Spotify_GetTrack() { return g_trackName; }
    __declspec(dllexport) const char* Spotify_GetArtist() { return g_artistName; }
    __declspec(dllexport) const char* Spotify_GetAlbumArt() { return g_albumArtPath; }
    __declspec(dllexport) bool Spotify_IsPlaying() { return g_isPlaying; }
    __declspec(dllexport) long Spotify_GetPosition() { return g_position; }
    __declspec(dllexport) long Spotify_GetDuration() { return g_duration; }
    
    __declspec(dllexport) void Spotify_Seek(int seconds) {
        try {
            init_apartment();
            auto sessionManager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
            auto session = sessionManager.GetCurrentSession();
            if (session) {
                // Convert seconds to TimeSpan (ticks)
                // 1 second = 10,000,000 ticks
                Windows::Foundation::TimeSpan ts(seconds * 10000000LL);
                session.TryChangePlaybackPositionAsync(ts.count()).get(); 
                // Not: TryChangePlaybackPositionAsync, int64 tick alır veya TimeSpan alır.
                // WinRT C++ projeksiyonunda TimeSpan.count() değil direkt TimeSpan objesi veya tick count gerekebilir.
                // Windows.Foundation.TimeSpan bir struct'tır.
            }
            uninit_apartment();
        } catch (...) {}
    }

    __declspec(dllexport) void Spotify_PlayPause() {
        try {
            init_apartment();
            auto sessionManager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
            auto session = sessionManager.GetCurrentSession();
            if (session) session.TryTogglePlayPauseAsync().get();
            uninit_apartment();
        } catch (...) {}
    }
    
    __declspec(dllexport) void Spotify_Next() {
        try {
            init_apartment();
            auto sessionManager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
            auto session = sessionManager.GetCurrentSession();
            if (session) session.TrySkipNextAsync().get();
            uninit_apartment();
        } catch (...) {}
    }
    
    __declspec(dllexport) void Spotify_Previous() {
        try {
            init_apartment();
            auto sessionManager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
            auto session = sessionManager.GetCurrentSession();
            if (session) session.TrySkipPreviousAsync().get();
            uninit_apartment();
        } catch (...) {}
    }
    
    __declspec(dllexport) void Spotify_Init() {
        if (!g_threadRunning) {
            g_threadRunning = true;
            CreateThread(nullptr, 0, SpotifyUpdateThread, nullptr, 0, nullptr);
        }
    }
}
