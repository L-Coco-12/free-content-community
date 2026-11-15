param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,64}$')]
    [string]$UserId,
    [uri]$Origin = 'http://localhost:8088'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$expectedOrigin = $Origin.GetLeftPart([UriPartial]::Authority)
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)
try {
    $profileResponse = $client.GetAsync("$expectedOrigin/api/v1/users/$UserId").GetAwaiter().GetResult()
    if ([int]$profileResponse.StatusCode -ne 200) { throw "公开资料读取失败：$([int]$profileResponse.StatusCode)" }
    $profile = ($profileResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).data
    if (-not $profile.avatarUrl) { throw '测试账号需要已绑定且 READY 的头像' }
    $avatarUri = [uri]$profile.avatarUrl
    $actualOrigin = $avatarUri.GetLeftPart([UriPartial]::Authority)
    if ($actualOrigin -ne $expectedOrigin) { throw "头像地址来源错误：expected=$expectedOrigin actual=$actualOrigin" }

    $redirect = $client.GetAsync($avatarUri).GetAwaiter().GetResult()
    if ([int]$redirect.StatusCode -ne 302 -or -not $redirect.Headers.Location) { throw "头像跳转失败：$([int]$redirect.StatusCode)" }
    $image = $client.GetAsync($redirect.Headers.Location).GetAwaiter().GetResult()
    if ([int]$image.StatusCode -ne 200) { throw "头像图片读取失败：$([int]$image.StatusCode)" }
    if ($image.Content.Headers.ContentType.MediaType -notlike 'image/*') { throw '头像响应不是图片' }
    $imageBytes = $image.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult().Length
    if ($imageBytes -lt 1) { throw '头像图片为空' }
    # 不输出存储签名、对象键或图片内容；此检查只读，不修改账号或素材。
    [pscustomobject]@{ Origin = $actualOrigin; Profile = 200; Redirect = 302; Image = 200; Bytes = $imageBytes }
} finally {
    $client.Dispose()
    $handler.Dispose()
}
