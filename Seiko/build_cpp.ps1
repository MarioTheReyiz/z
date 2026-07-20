<#
.SYNOPSIS
    Builds Pewa.dll using the MSVC toolchain.
.DESCRIPTION
    Must be run from a Visual Studio Developer Command Prompt (or PowerShell)
    so that cl.exe and link.exe are in PATH, along with the standard INCLUDE/LIB
    environment variables.  If cl.exe is not found, the script attempts to
    locate the latest Visual Studio installation automatically via vswhere.exe.
#>

param(
    [string]$Dest = "C:\pewa"
)

$ErrorActionPreference = "Stop"

# ---- Paths relative to this script's directory (the project root) ----
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcDir     = Join-Path $ScriptRoot "cpp\src"
$incDir     = Join-Path $ScriptRoot "cpp\include"
$libDir     = Join-Path $ScriptRoot "cpp\lib"
$objDir     = Join-Path $ScriptRoot "build\obj\Release"
$outDir     = Join-Path $ScriptRoot "build\bin\Release"

# ---- Ensure output directories exist ----
foreach ($d in @($objDir, $outDir, $Dest)) {
    if (-not (Test-Path $d)) {
        New-Item -ItemType Directory -Path $d -Force | Out-Null
    }
}

# ---- Locate the MSVC compiler (cl.exe) ----
# First, try to use whatever is in PATH (works if launched from a VS Dev Cmd Prompt).
$cl   = (Get-Command cl.exe   -ErrorAction SilentlyContinue).Source
$link = (Get-Command link.exe -ErrorAction SilentlyContinue).Source

if (-not $cl -or -not $link) {
    Write-Host "cl.exe not in PATH. Trying to find Visual Studio automatically..."
    
    # vswhere is installed with VS 2017+.  It's the official way to locate VS.
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) {
        Write-Host "vswhere.exe not found at $vswhere" -ForegroundColor Red
        Write-Host "Please run this script from a 'Developer Command Prompt for VS'." -ForegroundColor Red
        exit 1
    }

    $vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $vsPath) {
        Write-Host "Could not find a Visual Studio installation with C++ tools." -ForegroundColor Red
        exit 1
    }
    Write-Host "Found Visual Studio at: $vsPath"

    # Find the newest MSVC tools version
    $vcToolsVersion = Get-ChildItem "$vsPath\VC\Tools\MSVC" -Directory |
                      Sort-Object Name -Descending |
                      Select-Object -First 1 -ExpandProperty Name
    if (-not $vcToolsVersion) {
        Write-Host "No MSVC tools version found inside $vsPath\VC\Tools\MSVC." -ForegroundColor Red
        exit 1
    }
    Write-Host "MSVC Tools version: $vcToolsVersion"

    $msvcBin  = "$vsPath\VC\Tools\MSVC\$vcToolsVersion\bin\Hostx64\x64"
    $cl       = "$msvcBin\cl.exe"
    $link     = "$msvcBin\link.exe"

    # Check that they really exist
    if (-not (Test-Path $cl))   { Write-Host "cl.exe not found at $cl" -ForegroundColor Red; exit 1 }
    if (-not (Test-Path $link)) { Write-Host "link.exe not found at $link" -ForegroundColor Red; exit 1 }

    # Build the INCLUDE and LIB variables if they aren't already set.
    # This way the script works even outside a Dev Cmd Prompt.
    if (-not $env:INCLUDE) {
        $sdkBase = "C:\Program Files (x86)\Windows Kits\10"
        # Try to find the newest SDK version installed
        $sdkVer = Get-ChildItem "$sdkBase\Include" -Directory |
                  Sort-Object Name -Descending |
                  Select-Object -First 1 -ExpandProperty Name
        if (-not $sdkVer) {
            Write-Host "Windows SDK not found in $sdkBase\Include" -ForegroundColor Red
            exit 1
        }
        Write-Host "Windows SDK version: $sdkVer"

        $env:INCLUDE = @(
            "$vsPath\VC\Tools\MSVC\$vcToolsVersion\include",
            "$sdkBase\Include\$sdkVer\um",
            "$sdkBase\Include\$sdkVer\shared",
            "$sdkBase\Include\$sdkVer\ucrt",
            "$sdkBase\Include\$sdkVer\cppwinrt"
        ) -join ";"
    }
    if (-not $env:LIB) {
        $env:LIB = @(
            "$vsPath\VC\Tools\MSVC\$vcToolsVersion\lib\x64",
            "$sdkBase\Lib\$sdkVer\um\x64",
            "$sdkBase\Lib\$sdkVer\ucrt\x64"
        ) -join ";"
    }
}

Write-Host "[C++] Using compiler: $cl"
Write-Host "[C++] Using linker:   $link"

# ---- Compile ----
Write-Host "[C++] Compiling..."
$compileArgs = @(
    "/c",
    "/O2", "/MD",
    "/std:c++17", "/EHsc", "/await",
    "/DNDEBUG", "/D_WINDOWS", "/D_USRDLL",
    "/I$incDir",           # project headers
    "/Fo$objDir\",
    "$srcDir\main.cpp",
    "$srcDir\jni_helper.cpp",
    "$srcDir\jar_loader.cpp",
    "$srcDir\spotify_control.cpp"
)
& $cl @compileArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAILED] Compilation failed." -ForegroundColor Red
    exit 1
}

# ---- Link ----
Write-Host "[C++] Linking..."
$linkArgs = @(
    "/DLL",
    "/OUT:$outDir\Pewa.dll",
    "/MACHINE:X64",
    "/LIBPATH:$libDir",   # project libraries (libMinHook-x64.lib etc.)
    "$objDir\main.obj",
    "$objDir\jni_helper.obj",
    "$objDir\jar_loader.obj",
    "$objDir\spotify_control.obj",
    "libMinHook-x64.lib",
    "kernel32.lib",
    "user32.lib",
    "ws2_32.lib",
    "windowsapp.lib",
    "ole32.lib",
    "oleaut32.lib"
)
& $link @linkArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAILED] Linking failed." -ForegroundColor Red
    exit 1
}

# ---- Copy to final destination ----
Copy-Item -Force "$outDir\Pewa.dll" "$Dest\Pewa.dll"
Write-Host "[Done] Pewa.dll copied to $Dest" -ForegroundColor Green