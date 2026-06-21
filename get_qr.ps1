$remote = git remote get-url origin
if ($remote -match 'https://[^:]+:([^@]+)@') {
    $token = $Matches[1]
} else {
    $token = $env:GITHUB_TOKEN
}
$headers = @{ "Authorization" = "token $token"; "Accept" = "application/vnd.github.v3+json" }
$runs = Invoke-RestMethod -Uri "https://api.github.com/repos/kh-saadany/mushaf-qiyam/actions/artifacts" -Headers $headers
$apkArtifact = $runs.artifacts | Where-Object { $_.name -eq 'mushaf-qiyam-apk' } | Select-Object -First 1
Invoke-WebRequest -Uri $apkArtifact.archive_download_url -Headers $headers -OutFile "artifact.zip"
Expand-Archive -Force -Path "artifact.zip" -DestinationPath "apk_folder"
$server = (Invoke-RestMethod -Uri "https://api.gofile.io/servers").data.servers[0].name
$response = curl.exe -s -F "file=@apk_folder\mushaf-qiyam.apk" "https://${server}.gofile.io/contents/uploadfile" | ConvertFrom-Json
$link = $response.data.downloadPage
Invoke-WebRequest -Uri "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=$([uri]::EscapeDataString($link))" -OutFile "C:\Users\Khaled El_Saadany\.gemini\antigravity\brain\94762066-497a-4788-8685-8bd5d01e6b16\qr_apk.png"
Write-Output "Link: $link"
