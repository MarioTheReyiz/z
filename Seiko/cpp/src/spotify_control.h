#pragma once
#include <windows.h>

// Export fonksiyonlar
extern "C" {
    __declspec(dllexport) const char* Spotify_GetTrack();
    __declspec(dllexport) const char* Spotify_GetArtist();
    __declspec(dllexport) const char* Spotify_GetAlbumArt();
    __declspec(dllexport) bool Spotify_IsPlaying();
    __declspec(dllexport) void Spotify_PlayPause();
    __declspec(dllexport) void Spotify_Next();
    __declspec(dllexport) void Spotify_Previous();
    __declspec(dllexport) void Spotify_VolumeUp();
    __declspec(dllexport) void Spotify_VolumeDown();
    __declspec(dllexport) void Spotify_SetVolume(int vol);
    __declspec(dllexport) void Spotify_Seek(int seconds);
    __declspec(dllexport) long Spotify_GetPosition();
    __declspec(dllexport) long Spotify_GetDuration();
    __declspec(dllexport) void Spotify_Init();
}
