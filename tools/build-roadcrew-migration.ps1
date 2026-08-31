param(
	[string] $RoadCrewApk = "output/roadcrew-secure-release/RoadCrew.apk",
	[string] $OutputDirectory = "output/roadcrew-migration",
	[switch] $PatchOnly
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$signingRoot = "D:\Projects\truck-community-navigation-private\signing"
$keystorePath = Join-Path $signingRoot "roadcrew-release.p12"
$credentialPath = Join-Path $signingRoot "roadcrew-release-password.dpapi"
$sourceApk = Join-Path $repoRoot $RoadCrewApk
$migrationRoot = Join-Path $repoRoot "tools\roadcrew-migration"
$embeddedApk = Join-Path $migrationRoot "app\src\main\assets\RoadCrew.apk"

$requiredPaths = @($keystorePath, $credentialPath)
if (-not $PatchOnly) {
	$requiredPaths += $sourceApk
}
foreach ($requiredPath in $requiredPaths) {
	if (-not (Test-Path -LiteralPath $requiredPath)) {
		throw "Required migration build file is missing: $requiredPath"
	}
}

function ConvertFrom-DpapiSecureString {
	param([Parameter(Mandatory)] [string] $Path)

	$secureValue = Get-Content -LiteralPath $Path -Raw | ConvertTo-SecureString
	$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
	try {
		return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
	} finally {
		[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
		$secureValue.Dispose()
	}
}

function Get-AndroidBuildTool {
	param([Parameter(Mandatory)] [string] $Name)

	$tool = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Recurse -Filter $Name -ErrorAction SilentlyContinue |
		Sort-Object FullName -Descending |
		Select-Object -First 1 -ExpandProperty FullName
	if (-not $tool) {
		throw "Android build tool not found: $Name"
	}
	return $tool
}

$password = ConvertFrom-DpapiSecureString -Path $credentialPath
$previousEnvironment = @{}
$releaseEnvironment = @{
	ROADCREW_KEYSTORE_PATH = $keystorePath
	ROADCREW_KEYSTORE_PASSWORD = $password
	ROADCREW_KEY_ALIAS = "roadcrew"
	ROADCREW_KEY_PASSWORD = $password
}

try {
	$apksigner = Get-AndroidBuildTool -Name "apksigner.bat"
	$aapt = Get-AndroidBuildTool -Name "aapt.exe"
	Remove-Item -LiteralPath $embeddedApk -Force -ErrorAction SilentlyContinue
	if (-not $PatchOnly) {
		$releaseCertificate = (& $apksigner verify --print-certs $sourceApk |
				Select-String "Signer #1 certificate SHA-256 digest").Line
		if ($LASTEXITCODE -ne 0 -or $releaseCertificate -notmatch "18ace3d71ce155e20c2add395f42b0088d44aabf381db8fd6bd0f047ab331481") {
			throw "The embedded RoadCrew APK does not use the permanent release certificate."
		}

		New-Item -ItemType Directory -Path (Split-Path -Parent $embeddedApk) -Force | Out-Null
		Copy-Item -LiteralPath $sourceApk -Destination $embeddedApk -Force
	}

	foreach ($entry in $releaseEnvironment.GetEnumerator()) {
		$previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
		[Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
	}

	Push-Location $repoRoot
	try {
		& .\gradlew.bat -p tools\roadcrew-migration :app:clean :app:assembleRelease
		if ($LASTEXITCODE -ne 0) {
			throw "Migration Gradle build failed with exit code $LASTEXITCODE"
		}
	} finally {
		Pop-Location
	}

	$migrationApk = Join-Path $migrationRoot "app\build\outputs\apk\release\app-release.apk"
	if (-not (Test-Path -LiteralPath $migrationApk)) {
		throw "Migration build completed without producing an APK."
	}
	$badging = (& $aapt dump badging $migrationApk) -join "`n"
	if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='org.roadcrew.migration'" -or
			$badging -match "application-debuggable") {
		throw "Migration verification failed: unexpected package or debuggable APK."
	}
	$migrationCertificate = (& $apksigner verify --print-certs $migrationApk |
			Select-String "Signer #1 certificate SHA-256 digest").Line
	if ($LASTEXITCODE -ne 0 -or $migrationCertificate -notmatch "18ace3d71ce155e20c2add395f42b0088d44aabf381db8fd6bd0f047ab331481") {
		throw "Migration verification failed: unexpected signing certificate."
	}

	$destination = Join-Path $repoRoot $OutputDirectory
	New-Item -ItemType Directory -Path $destination -Force | Out-Null
	$destinationName = if ($PatchOnly) { "RoadCrew-Migration-Fix.apk" } else { "RoadCrew.apk" }
	$destinationApk = Join-Path $destination $destinationName
	Copy-Item -LiteralPath $migrationApk -Destination $destinationApk -Force
	Write-Output "Verified RoadCrew migration APK: $destinationApk"
} finally {
	Remove-Item -LiteralPath $embeddedApk -Force -ErrorAction SilentlyContinue
	foreach ($entry in $previousEnvironment.GetEnumerator()) {
		[Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
	}
	$password = $null
}
