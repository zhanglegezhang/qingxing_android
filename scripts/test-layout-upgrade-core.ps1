param([switch]$LatestOnly,[ValidatePattern('^emulator-[0-9]+$')][string]$TestSerial='emulator-5556')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if(!$env:QINGXING_WORKFLOW_LOCKED -or !$env:QINGXING_CANDIDATE){throw 'Use the public test script'}
$taskCandidate=Get-Content -LiteralPath $env:QINGXING_CANDIDATE -Raw | ConvertFrom-Json
$taskCandidateApk=Join-Path (Split-Path -Parent $env:QINGXING_CANDIDATE) $taskCandidate.file
$taskExpectedCode=[string]$taskCandidate.versionCode
$adb=Join-Path $PWD '.tooling\adb\platform-tools\adb.exe'
$avd=& $adb -s $TestSerial emu avd name
if(($avd | Select-Object -First 1) -ne 'QingxingSmoke16'){throw 'Upgrade checks require disposable QingxingSmoke16'}
& .\scripts\test-android-core.ps1 -BuildOnly -TestSerial $TestSerial
function Checked([string[]]$arguments){& $adb @arguments;if($LASTEXITCODE -ne 0){throw 'Emulator command failed'}}
$taskVersion=Get-Content release/version.json -Raw | ConvertFrom-Json
$taskReport=if($LatestOnly){'docs\latest-upgrade-test-output.txt'}else{'docs\layout-upgrade-test-output.txt'}
if($LatestOnly -and $taskCandidate.testPlan.reinstallRequired){
    $taskBaseline=$taskCandidate.testPlan.baseline
    if(!$taskBaseline -or (Get-FileHash -LiteralPath $taskBaseline.apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskBaseline.sha256){throw 'Preceding formal APK hash changed'}
    $installed=& $adb -s $TestSerial shell pm list packages cn.qingxing.reminder
    if(($installed -join "`n") -match 'package:cn.qingxing.reminder'){Checked @('-s',$TestSerial,'uninstall','cn.qingxing.reminder')}
    Checked @('-s',$TestSerial,'install',$taskBaseline.apk)
    $taskRejected=& $adb -s $TestSerial install -r $taskCandidateApk 2>&1
    if($LASTEXITCODE -eq 0 -or ($taskRejected -join "`n") -notmatch 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'){throw 'Expected Android to reject the old-signature update'}
    'PASS Android rejected the old-signature update as expected' | Set-Content -Encoding utf8 $taskReport
    Checked @('-s',$TestSerial,'uninstall','cn.qingxing.reminder')
    Checked @('-s',$TestSerial,'install',$taskCandidateApk)
    Checked @('-s',$TestSerial,'install','-r','build\integration\signed.apk')
    foreach($taskPermission in @('POST_NOTIFICATIONS','ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION','ACCESS_BACKGROUND_LOCATION')){
        Checked @('-s',$TestSerial,'shell','pm','grant','cn.qingxing.reminder',"android.permission.$taskPermission")
    }
    Checked @('-s',$TestSerial,'shell','appops','set','cn.qingxing.reminder','SCHEDULE_EXACT_ALARM','allow')
    $result=& $adb -s $TestSerial shell am instrument -w -r -e expectedCode $taskExpectedCode -e verifyBackup true cn.qingxing.reminder.tests/cn.qingxing.reminder.MinimalSmokeTests
    $result | Tee-Object -FilePath $taskReport -Append
    if(($result -join "`n") -notmatch 'PASS \d+ final smoke checks:.*reinstall/backup import' -or ($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Clean reinstall or settings import failed'}
    return
}
$taskSeeds=if($LatestOnly){@($taskCandidate.testPlan.baseline)}else{@($taskVersion.upgradeFrom)}
$taskSeeds=@($taskSeeds | Where-Object {$_})
if(!$LatestOnly -and $taskVersion.signing){
    $taskSeeds=@($taskSeeds | Where-Object {$_.versionCode -ge $taskVersion.signing.firstVersionCode})
    if($taskSeeds.Count -eq 0){throw 'Historical in-place upgrade requires a seed APK with the current signing certificate'}
}
if($taskSeeds.Count -eq 0){'PASS 0 preceding upgrade checks: no previous release baseline' | Set-Content -Encoding utf8 $taskReport;return}
$taskFirst=$true
foreach($taskSeedVersion in $taskSeeds){
    $taskSeedCode=[string]$taskSeedVersion.versionCode
    $taskSeedApk=if($LatestOnly){[string]$taskSeedVersion.apk}else{'tests\fixtures\Qingxing-'+$taskSeedVersion.versionName+'.apk'}
    if($LatestOnly -and (Get-FileHash -LiteralPath $taskSeedApk -Algorithm SHA256).Hash.ToLower() -ne $taskSeedVersion.sha256){throw 'Preceding formal APK hash changed'}
    $installed=& $adb -s $TestSerial shell pm list packages cn.qingxing.reminder
    if(($installed -join "`n") -match 'package:cn.qingxing.reminder'){Checked @('-s',$TestSerial,'uninstall','cn.qingxing.reminder')}
    Checked @('-s',$TestSerial,'install',$taskSeedApk)
    Checked @('-s',$TestSerial,'install','-r','build\integration\signed.apk')
    Checked @('-s',$TestSerial,'shell','pm','grant','cn.qingxing.reminder','android.permission.POST_NOTIFICATIONS')
    Checked @('-s',$TestSerial,'shell','appops','set','cn.qingxing.reminder','SCHEDULE_EXACT_ALARM','allow')
    $seed=& $adb -s $TestSerial shell am instrument -w -e mode seed -e seedCode $taskSeedCode cn.qingxing.reminder.tests/cn.qingxing.reminder.LayoutUpgradeProbe
    if($taskFirst){$seed | Out-File -Encoding utf8 $taskReport;$taskFirst=$false}else{$seed | Out-File -Encoding utf8 $taskReport -Append}
    $seed | Write-Output
    if(($seed -join "`n") -notmatch ('PASS layout upgrade seed on versionCode '+$taskSeedCode)){throw 'Layout seed failed'}
    Checked @('-s',$TestSerial,'install','-r',$taskCandidateApk)
    $result=& $adb -s $TestSerial shell am instrument -w -e mode check -e seedCode $taskSeedCode -e expectedCode $taskExpectedCode cn.qingxing.reminder.tests/cn.qingxing.reminder.LayoutUpgradeProbe
    $result | Tee-Object -FilePath $taskReport -Append
    if(($result -join "`n") -notmatch ('PASS versionCode '+$taskSeedCode+' -> '+$taskExpectedCode) -or ($result -join "`n") -match 'FAIL'){throw 'Layout upgrade failed'}
}
