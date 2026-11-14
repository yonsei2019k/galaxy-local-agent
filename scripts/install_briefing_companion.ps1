param([Parameter(Mandatory=$true)][string]$PrivateConnectionFile,
      [Parameter(Mandatory=$true)][string]$PythonExe,
      [Parameter(Mandatory=$true)][string]$CodexExe,
      [switch]$Start)
$ErrorActionPreference='Stop'
$destination=Join-Path $env:LOCALAPPDATA 'ZipHap\Briefing'
New-Item -ItemType Directory -Force $destination|Out-Null
$connection=Get-Content -LiteralPath $PrivateConnectionFile -Raw|ConvertFrom-Json
$identity=((& $connection.adb -s $connection.target shell getprop ro.serialno 2>$null)-join '').Trim()
if($LASTEXITCODE -ne 0 -or !$identity -or $identity -eq 'unknown'){throw 'Connected phone identity must be verified first'}
$identityHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($identity))).ToLowerInvariant()
$configuration=@{adb=$connection.adb;target=$connection.target;deviceIdentitySha256=$identityHash;codex=$CodexExe;minimumSeconds=3600;maxCallsPerDay=4}
$configFile=Join-Path $destination 'config.json'
$configuration|ConvertTo-Json|Set-Content -LiteralPath $configFile -Encoding utf8
$runtime=Join-Path $destination 'briefing_companion.py'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'briefing_companion.py') -Destination $runtime
$pythonWindowless=Join-Path (Split-Path -Parent $PythonExe) 'pythonw.exe'
if(!(Test-Path -LiteralPath $pythonWindowless)){throw 'A windowless Python runtime is required'}
$startup=[Environment]::GetFolderPath('Startup')
$shell=New-Object -ComObject WScript.Shell
$shortcut=$shell.CreateShortcut((Join-Path $startup 'ZipHap Briefing.lnk'))
$shortcut.TargetPath=$pythonWindowless
$shortcut.Arguments=('"{0}" --config "{1}"' -f $runtime,$configFile)
$shortcut.WorkingDirectory=$destination
$shortcut.WindowStyle=7
$shortcut.Description='ZipHap new-data detector; no LLM call when no new data'
$shortcut.Save()
if($Start){Start-Process -FilePath $pythonWindowless -ArgumentList $shortcut.Arguments -WorkingDirectory $destination -WindowStyle Hidden}
Write-Output 'LOCAL_COMPANION_INSTALLED'
