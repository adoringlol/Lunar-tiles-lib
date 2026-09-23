$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$workspace = Split-Path $root -Parent
$weave = Join-Path $workspace 'Weave-Loader'
$loader = Join-Path $weave 'loader\build\libs\weave-lunar-loader-1.3.3-all.jar'
$versionApi = Join-Path $weave 'api\v1_8\build\libs\weave-v1_8-1.3.3.jar'
$gameJar = Join-Path $weave 'api\v1_8\.gradle\weave\client-mcp-named.jar'
$gradleCache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

# Gradle stores each artifact under a content-hash directory that changes whenever the cache is
# rebuilt, so resolve these by pattern instead of pinning a path that goes stale.
function Resolve-CachedJar([string] $group, [string] $artifact, [string] $pattern) {
    $base = Join-Path $gradleCache "$group\$artifact"
    if (-not (Test-Path -LiteralPath $base)) { return $null }
    Get-ChildItem -LiteralPath $base -Recurse -Filter $pattern -File |
        Sort-Object Name |
        Select-Object -First 1 -ExpandProperty FullName
}

# Only APIs that already exist in Minecraft 1.8.9's Gson 2.2.4 are used; any cached Gson compiles them.
$gsonJar = Resolve-CachedJar 'com.google.code.gson' 'gson' 'gson-2.*.jar'
$lwjglJar = Resolve-CachedJar 'org.lwjgl.lwjgl' 'lwjgl' 'lwjgl-2.9.*.jar'
$classes = Join-Path $root 'build\classes'
$resources = Join-Path $root 'src\main\resources'
$output = Join-Path $root 'build\lunar-mod-api-1.0.0.jar'
$installerMods = Join-Path $workspace 'Weave-Mod-Installer\payload\mods'
$liveMods = Join-Path $env:USERPROFILE '.weave\mods\1.8.9'

foreach ($dependency in @($loader, $versionApi, $gameJar, $gsonJar, $lwjglJar)) {
    if (-not $dependency -or -not (Test-Path -LiteralPath $dependency -PathType Leaf)) {
        throw "Missing build dependency: $dependency"
    }
}

Remove-Item -LiteralPath $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classes -Force | Out-Null

$sources = Get-ChildItem -LiteralPath (Join-Path $root 'src\main\java') -Recurse -Filter '*.java' |
    ForEach-Object { $_.FullName }
$classpath = "$loader;$versionApi;$gameJar;$gsonJar;$lwjglJar"

javac --release 8 -encoding UTF-8 -proc:none -Xlint:-options -cp $classpath -d $classes $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

Copy-Item -Path (Join-Path $resources '*') -Destination $classes -Recurse -Force
Remove-Item -LiteralPath (Join-Path $classes 'META-INF\MANIFEST.MF') -Force -ErrorAction SilentlyContinue
jar cfm $output (Join-Path $resources 'META-INF\MANIFEST.MF') -C $classes .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}

foreach ($destination in @($installerMods, $liveMods)) {
    if (Test-Path -LiteralPath $destination -PathType Container) {
        Copy-Item -LiteralPath $output -Destination (Join-Path $destination 'lunar-mod-api-1.0.0.jar') -Force
    }
}

Write-Host "Built $output"
