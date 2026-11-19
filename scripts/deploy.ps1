[CmdletBinding()]
param(
    [ValidateSet("up", "down", "restart", "status", "logs", "check")]
    [string]$Action = "up",
    [switch]$NoBuild,
    [switch]$NoBrowser,
    [switch]$ResetData,
    [switch]$UseOfficialRegistry,
    [ValidateRange(30, 900)]
    [int]$TimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$script:ComposeFile = Join-Path $script:ProjectRoot "docker-compose.yml"
$script:EnvExample = Join-Path $script:ProjectRoot ".env.example"
$script:EnvFile = Join-Path $script:ProjectRoot ".env"
$script:ProjectName = "wenjin-ai-admission"
$script:Utf8NoBom = New-Object Text.UTF8Encoding($false)

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Write-Success([string]$Message) {
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Assert-ProjectFiles {
    foreach ($path in @($script:ComposeFile, $script:EnvExample)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "缺少部署文件：$path"
        }
    }
}

function Test-DockerDaemon {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        return $false
    }
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "SilentlyContinue"
        & docker info --format "{{.ServerVersion}}" 2> $null | Out-Null
        return $LASTEXITCODE -eq 0
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Find-DockerDesktop {
    $command = Get-Command "Docker Desktop.exe" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $uninstallRoots = @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )
    foreach ($root in $uninstallRoots) {
        $entry = Get-ItemProperty $root -ErrorAction SilentlyContinue |
            Where-Object {
                $displayName = $_.PSObject.Properties["DisplayName"]
                $installLocation = $_.PSObject.Properties["InstallLocation"]
                $displayName -and $installLocation -and $displayName.Value -eq "Docker Desktop" -and $installLocation.Value
            } |
            Select-Object -First 1
        if ($entry) {
            $candidate = Join-Path $entry.InstallLocation "Docker Desktop.exe"
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return $candidate
            }
        }
    }
    return $null
}

function Ensure-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "未检测到 Docker CLI，请先安装 Docker Desktop。"
    }
    if (Test-DockerDaemon) {
        Write-Success "Docker Engine 已就绪"
        return
    }

    $desktop = Find-DockerDesktop
    if (-not $desktop) {
        throw "Docker CLI 已安装，但 Docker Engine 未运行；请启动 Docker Desktop 后重试。"
    }

    Write-Step "正在启动 Docker Desktop"
    if (-not (Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue)) {
        Start-Process -FilePath $desktop -WindowStyle Hidden
    }
    $deadline = [DateTimeOffset]::Now.AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Seconds 3
        if (Test-DockerDaemon) {
            Write-Success "Docker Engine 已启动"
            return
        }
    } while ([DateTimeOffset]::Now -lt $deadline)
    throw "等待 Docker Engine 启动超时（$TimeoutSeconds 秒）。"
}

function New-RandomBytes([int]$Length) {
    $bytes = New-Object byte[] $Length
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return $bytes
}

function New-SafePassword {
    return ([Convert]::ToBase64String((New-RandomBytes 30))).TrimEnd("=").Replace("+", "A").Replace("/", "B")
}

function New-EncryptionKey {
    return [Convert]::ToBase64String((New-RandomBytes 32))
}

function Test-DockerHubEndpoint {
    try {
        Invoke-WebRequest -UseBasicParsing -Method Head -Uri "https://registry-1.docker.io/v2/" -TimeoutSec 8 | Out-Null
        return $true
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 401) {
            return $true
        }
        return $false
    }
}

function Test-TcpPortInUse([int]$Port) {
    try {
        return [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port -contains $Port
    } catch {
        return $false
    }
}

function Test-PortOwnedByProject([int]$Port) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "SilentlyContinue"
        $ports = & docker ps --filter "label=com.docker.compose.project=$($script:ProjectName)" --format "{{.Ports}}" 2> $null
        if ($LASTEXITCODE -ne 0) {
            return $false
        }
        return [bool]($ports | Where-Object { $_ -match "(?:0\.0\.0\.0|\[::\]):$Port->" } | Select-Object -First 1)
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Find-AvailablePort([int]$PreferredPort) {
    foreach ($candidate in $PreferredPort..($PreferredPort + 100)) {
        if (-not (Test-TcpPortInUse $candidate)) {
            return $candidate
        }
    }
    throw "端口 $PreferredPort 至 $($PreferredPort + 100) 均被占用，请在 .env 中指定可用端口。"
}

function Get-EnvValue([string[]]$Lines, [string]$Name, [string]$DefaultValue = "") {
    $prefix = "$Name="
    $line = $Lines | Where-Object { $_.StartsWith($prefix, [StringComparison]::Ordinal) } | Select-Object -Last 1
    if ($null -eq $line) {
        return $DefaultValue
    }
    return $line.Substring($prefix.Length)
}

function Set-EnvValue([Collections.Generic.List[string]]$Lines, [string]$Name, [string]$Value) {
    $prefix = "$Name="
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index].StartsWith($prefix, [StringComparison]::Ordinal)) {
            $Lines[$index] = "$Name=$Value"
            return
        }
    }
    $Lines.Add("$Name=$Value")
}

function Initialize-Environment {
    $created = -not (Test-Path -LiteralPath $script:EnvFile -PathType Leaf)
    $source = if ($created) { $script:EnvExample } else { $script:EnvFile }
    $lines = New-Object "Collections.Generic.List[string]"
    [IO.File]::ReadAllLines($source, [Text.Encoding]::UTF8) | ForEach-Object { $lines.Add($_) }

    $changed = $created
    $postgresPassword = Get-EnvValue $lines "POSTGRES_PASSWORD"
    if ([string]::IsNullOrWhiteSpace($postgresPassword) -or $postgresPassword -in @("change-me", "wenjin_dev")) {
        Set-EnvValue $lines "POSTGRES_PASSWORD" (New-SafePassword)
        $changed = $true
        Write-Success "已生成随机 PostgreSQL 密码（不会输出明文）"
    }

    $encryptionKey = Get-EnvValue $lines "AI_CONFIG_ENCRYPTION_KEY"
    if ([string]::IsNullOrWhiteSpace($encryptionKey)) {
        Set-EnvValue $lines "AI_CONFIG_ENCRYPTION_KEY" (New-EncryptionKey)
        $changed = $true
        Write-Success "已生成网页模型配置加密主密钥（不会输出明文）"
    }

    if ([string]::IsNullOrWhiteSpace((Get-EnvValue $lines "FRONTEND_PORT"))) {
        Set-EnvValue $lines "FRONTEND_PORT" "3000"
        $changed = $true
    }
    if ([string]::IsNullOrWhiteSpace((Get-EnvValue $lines "BACKEND_PORT"))) {
        Set-EnvValue $lines "BACKEND_PORT" "8080"
        $changed = $true
    }
    if ([string]::IsNullOrWhiteSpace((Get-EnvValue $lines "COMPOSE_PROJECT_NAME"))) {
        Set-EnvValue $lines "COMPOSE_PROJECT_NAME" $script:ProjectName
        $changed = $true
    }
    $configuredFrontendPort = Get-EnvValue $lines "FRONTEND_PORT" "3000"
    $configuredCors = Get-EnvValue $lines "CORS_ALLOWED_ORIGINS"
    if ($created -or [string]::IsNullOrWhiteSpace($configuredCors)) {
        Set-EnvValue $lines "CORS_ALLOWED_ORIGINS" "http://localhost:$configuredFrontendPort"
        $changed = $true
    }

    $officialImages = @{
        "PGVECTOR_IMAGE" = "pgvector/pgvector:pg17"
        "REDIS_IMAGE" = "redis:7.4-alpine"
        "MAVEN_IMAGE" = "maven:3.9.11-eclipse-temurin-21"
        "JAVA_RUNTIME_IMAGE" = "eclipse-temurin:21-jre"
        "NODE_IMAGE" = "node:24-alpine"
    }
    $imageMappings = @{
        "PGVECTOR_IMAGE" = "docker.m.daocloud.io/pgvector/pgvector:pg17"
        "REDIS_IMAGE" = "docker.m.daocloud.io/library/redis:7.4-alpine"
        "MAVEN_IMAGE" = "docker.m.daocloud.io/library/maven:3.9.11-eclipse-temurin-21"
        "JAVA_RUNTIME_IMAGE" = "docker.m.daocloud.io/library/eclipse-temurin:21-jre"
        "NODE_IMAGE" = "docker.m.daocloud.io/library/node:24-alpine"
    }
    if ($UseOfficialRegistry) {
        foreach ($name in $officialImages.Keys) {
            if ((Get-EnvValue $lines $name) -ne $officialImages[$name]) {
                Set-EnvValue $lines $name $officialImages[$name]
                $changed = $true
            }
        }
        Write-Success "已按请求使用 Docker Hub 官方镜像"
    } elseif (-not (Test-DockerHubEndpoint)) {
        foreach ($name in $imageMappings.Keys) {
            $currentValue = Get-EnvValue $lines $name
            if ([string]::IsNullOrWhiteSpace($currentValue) -or $currentValue -eq $officialImages[$name]) {
                Set-EnvValue $lines $name $imageMappings[$name]
                $changed = $true
            }
        }
        Write-Success "Docker Hub 不可达，已自动启用镜像加速地址"
    }

    if ($changed) {
        [IO.File]::WriteAllLines($script:EnvFile, $lines, $script:Utf8NoBom)
        Write-Success "部署配置已写入 .env"
    } else {
        Write-Success "沿用现有 .env 配置"
    }
    return $lines.ToArray()
}

function Resolve-DeploymentPorts([string[]]$EnvLines) {
    $lines = New-Object "Collections.Generic.List[string]"
    $EnvLines | ForEach-Object { $lines.Add($_) }
    $changed = $false

    $frontendPort = [int](Get-EnvValue $lines "FRONTEND_PORT" "3000")
    $backendPort = [int](Get-EnvValue $lines "BACKEND_PORT" "8080")
    $originalFrontendPort = $frontendPort

    if ((Test-TcpPortInUse $frontendPort) -and -not (Test-PortOwnedByProject $frontendPort)) {
        $frontendPort = Find-AvailablePort ($frontendPort + 1)
        Set-EnvValue $lines "FRONTEND_PORT" "$frontendPort"
        $changed = $true
        Write-Host "[INFO] 前端端口 $originalFrontendPort 已被占用，自动改用 $frontendPort" -ForegroundColor Yellow
    }
    if ((Test-TcpPortInUse $backendPort) -and -not (Test-PortOwnedByProject $backendPort)) {
        $originalBackendPort = $backendPort
        $backendPort = Find-AvailablePort ($backendPort + 1)
        Set-EnvValue $lines "BACKEND_PORT" "$backendPort"
        $changed = $true
        Write-Host "[INFO] 后端端口 $originalBackendPort 已被占用，自动改用 $backendPort" -ForegroundColor Yellow
    }

    $cors = Get-EnvValue $lines "CORS_ALLOWED_ORIGINS"
    if ($frontendPort -ne $originalFrontendPort -and ($cors -eq "http://localhost:$originalFrontendPort" -or [string]::IsNullOrWhiteSpace($cors))) {
        Set-EnvValue $lines "CORS_ALLOWED_ORIGINS" "http://localhost:$frontendPort"
        $changed = $true
    }

    if ($changed) {
        [IO.File]::WriteAllLines($script:EnvFile, $lines, $script:Utf8NoBom)
        Write-Success "端口配置已更新到 .env"
    }
    return $lines.ToArray()
}

function Invoke-Compose([string[]]$Arguments, [switch]$IgnoreFailure) {
    $base = @("compose", "--project-name", $script:ProjectName, "--env-file", $script:EnvFile, "-f", $script:ComposeFile)
    & docker @base @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $IgnoreFailure) {
        throw "docker compose 执行失败，退出码：$exitCode"
    }
}

function Invoke-ComposeWithRetry([string[]]$Arguments, [int]$MaxAttempts = 3) {
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            Invoke-Compose $Arguments
            return
        } catch {
            if ($attempt -eq $MaxAttempts) {
                throw
            }
            $delay = 4 * $attempt
            Write-Host "[WARN] 镜像下载或构建失败，$delay 秒后自动重试（$attempt/$MaxAttempts）" -ForegroundColor Yellow
            Start-Sleep -Seconds $delay
        }
    }
}

function Test-Http([string]$Url) {
    try {
        # 本机容器健康检查不应经过系统代理，否则 localhost 可能被代理连接拖到超时。
        $status = & curl.exe --silent --show-error --location --max-time 5 --output NUL `
            --write-out "%{http_code}" --noproxy "*" $Url 2> $null
        if ($LASTEXITCODE -ne 0) {
            return $false
        }
        $code = 0
        return [int]::TryParse(($status | Select-Object -Last 1), [ref]$code) -and $code -ge 200 -and $code -lt 500
    } catch {
        return $false
    }
}

function Wait-ForApplication([string]$FrontendUrl, [string]$BackendHealthUrl) {
    Write-Step "等待后端健康检查和前端页面"
    $deadline = [DateTimeOffset]::Now.AddSeconds($TimeoutSeconds)
    do {
        $backendReady = Test-Http $BackendHealthUrl
        $frontendReady = Test-Http $FrontendUrl
        if ($backendReady -and $frontendReady) {
            Write-Success "后端健康检查通过"
            Write-Success "前端页面可访问"
            return
        }
        Start-Sleep -Seconds 3
    } while ([DateTimeOffset]::Now -lt $deadline)

    Write-Host "`n容器状态：" -ForegroundColor Yellow
    Invoke-Compose @("ps") -IgnoreFailure
    Write-Host "`n最近日志：" -ForegroundColor Yellow
    Invoke-Compose @("logs", "--tail", "100") -IgnoreFailure
    throw "应用健康检查超时（$TimeoutSeconds 秒）。"
}

function Show-AccessInfo([string]$FrontendUrl, [string]$BackendUrl) {
    Write-Host "`n部署完成" -ForegroundColor Green
    Write-Host "前端：    $FrontendUrl"
    Write-Host "健康检查：$BackendUrl/actuator/health"
    Write-Host "Swagger： $BackendUrl/swagger-ui.html"
    Write-Host "普通账号：demo@wenjin.local / Demo123!"
    Write-Host "管理员：  admin@wenjin.local / Admin123!"
    Write-Host "模型 API：使用管理员账号打开 $FrontendUrl/admin/model-settings"
}

try {
    Set-Location -LiteralPath $script:ProjectRoot
    Assert-ProjectFiles
    Write-Step "检查 Docker 环境"
    Ensure-Docker
    $envLines = Initialize-Environment
    if ($Action -in @("up", "restart")) {
        $envLines = Resolve-DeploymentPorts $envLines
    }
    $frontendPort = Get-EnvValue $envLines "FRONTEND_PORT" "3000"
    $backendPort = Get-EnvValue $envLines "BACKEND_PORT" "8080"
    $frontendUrl = "http://localhost:$frontendPort"
    $backendUrl = "http://localhost:$backendPort"

    if ($ResetData) {
        Write-Step "按请求清理现有容器和数据卷"
        Invoke-Compose @("down", "--volumes", "--remove-orphans")
    }

    switch ($Action) {
        "check" {
            Write-Step "校验 Docker Compose 配置"
            Invoke-Compose @("config", "--quiet")
            Write-Success "一键部署配置校验通过"
        }
        "down" {
            Write-Step "停止应用（保留数据卷）"
            Invoke-Compose @("down", "--remove-orphans")
            Write-Success "应用已停止，数据卷已保留"
        }
        "status" {
            Invoke-Compose @("ps")
        }
        "logs" {
            Invoke-Compose @("logs", "--tail", "200", "--follow")
        }
        default {
            if ($Action -eq "restart") {
                Write-Step "重新创建应用容器"
            } else {
                Write-Step "构建并启动应用"
            }
            $upArgs = @("up", "--detach", "--remove-orphans")
            if (-not $NoBuild) {
                $upArgs += "--build"
            }
            Invoke-ComposeWithRetry $upArgs
            Wait-ForApplication $frontendUrl "$backendUrl/actuator/health"
            Show-AccessInfo $frontendUrl $backendUrl
            if (-not $NoBrowser) {
                Start-Process $frontendUrl
            }
        }
    }
} catch {
    Write-Host "`n[FAILED] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    Set-Location -LiteralPath $script:ProjectRoot
}
