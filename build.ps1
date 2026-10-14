param(
    [ValidateSet('arm64','amd64')][string]$Architecture='arm64',
    [switch]$Native,
    [switch]$TestOnly
)
$ErrorActionPreference='Stop'
if (!$env:ANDROID_HOME -or !$env:JAVA_HOME) { throw 'Set ANDROID_HOME and JAVA_HOME (JDK 17).' }
$env:GOTOOLCHAIN='go1.26.8'
if (!$env:GOMODCACHE) { $env:GOMODCACHE=Join-Path ([Environment]::GetFolderPath('UserProfile')) 'go/pkg/mod' }
$env:MYVPN_ABI=if ($Architecture -eq 'arm64') { 'arm64-v8a' } else { 'x86_64' }
$env:GOPATH=Join-Path $PSScriptRoot '.build/go'
$env:GOBIN=Join-Path $env:GOPATH 'bin'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:GOBIN+';'+$env:PATH
$env:ANDROID_NDK_HOME=Join-Path $env:ANDROID_HOME 'ndk/28.0.13004108'
$go=(Get-Command go -ErrorAction Stop).Source
if ($Native) {
    foreach ($tool in @('gomobile','gobind')) {
        & $go @('install',('github.com/sagernet/gomobile/cmd/'+$tool+'@v0.1.13'))
        if ($LASTEXITCODE -ne 0) { throw 'Local mobile-tool installation failed' }
    }
    & $go @('run','-C',(Join-Path $PSScriptRoot 'core-build-transport15'),'./cmd/internal/build_libbox','-target','android','-platform',('android/'+$Architecture))
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
    $libs=Join-Path $PSScriptRoot 'client-source/app/libs'
    [void][IO.Directory]::CreateDirectory($libs)
    foreach ($name in @('libbox.aar','libbox-legacy.aar')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot "core-build-transport15/$name") -Destination (Join-Path $libs $name) -Force
    }
}
if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'client-source/app/libs/libbox.aar'))) { throw 'Build native libraries first: ./build.ps1 -Native' }
$wrapper=Join-Path $PSScriptRoot 'client-source/gradlew.bat'
$args=@('-p',(Join-Path $PSScriptRoot 'client-source'),'--no-daemon','--console=plain',':app:testOtherDebugUnitTest')
if (!$TestOnly) { $args+= ':app:assembleOtherDebug' }
& $wrapper @args
if ($LASTEXITCODE -ne 0) { throw 'Android checks failed' }
