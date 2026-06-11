param(
    [string]$RedisCli = "redis-cli",
    [string]$HostName = "192.168.130.128",
    [int]$Port = 6379,
    [int]$Database = 0,
    [string]$Password = "",
    [string[]]$PostIds = @(
        "305978636022976512",
        "306010618199150592",
        "306012407921250304",
        "306018405683695616",
        "306023544435904512",
        "312045628760920064",
        "312099950437732352",
        "305696708409561088",
        "305937767383306240"
    ),
    [switch]$IncludeFeedCache,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$redisArgs = @("-h", $HostName, "-p", "$Port", "-n", "$Database")
if ($Password -ne "") {
    $redisArgs += @("-a", $Password)
}

function Invoke-Redis {
    param([string[]]$ArgsList)
    & $RedisCli @redisArgs @ArgsList
}

function Remove-RedisKey {
    param([string]$Key)
    if ([string]::IsNullOrWhiteSpace($Key)) {
        return
    }

    if ($DryRun) {
        Write-Host "[DRY-RUN] DEL $Key"
        return
    }

    $result = Invoke-Redis @("DEL", $Key)
    if ($result -ne "0") {
        Write-Host "DEL $Key -> $result"
    }
}

function Remove-RedisPattern {
    param([string]$Pattern)

    Write-Host "Scanning pattern: $Pattern"
    $keys = & $RedisCli @redisArgs --scan --pattern $Pattern
    foreach ($key in $keys) {
        Remove-RedisKey $key
    }
}

Write-Host "Redis: ${HostName}:${Port}, db=${Database}"
Write-Host "Posts: $($PostIds -join ', ')"
if ($DryRun) {
    Write-Host "Mode: dry-run"
} else {
    Write-Host "Mode: delete"
}

foreach ($postId in $PostIds) {
    Remove-RedisPattern "bm:like:knowpost:${postId}:*"
    Remove-RedisKey "cnt:v1:knowpost:${postId}"
    Remove-RedisKey "agg:v1:knowpost:${postId}"
    Remove-RedisKey "backoff:sds-rebuild:until:knowpost:${postId}"
    Remove-RedisKey "backoff:sds-rebuild:exp:knowpost:${postId}"
    Remove-RedisKey "rl:sds-rebuild:knowpost:${postId}"
    Remove-RedisKey "lock:sds-rebuild:knowpost:${postId}"

    if ($IncludeFeedCache) {
        Remove-RedisKey "knowpost:detail:${postId}:v1"
        Remove-RedisKey "feed:item:${postId}"
        Remove-RedisPattern "feed:public:index:${postId}:*"
    }
}

if ($IncludeFeedCache) {
    Remove-RedisPattern "feed:public:*"
    Remove-RedisPattern "feed:mine:*"
}

Write-Host "Done."
