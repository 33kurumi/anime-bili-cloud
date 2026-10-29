# =============================================================================
# abc-danmaku-service 离线单元测试脚本
#
# 为什么不用 mvn test：
#   交付机房离线，Maven 本地仓库没有缓存 junit-jupiter 与 surefire 2.22.x，
#   mvn test 会在 test-compile 阶段失败。测试源码因此放在 src/unit-test/java，
#   默认构建完全不编译；本脚本用 javac + junit-platform-launcher 直接驱动 Jupiter。
#
# 用法（在 abc-danmaku-service 目录下）：
#   pwsh -File run-unit-tests.ps1
# 可选参数：
#   -MavenRepo 指定本地仓库根目录（默认依次探测 D:\Maven\maven3.6.1\repository、
#              D:\maven3.6.1\repository、$env:USERPROFILE\.m2\repository）
# =============================================================================
param(
    [string]$MavenRepo = ""
)

$ErrorActionPreference = "Stop"
$proj   = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcDir = Join-Path $proj "src\unit-test\java"
$outDir = Join-Path $proj "target\unit-test-classes"
$mainClasses = Join-Path $proj "target\classes"

# ---- 1. 主代码必须先编译（默认构建不编译测试，因此只保证 target/classes 存在） ----
if (-not (Test-Path $mainClasses)) {
    Write-Host "[1/5] target/classes 不存在，先编译主代码 ..." -ForegroundColor Yellow
    & mvn -B -o -DskipTests -pl abc-danmaku-service -am compile
    if ($LASTEXITCODE -ne 0) { throw "主代码编译失败（exit=$LASTEXITCODE）" }
} else {
    Write-Host "[1/5] 主代码已编译：$mainClasses" -ForegroundColor Green
}

# ---- 2. 定位 JUnit 运行时 jar（按顺序探测候选本地仓库） ----
$candidates = @()
if ($MavenRepo) { $candidates += $MavenRepo }
$candidates += @(
    "D:\Maven\maven3.6.1\repository",
    "D:\maven3.6.1\repository",
    (Join-Path $env:USERPROFILE ".m2\repository")
)

function Find-Jar([string[]]$roots, [string]$relPath) {
    $leaf = Split-Path $relPath -Leaf
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $hit = Get-ChildItem -Path $root -Filter $leaf -Recurse -File -ErrorAction SilentlyContinue |
               Where-Object { $_.FullName.Replace('/', '\').EndsWith($relPath, [System.StringComparison]::OrdinalIgnoreCase) } |
               Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

Write-Host "[2/5] 定位 JUnit jar ..." -ForegroundColor Yellow
$jarPatterns = @(
    "org\junit\jupiter\junit-jupiter-api\5.8.2\junit-jupiter-api-5.8.2.jar",
    "org\junit\jupiter\junit-jupiter-engine\5.8.2\junit-jupiter-engine-5.8.2.jar",
    "org\junit\jupiter\junit-jupiter-params\5.8.2\junit-jupiter-params-5.8.2.jar",
    "org\junit\platform\junit-platform-commons\1.8.2\junit-platform-commons-1.8.2.jar",
    "org\junit\platform\junit-platform-engine\1.8.2\junit-platform-engine-1.8.2.jar",
    "org\junit\platform\junit-platform-launcher\1.8.2\junit-platform-launcher-1.8.2.jar",
    "org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar",
    "org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar",
    "org\slf4j\slf4j-api\2.0.13\slf4j-api-2.0.13.jar"
)
$jars = @()
foreach ($rel in $jarPatterns) {
    $jar = Find-Jar $candidates $rel
    if (-not $jar) { throw "找不到 jar：$rel （可用 -MavenRepo 指定本地仓库）" }
    $jars += $jar
    Write-Host ("      OK  " + (Split-Path $jar -Leaf))
}

# ---- 3. 业务依赖 classpath（弹幕服务的工具类可能引用 Spring 类型，编译测试时需要） ----
Write-Host "[3/5] 解析业务依赖 classpath ..." -ForegroundColor Yellow
$depsFile = Join-Path $proj "target\deps-classpath.txt"
$deps = ""
if (-not (Test-Path $depsFile)) {
    Push-Location $proj
    try { & mvn -B -o -q dependency:build-classpath "-Dmdep.outputFile=$depsFile" 2>&1 | Out-Null } catch { }
    Pop-Location
}
if (Test-Path $depsFile) {
    $deps = (Get-Content $depsFile -Raw).Trim()
    Write-Host "      已从 target/deps-classpath.txt 读取依赖（$($deps.Split(';').Count) 个 jar）"
} else {
    Write-Host "      未生成依赖清单，仅用 target/classes 编译（纯 JDK 用例可过）" -ForegroundColor Yellow
}

# ---- 4. 编译测试源码 ----
Write-Host "[4/5] 编译单元测试（src/unit-test/java）..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$cpParts = @($mainClasses, $deps) + $jars
$cp = ($cpParts | Where-Object { $_ }) -join ";"
$testFiles = Get-ChildItem -Path $srcDir -Filter *.java -Recurse -File | ForEach-Object { $_.FullName }
if (-not $testFiles) { throw "没有找到测试源码：$srcDir" }
& javac -encoding UTF-8 -nowarn -cp $cp -d $outDir $testFiles
if ($LASTEXITCODE -ne 0) { throw "测试代码编译失败（exit=$LASTEXITCODE）" }

# ---- 5. 用 junit-platform-launcher 跑测试 ----
Write-Host "[5/5] 运行测试 ..." -ForegroundColor Yellow
$runCp = (@($outDir, $mainClasses, $deps) + $jars | Where-Object { $_ }) -join ";"
& java "-Dfile.encoding=UTF-8" -cp $runCp com.abc.danmakuservice.OfflineJUnitRunner
$code = $LASTEXITCODE
if ($code -eq 0) {
    Write-Host "全部单元测试通过。" -ForegroundColor Green
} else {
    Write-Host "存在失败用例（exit=$code）。" -ForegroundColor Red
}
exit $code
