<#
.SYNOPSIS
    本项目一键压测脚本：跑 REST / 弹幕 WebSocket 压测，并从 jtl 汇总 TPS / P95 / 错误率。
.DESCRIPTION
    依赖 Apache JMeter 5.6.x；弹幕场景还需要 jmeter-websocket-samplers 插件（见 jmeter/README.md）。
    每个档位（并发数）独立输出一份 jtl + 聚合 CSV + HTML 报告，最后打印 markdown 表格，可直接贴进论文。
.EXAMPLE
    pwsh ./jmeter/run.ps1 -Scenario all -Levels 200,500,1000 -Duration 120
.EXAMPLE
    pwsh ./jmeter/run.ps1 -Scenario rest -Levels 100 -Duration 60 -HostName 192.168.1.10
#>
[CmdletBinding()]
param(
    [ValidateSet('rest', 'danmaku', 'all')][string]$Scenario = 'all',
    [int[]]$Levels = @(200, 500, 1000),
    [int]$Duration = 120,
    [int]$Ramp = 10,
    [string]$HostName = '127.0.0.1',
    [int]$HttpPort = 8080,
    [int]$WsPort = 8085,
    [string]$OutDir = (Join-Path $PSScriptRoot 'results')
)

$ErrorActionPreference = 'Stop'
$scriptRoot = $PSScriptRoot

# ---------- 1. 定位 JMeter ----------
function Resolve-JMeter {
    if ($env:JMETER_HOME) {
        $candidate = Join-Path $env:JMETER_HOME 'bin/jmeter.bat'
        if (Test-Path $candidate) { return $candidate }
    }
    $cmd = Get-Command jmeter -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw '未找到 JMeter：请安装 Apache JMeter 5.6.x，或设置 JMETER_HOME 环境变量后重试。'
}

function Get-JMeterHome([string]$jmeterPath) {
    # jmeter.bat 位于 <JMETER_HOME>/bin/ 下，向上两级即 JMETER_HOME
    return (Split-Path (Split-Path $jmeterPath -Parent) -Parent)
}

function Test-WebSocketPlugin([string]$jmeterHome) {
    $ext = Join-Path $jmeterHome 'lib/ext'
    if (-not (Test-Path $ext)) { return $false }
    return [bool](Get-ChildItem $ext -Filter '*websocket*' -ErrorAction SilentlyContinue)
}

# ---------- 2. 执行一次压测 ----------
function Invoke-JMeterPlan {
    param(
        [string]$JMeterPath,
        [string]$Plan,
        [int]$Threads,
        [string]$Tag,
        [hashtable]$ExtraProps = @{}
    )

    $caseDir = Join-Path $OutDir $Tag
    if (Test-Path $caseDir) { Remove-Item $caseDir -Recurse -Force }
    New-Item -ItemType Directory -Path $caseDir -Force | Out-Null

    $jtl = Join-Path $caseDir 'result.jtl'
    $html = Join-Path $caseDir 'html'
    $jmeterArgs = @(
        '-n', '-t', $Plan,
        '-l', $jtl,
        '-e', '-o', $html,
        "-Jhost=$HostName", "-Jws_host=$HostName",
        "-Jport=$HttpPort", "-Jws_port=$WsPort",
        "-Jthreads=$Threads", "-Jlogin_threads=$([Math]::Max(2, [int]($Threads / 20)))",
        "-Jramp=$Ramp", "-Jduration=$Duration",
        "-Jreport_dir=$caseDir"
    )
    foreach ($k in $ExtraProps.Keys) { $jmeterArgs += "-J$k=$($ExtraProps[$k])" }

    Write-Host "==> 运行 $Tag ：threads=$Threads，时长 $Duration 秒" -ForegroundColor Cyan
    & $JMeterPath @jmeterArgs | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Warning "$Tag 退出码 $LASTEXITCODE（JMeter 在有失败样本时也可能返回非 0，请看指标）" }

    return $jtl
}

# ---------- 3. 从 jtl 汇总指标 ----------
function Get-JtlMetrics {
    param([string]$JtlPath, [string]$Tag)

    if (-not (Test-Path $JtlPath)) { Write-Warning "缺少 $JtlPath"; return @() }
    $rows = Import-Csv $JtlPath
    if (-not $rows) { Write-Warning "$Tag 无样本"; return @() }

    $maxTs = ($rows | Measure-Object -Property timeStamp -Maximum).Maximum
    $minTs = ($rows | Measure-Object -Property timeStamp -Minimum).Minimum
    $span = $maxTs - $minTs
    $spanSec = if ($span -gt 0) { $span / 1000.0 } else { 1 }
    $threads = [int]$Tag.Split('-')[-1]

    $rows | Group-Object label | ForEach-Object {
        $g = $_.Group
        $elapsed = @($g | ForEach-Object { [double]$_.elapsed } | Sort-Object)
        $count = $elapsed.Count
        $p95Index = [Math]::Min($count - 1, [Math]::Ceiling($count * 0.95) - 1)
        $errors = @($g | Where-Object { $_.success -ne 'true' }).Count
        [pscustomobject]@{
            '场景'      = $Tag.Split('-')[0]
            '并发'      = $threads
            '接口'      = $_.Name
            '样本数'    = $count
            'TPS'       = [Math]::Round($count / $spanSec, 2)
            '平均(ms)'  = [Math]::Round(($elapsed | Measure-Object -Average).Average, 1)
            'P95(ms)'   = [Math]::Round($elapsed[$p95Index], 1)
            '最大(ms)'  = [Math]::Round(($elapsed | Measure-Object -Maximum).Maximum, 1)
            '错误率(%)' = [Math]::Round(100.0 * $errors / $count, 2)
        }
    }
}

# ---------- 4. 主流程 ----------
$jmeter = Resolve-JMeter
$jmeterHome = Get-JMeterHome $jmeter
Write-Host "JMeter: $jmeter"
Write-Host "JMETER_HOME: $jmeterHome"
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

$plans = @()
if ($Scenario -in @('rest', 'all')) { $plans += @{ Name = 'rest'; File = (Join-Path $scriptRoot 'rest-test.jmx') } }
if ($Scenario -in @('danmaku', 'all')) {
    if (-not (Test-WebSocketPlugin $jmeterHome)) {
        Write-Warning '未检测到 jmeter-websocket-samplers 插件，跳过弹幕 WebSocket 场景（安装方式见 jmeter/README.md）'
    } else {
        $plans += @{ Name = 'danmaku'; File = (Join-Path $scriptRoot 'danmaku-test.jmx') }
    }
}
if (-not $plans) { throw '没有可执行的场景，检查插件安装或 -Scenario 参数。' }

$allMetrics = @()
foreach ($plan in $plans) {
    if (-not (Test-Path $plan.File)) { throw "脚本不存在：$($plan.File)" }
    foreach ($level in $Levels) {
        # REST 场景线程数减半：每个线程一轮打 5 个接口，避免把单机压测机打满
        $threads = if ($plan.Name -eq 'rest') { [Math]::Max(1, [int]($level / 2)) } else { $level }
        $tag = "$($plan.Name)-$threads"
        $jtl = Invoke-JMeterPlan -JMeterPath $jmeter -Plan $plan.File -Threads $threads -Tag $tag
        $allMetrics += Get-JtlMetrics -JtlPath $jtl -Tag $tag
    }
}

# ---------- 5. 输出汇总 ----------
$csv = Join-Path $OutDir 'summary.csv'
$allMetrics | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8
Write-Host ''
Write-Host "汇总表已写入 $csv" -ForegroundColor Green
$allMetrics | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

# 生成 markdown 片段，方便直接贴进 docs/实验报告-压测对比.md
$md = @('| 场景 | 并发 | 接口 | 样本数 | TPS | 平均(ms) | P95(ms) | 最大(ms) | 错误率(%) |',
        '| --- | --- | --- | --- | --- | --- | --- | --- | --- |')
foreach ($m in $allMetrics) {
    $md += "| $($m.'场景') | $($m.'并发') | $($m.'接口') | $($m.'样本数') | $($m.'TPS') | $($m.'平均(ms)') | $($m.'P95(ms)') | $($m.'最大(ms)') | $($m.'错误率(%)') |"
}
$mdPath = Join-Path $OutDir 'summary.md'
$md | Set-Content -Path $mdPath -Encoding UTF8
Write-Host "Markdown 表格已写入 $mdPath（可直接粘到 docs/实验报告-压测对比.md）" -ForegroundColor Green
