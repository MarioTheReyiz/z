$msvcVer  = "14.50.35717"
$vsBase   = "C:\Program Files\Microsoft Visual Studio\18\Community"
$sdkBase  = "C:\Program Files (x86)\Windows Kits\10"
$sdkVer   = "10.0.26100.0"
$srcDir   = "C:\Users\Wachen\Desktop\Seiko\cpp\src"
$incDir   = "C:\Users\Wachen\Desktop\Seiko\cpp\include"
$libDir   = "C:\Users\Wachen\Desktop\Seiko\cpp\lib"
$objDir   = "C:\Users\Wachen\Desktop\Seiko\cpp\obj\Release"
$outDir   = "C:\Users\Wachen\Desktop\Seiko\cpp\bin\Release"
$dest     = "C:\pewa"

$cl       = "$vsBase\VC\Tools\MSVC\$msvcVer\bin\Hostx64\x64\cl.exe"
$link     = "$vsBase\VC\Tools\MSVC\$msvcVer\bin\Hostx64\x64\link.exe"

$msvcInc      = "$vsBase\VC\Tools\MSVC\$msvcVer\include"
$msvcLib      = "$vsBase\VC\Tools\MSVC\$msvcVer\lib\x64"
$sdkIncUm     = "$sdkBase\Include\$sdkVer\um"
$sdkIncShared = "$sdkBase\Include\$sdkVer\shared"
$sdkIncUcrt   = "$sdkBase\Include\$sdkVer\ucrt"
$sdkIncWinrt  = "$sdkBase\Include\$sdkVer\cppwinrt"
$sdkLibUm     = "$sdkBase\Lib\$sdkVer\um\x64"
$sdkLibUcrt   = "$sdkBase\Lib\$sdkVer\ucrt\x64"

foreach ($d in @($objDir, $outDir, $dest)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}

Write-Host "[C++] Compiling..."
$compileArgs = @(
    "/c", "/O2", "/MD", "/std:c++17", "/EHsc", "/await", "/DNDEBUG", "/D_WINDOWS", "/D_USRDLL",
    "/I$incDir", "/I$msvcInc", "/I$sdkIncUm", "/I$sdkIncShared", "/I$sdkIncUcrt", "/I$sdkIncWinrt",
    "/Fo$objDir\",
    "$srcDir\main.cpp", "$srcDir\jni_helper.cpp", "$srcDir\jar_loader.cpp", "$srcDir\spotify_control.cpp"
)
& $cl @compileArgs
if ($LASTEXITCODE -ne 0) { Write-Host "[FAILED] Compile"; exit 1 }

Write-Host "[C++] Linking..."
$linkArgs = @(
    "/DLL", "/OUT:$outDir\Pewa.dll", "/MACHINE:X64",
    "/LIBPATH:$msvcLib", "/LIBPATH:$sdkLibUm", "/LIBPATH:$sdkLibUcrt", "/LIBPATH:$libDir",
    "$objDir\main.obj", "$objDir\jni_helper.obj", "$objDir\jar_loader.obj", "$objDir\spotify_control.obj",
    "libMinHook-x64.lib", "kernel32.lib", "user32.lib", "ws2_32.lib", "windowsapp.lib", "ole32.lib", "oleaut32.lib"
)
& $link @linkArgs
if ($LASTEXITCODE -ne 0) { Write-Host "[FAILED] Link"; exit 1 }

Copy-Item -Force "$outDir\Pewa.dll" "$dest\Pewa.dll"
Write-Host "[Done] Pewa.dll -> $dest"
