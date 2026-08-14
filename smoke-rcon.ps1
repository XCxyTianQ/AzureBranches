# AzureBranches smoke-test RCON helper.
# Usage: pwsh -File smoke-rcon.ps1 -Command "scoreboard objectives list"
param(
    [string]$Hostname = "127.0.0.1",
    [int]$Port = 25575,
    [string]$Password = "test1234",
    [string]$Command
)

$ErrorActionPreference = "Stop"

function Read-Packet([System.IO.Stream]$stream) {
    $lenBuf = New-Object byte[] 4
    if ($stream.Read($lenBuf, 0, 4) -lt 4) { throw "connection closed while reading length" }
    $len = [BitConverter]::ToInt32($lenBuf, 0)
    $rest = New-Object byte[] $len
    $off = 0
    while ($off -lt $len) {
        $n = $stream.Read($rest, $off, $len - $off)
        if ($n -le 0) { throw "connection closed while reading packet" }
        $off += $n
    }
    $id = [BitConverter]::ToInt32($rest, 0)
    $type = [BitConverter]::ToInt32($rest, 4)
    $text = [System.Text.Encoding]::UTF8.GetString($rest, 8, $len - 10)
    return @{ Id = $id; Type = $type; Text = $text }
}

function Send-Packet([System.IO.Stream]$stream, [int]$id, [int]$type, [string]$body) {
    $payload = [System.Text.Encoding]::UTF8.GetBytes($body)  # server adds its own terminators on response; request needs two nulls
    $len = $payload.Length + 4 + 4 + 2
    $buf = New-Object byte[] (4 + $len)
    [BitConverter]::GetBytes([int]$len).CopyTo($buf, 0)
    [BitConverter]::GetBytes([int]$id).CopyTo($buf, 4)
    [BitConverter]::GetBytes([int]$type).CopyTo($buf, 8)
    $payload.CopyTo($buf, 12)
    $stream.Write($buf, 0, $buf.Length)
    $stream.Flush()
}

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($Hostname, $Port)
$stream = $client.GetStream()
$stream.ReadTimeout = 10000

# authenticate
Send-Packet $stream 999 3 $Password
$auth = Read-Packet $stream
if ($auth.Id -eq -1) { throw "RCON auth failed" }
Write-Host "[RCON] authenticated"

# send command
Send-Packet $stream 1000 2 $Command

# collect response packets (last packet is shorter than max)
$parts = New-Object System.Collections.Generic.List[string]
$guard = 0
while ($true) {
    $resp = Read-Packet $stream
    if ($resp.Id -ne 1000) { continue }
    $parts.Add($resp.Text)
    $guard++
    if ($guard -gt 50) { throw "too many response packets" }
    # vanilla server max packet 1460; a full packet signals more to come
    if ($resp.Text.Length -lt 1400) { break }
}
$client.Close()

Write-Host "=== RCON RESPONSE ==="
$parts | ForEach-Object { Write-Host $_ }
