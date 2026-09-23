param(
    # Minecraft 1.8.9 client jar with MCP (mcp-named) names, e.g. the one Weave's Gradle plugin
    # writes to <Weave-Loader>\api\v1_8\.gradle\weave\client-mcp-named.jar.
    [string] $MinecraftJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'Weave-Loader\api\v1_8\.gradle\weave\client-mcp-named.jar'),
    # Gson, Guava and LWJGL 2 jars; found in the Gradle cache (or Minecraft's libraries) when not given.
    [string] $GsonJar,
    [string] $GuavaJar,
    [string] $LwjglJar,
    # Where to copy the built jar; skipped when the folder does not exist.
    [string] $ModsDir = (Join-Path $env:USERPROFILE '.weave\mods\1.8.9')
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$gradleCache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

# Gradle stores each artifact under a content-hash directory that changes whenever the cache is
# rebuilt, so resolve these by pattern instead of pinning a path that goes stale.
function Resolve-CachedJar([string] $group, [string] $artifact, [string] $pattern) {
    $base = Join-Path $gradleCache "$group\$artifact"
    if (-not (Test-Path -LiteralPath $base)) { return $null }
    Get-ChildItem -LiteralPath $base -Recurse -Filter $pattern -File |
        Where-Object { $_.Name -notlike '*-sources.jar' } |
        Sort-Object Name |
        Select-Object -First 1 -ExpandProperty FullName
}

# Only APIs that already exist in Minecraft 1.8.9's Gson 2.2.4 are used; any Gson 2.x compiles them.
if (-not $GsonJar) { $GsonJar = Resolve-CachedJar 'com.google.code.gson' 'gson' 'gson-2.*.jar' }
if (-not $LwjglJar) { $LwjglJar = Resolve-CachedJar 'org.lwjgl.lwjgl' 'lwjgl' 'lwjgl-2.9.*.jar' }
# Only needed for the return type of Minecraft.addScheduledTask; any version has it.
if (-not $GuavaJar) { $GuavaJar = Resolve-CachedJar 'com.google.guava' 'guava' 'guava-*.jar' }
if (-not $GuavaJar) {
    $GuavaJar = Join-Path $env:APPDATA '.minecraft\libraries\com\google\guava\guava\17.0\guava-17.0.jar'
}
$stubClasses = Join-Path $root 'build\stubs'
$classes = Join-Path $root 'build\classes'
$resources = Join-Path $root 'src\main\resources'
$output = Join-Path $root 'build\lunar-mod-api-1.0.0.jar'

foreach ($dependency in @($MinecraftJar, $GsonJar, $GuavaJar, $LwjglJar)) {
    if (-not $dependency -or -not (Test-Path -LiteralPath $dependency -PathType Leaf)) {
        throw "Missing build dependency: $dependency"
    }
}

foreach ($directory in @($stubClasses, $classes)) {
    Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

# Weave's ModInitializer is compiled from a stub and left out of the jar; Weave provides it at runtime.
$stubs = Get-ChildItem -LiteralPath (Join-Path $root 'src\stubs\java') -Recurse -Filter '*.java' |
    ForEach-Object { $_.FullName }
javac --release 8 -encoding UTF-8 -proc:none -Xlint:-options -d $stubClasses $stubs
if ($LASTEXITCODE -ne 0) {
    throw "javac (stubs) failed with exit code $LASTEXITCODE"
}

$sources = Get-ChildItem -LiteralPath (Join-Path $root 'src\main\java') -Recurse -Filter '*.java' |
    ForEach-Object { $_.FullName }
$classpath = "$stubClasses;$MinecraftJar;$GsonJar;$GuavaJar;$LwjglJar"

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

if ($ModsDir -and (Test-Path -LiteralPath $ModsDir -PathType Container)) {
    Copy-Item -LiteralPath $output -Destination (Join-Path $ModsDir 'lunar-mod-api-1.0.0.jar') -Force
}

Write-Host "Built $output"
