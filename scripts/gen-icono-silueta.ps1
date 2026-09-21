# Generates monochrome vector silhouettes of the Novaura logo from logo-sin-fondo.png.
# Pipeline: alpha binarize -> 1x 8-neighbour dilation (connects thin strokes) ->
#           rectilinear directed-edge boundary tracing (guaranteed closed loops) ->
#           Douglas-Peucker simplification -> scaled vector output.
# Produces:
#   app/src/main/res/drawable/ic_novaura_stat_24.xml  (24dp, tinted white, for notifications)
#   app/src/main/res/drawable/ic_novaura_mono.xml      (108dp, centered, for adaptive-icon monochrome)
param(
    [string]$LogoPath = (Join-Path (Split-Path -Parent $PSScriptRoot) "logo-sin-fondo.png")
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$resDir = Join-Path (Split-Path -Parent $PSScriptRoot) "app\src\main\res"
$sampleStep = 3
$alphaThreshold = 128

$bmp = New-Object System.Drawing.Bitmap($LogoPath)
$W = $bmp.Width
$H = $bmp.Height
"logo: ${W}x${H}"

$cols = [int][Math]::Ceiling($W / $sampleStep)
$rows = [int][Math]::Ceiling($H / $sampleStep)

function Get-Cell([bool[]]$g, [int]$r, [int]$c, [int]$rows, [int]$cols) {
    if ($r -lt 0 -or $c -lt 0 -or $r -ge $rows -or $c -ge $cols) { return $false }
    return $g[$r * $cols + $c]
}

# Binarize by alpha
$grid = New-Object 'bool[]' ($rows * $cols)
$cmin = $cols; $cmax = -1; $rmin = $rows; $rmax = -1
for ($r = 0; $r -lt $rows; $r++) {
    $py = [Math]::Min($r * $sampleStep, $H - 1)
    for ($c = 0; $c -lt $cols; $c++) {
        $px = [Math]::Min($c * $sampleStep, $W - 1)
        $filled = $bmp.GetPixel($px, $py).A -gt $alphaThreshold
        $grid[$r * $cols + $c] = $filled
        if ($filled) {
            if ($c -lt $cmin) { $cmin = $c }
            if ($c -gt $cmax) { $cmax = $c }
            if ($r -lt $rmin) { $rmin = $r }
            if ($r -gt $rmax) { $rmax = $r }
        }
    }
}
$bmp.Dispose()

$gW = $cmax - $cmin + 1
$gH = $rmax - $rmin + 1
"silhouette grid bbox: c[$cmin..$cmax] r[$rmin..$rmax]  size ${gW}x${gH}"

# Dilate once (8-neighbour)
$dil = New-Object 'bool[]' ($rows * $cols)
for ($r = 0; $r -lt $rows; $r++) {
    for ($c = 0; $c -lt $cols; $c++) {
        if ($grid[$r * $cols + $c]) { $dil[$r * $cols + $c] = $true; continue }
        $near = $false
        for ($dr = -1; $dr -le 1; $dr++) {
            for ($dc = -1; $dc -le 1; $dc++) {
                if (Get-Cell $grid ($r + $dr) ($c + $dc) $rows $cols) { $near = $true; break }
            }
            if ($near) { break }
        }
        $dil[$r * $cols + $c] = $near
    }
}
$grid = $dil

# Directed boundary edges between cell corners, interior (filled) kept on the right.
$edges = New-Object System.Collections.Generic.List[object]
function Add-Edge([int]$c1, [int]$r1, [int]$c2, [int]$r2) {
    $script:edges.Add(@{ From = @($c1, $r1); To = @($c2, $r2) })
}
for ($r = 0; $r -lt $rows; $r++) {
    for ($c = 0; $c -lt $cols; $c++) {
        if (-not $grid[$r * $cols + $c]) { continue }
        if (-not (Get-Cell $grid ($r - 1) $c $rows $cols)) { Add-Edge $c $r ($c + 1) $r }
        if (-not (Get-Cell $grid ($r + 1) $c $rows $cols)) { Add-Edge ($c + 1) ($r + 1) $c ($r + 1) }
        if (-not (Get-Cell $grid $r ($c - 1) $rows $cols)) { Add-Edge $c ($r + 1) $c $r }
        if (-not (Get-Cell $grid $r ($c + 1) $rows $cols)) { Add-Edge ($c + 1) $r ($c + 1) ($r + 1) }
    }
}
"boundary edges: $($edges.Count)"

# Trace edges into closed loops
$used = New-Object 'bool[]' $edges.Count
$loops = New-Object System.Collections.Generic.List[object]
while ($true) {
    $startIdx = -1
    for ($i = 0; $i -lt $edges.Count; $i++) { if (-not $used[$i]) { $startIdx = $i; break } }
    if ($startIdx -lt 0) { break }

    $loop = New-Object System.Collections.Generic.List[object]
    $cur = $startIdx
    $used[$cur] = $true
    $loop.Add($edges[$cur].From)
    $loop.Add($edges[$cur].To)
    $tail = $edges[$cur].To
    $guard = 0
    while ($guard -lt $edges.Count) {
        $guard++
        $next = -1
        for ($i = 0; $i -lt $edges.Count; $i++) {
            if ($used[$i]) { continue }
            if (($edges[$i].From[0] -eq $tail[0]) -and ($edges[$i].From[1] -eq $tail[1])) { $next = $i; break }
        }
        if ($next -lt 0) { break }
        $used[$next] = $true
        $loop.Add($edges[$next].To)
        $tail = $edges[$next].To
        if (($tail[0] -eq $loop[0][0]) -and ($tail[1] -eq $loop[0][1])) { break }
    }
    $loops.Add($loop)
}
"loops: $($loops.Count)"
$sizeStrs = ($loops | ForEach-Object { $_.Count }) -join ', '
"loop sizes: $sizeStrs"

# Remove consecutive duplicate points in a loop
function Remove-Duplicates($loop) {
    $out = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $loop.Count; $i++) {
        $p = $loop[$i]
        $q = $loop[($i + 1) % $loop.Count]
        if (($p[0] -ne $q[0]) -or ($p[1] -ne $q[1])) { $out.Add($p) }
    }
    return $out
}

# Douglas-Peucker simplification on an open polyline [start..end]
function DistToSegment([double[]]$p, [double[]]$a, [double[]]$b) {
    $dx = $b[0] - $a[0]; $dy = $b[1] - $a[1]
    $len2 = $dx * $dx + $dy * $dy
    if ($len2 -eq 0) { return [Math]::Sqrt(($p[0] - $a[0]) * ($p[0] - $a[0]) + ($p[1] - $a[1]) * ($p[1] - $a[1])) }
    $t = (($p[0] - $a[0]) * $dx + ($p[1] - $a[1]) * $dy) / $len2
    if ($t -lt 0) { $t = 0 }; if ($t -gt 1) { $t = 1 }
    $px = $a[0] + $t * $dx; $py = $a[1] + $t * $dy
    return [Math]::Sqrt(($p[0] - $px) * ($p[0] - $px) + ($p[1] - $py) * ($p[1] - $py))
}

function Simplify-Poly($pts, [double]$tol) {
    $n = $pts.Count
    if ($n -le 3) { return $pts }
    $keep = New-Object 'bool[]' $n
    $keep[0] = $true; $keep[$n - 1] = $true
    $stack = New-Object System.Collections.Generic.Stack[object]
    $stack.Push(@(0, ($n - 1)))
    while ($stack.Count -gt 0) {
        $rng = $stack.Pop()
        $s = [int]$rng[0]; $e = [int]$rng[1]
        $maxD = 0.0; $idx = -1
        for ($i = $s + 1; $i -lt $e; $i++) {
            $d = DistToSegment $pts[$i] $pts[$s] $pts[$e]
            if ($d -gt $maxD) { $maxD = $d; $idx = $i }
        }
        if (($maxD -gt $tol) -and ($idx -ge 0)) {
            $keep[$idx] = $true
            $stack.Push(@($s, $idx))
            $stack.Push(@($idx, $e))
        }
    }
    $out = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $n; $i++) { if ($keep[$i]) { $out.Add($pts[$i]) } }
    return $out
}

# Simplify each loop (chain v = p0..pn-1,p0 so the closing seams simplify too)
function Simplify-Loop($loop, [double]$tol) {
    $clean = Remove-Duplicates $loop
    $n = $clean.Count
    if ($n -le 4) { return $clean }
    $pts = New-Object System.Collections.Generic.List[object]
    foreach ($p in $clean) { $pts.Add($p) }
    $pts.Add($clean[0])
    $simp = Simplify-Poly $pts $tol
    $out = New-Object System.Collections.Generic.List[object]
    for ($i = 1; $i -lt $simp.Count; $i++) { $out.Add($simp[$i]) }
    return $out
}

# Build pathData, maps grid -> viewport
function Build-PathData($loops, $scale, $tx, $ty, $cmin, $rmin, [double]$tol) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($loop in $loops) {
        $poly = Simplify-Loop $loop $tol
        $first = $true
        $prevKey = ''
        foreach ($pt in $poly) {
            $x = $tx + (($pt[0] - $cmin) * $scale)
            $y = $ty + (($pt[1] - $rmin) * $scale)
            $xs = $x.ToString('0.00', [System.Globalization.CultureInfo]::InvariantCulture)
            $ys = $y.ToString('0.00', [System.Globalization.CultureInfo]::InvariantCulture)
            $key = "$xs,$ys"
            if ($first) {
                [void]$sb.Append("M${xs} ${ys} ")
                $first = $false
            } elseif ($key -ne $prevKey) {
                [void]$sb.Append("L${xs} ${ys} ")
            }
            $prevKey = $key
        }
        [void]$sb.Append("Z ")
    }
    return $sb.ToString().Trim()
}

# 24dp status-bar/notification icon: silhouette fits inside a 20dp box, centered.
$view = 24
$scaleStat = 20.0 / $gW
$txStat = ($view - ($gW * $scaleStat)) / 2
$tyStat = ($view - ($gH * $scaleStat)) / 2
$pathStat = Build-PathData $loops $scaleStat $txStat $tyStat $cmin $rmin 0.4

$statXml = @"
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:tint="@android:color/white"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:fillType="evenOdd"
        android:pathData="$pathStat" />
</vector>
"@

$statPath = Join-Path $resDir "drawable\ic_novaura_stat_24.xml"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($statPath, $statXml, $utf8)
"wrote $statPath ($($statXml.Length) bytes)"

# 108dp monochrome layer for themed adaptive icons: silhouette inside the 66dp safe zone.
$viewMono = 108
$safeMono = 66.0
$scaleMono = $safeMono / $gW
$txMono = ($viewMono - ($gW * $scaleMono)) / 2
$tyMono = ($viewMono - ($gH * $scaleMono)) / 2
$pathMono = Build-PathData $loops $scaleMono $txMono $tyMono $cmin $rmin 1.0

$monoXml = @"
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FF000000"
        android:fillType="evenOdd"
        android:pathData="$pathMono" />
</vector>
"@

$monoPath = Join-Path $resDir "drawable\ic_novaura_mono.xml"
[System.IO.File]::WriteAllText($monoPath, $monoXml, $utf8)
"wrote $monoPath ($($monoXml.Length) bytes)"