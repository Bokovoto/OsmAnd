param(
	[int] $VersionCode = 5400,
	[string] $VersionName = "0.1.0-test.79",
	[string] $OutputDirectory = "output/roadcrew-secure-release"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$signingRoot = "D:\Projects\truck-community-navigation-private\signing"
$keystorePath = Join-Path $signingRoot "roadcrew-release.p12"
$credentialPath = Join-Path $signingRoot "roadcrew-release-password.dpapi"
$googleServicesPath = Join-Path $repoRoot "OsmAnd\google-services.json"

foreach ($requiredPath in @($keystorePath, $credentialPath, $googleServicesPath)) {
	if (-not (Test-Path -LiteralPath $requiredPath)) {
		throw "Required local release file is missing: $requiredPath"
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
	ROADCREW_DISABLE_MINIFY = "true"
	APK_NUMBER_VERSION = $VersionCode.ToString()
	APK_VERSION = $VersionName
}

try {
	foreach ($entry in $releaseEnvironment.GetEnumerator()) {
		$previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
		[Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
	}

	Push-Location $repoRoot
	try {
		# Gradle and javac write ordinary notes to stderr. Windows PowerShell
		# turns any of those into a NativeCommandError under ErrorActionPreference
		# Stop, which failed a build that had in fact succeeded. The exit code is
		# the only trustworthy verdict, so ask it directly.
		$previousPreference = $ErrorActionPreference
		$ErrorActionPreference = "Continue"
		try {
			& .\gradlew.bat assembleNightlyFreeLegacyFatRelease 2>&1 | ForEach-Object {
				Write-Host $_
			}
		} finally {
			$ErrorActionPreference = $previousPreference
		}
		if ($LASTEXITCODE -ne 0) {
			throw "Gradle release build failed with exit code $LASTEXITCODE"
		}
	} finally {
		Pop-Location
	}

	$apk = Get-ChildItem (Join-Path $repoRoot "OsmAnd\build\outputs\apk\nightlyFreeLegacyFat\release") -Filter "*.apk" -File |
		Sort-Object LastWriteTime -Descending |
		Select-Object -First 1
	if (-not $apk) {
		throw "Gradle completed without producing a release APK."
	}

	$apksigner = Get-AndroidBuildTool -Name "apksigner.bat"
	$aapt = Get-AndroidBuildTool -Name "aapt.exe"
	$badging = & $aapt dump badging $apk.FullName
	if ($LASTEXITCODE -ne 0 -or ($badging -match "application-debuggable")) {
		throw "Release verification failed: APK is invalid or debuggable."
	}

	$certificateOutput = & $apksigner verify --print-certs $apk.FullName
	if ($LASTEXITCODE -ne 0) {
		throw "Release verification failed: APK signature is invalid."
	}
	$apkSha1 = (($certificateOutput | Select-String "Signer #1 certificate SHA-1 digest").Line -split ": ", 2)[1].Replace(":", "").ToUpperInvariant()
	$keyOutput = & keytool -list -v -storetype PKCS12 -keystore $keystorePath -storepass $password -alias roadcrew
	$keySha1 = (($keyOutput | Select-String "SHA1:").Line -split "SHA1:", 2)[1].Trim().Replace(":", "").ToUpperInvariant()
	if (-not $apkSha1 -or $apkSha1 -ne $keySha1) {
		throw "Release verification failed: APK was signed with an unexpected certificate."
	}

	$destination = Join-Path $repoRoot $OutputDirectory
	New-Item -ItemType Directory -Path $destination -Force | Out-Null
	$destinationApk = Join-Path $destination "RoadCrew.apk"
	Copy-Item -LiteralPath $apk.FullName -Destination $destinationApk -Force

	Write-Output "Verified RoadCrew release APK: $destinationApk"
	Write-Output "Version: $VersionName ($VersionCode)"
	Write-Output "Signing certificate SHA-1: $($apkSha1 -replace '(.{2})(?!$)', '$1:')"
} finally {
	foreach ($entry in $previousEnvironment.GetEnumerator()) {
		[Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
	}
	$password = $null
}
