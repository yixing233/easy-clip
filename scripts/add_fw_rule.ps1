$ErrorActionPreference = 'Stop'
try {
    New-NetFirewallRule -DisplayName 'SyncClipboard Server 5033' -Direction Inbound -Protocol TCP -LocalPort 5033 -Action Allow -Profile Any | Out-File -FilePath 'E:\Code\syncclipboard\fw_rule_result.txt' -Encoding utf8
    'OK' | Out-File -FilePath 'E:\Code\syncclipboard\fw_rule_result.txt' -Encoding utf8 -Append
} catch {
    $_.Exception.Message | Out-File -FilePath 'E:\Code\syncclipboard\fw_rule_result.txt' -Encoding utf8
}
