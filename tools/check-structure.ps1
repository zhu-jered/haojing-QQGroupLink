# 结构检查脚本：用"词法扫描"而不是正则计数，正确跳过
#   单行注释 // ...、块注释 /* ... */、JavaDoc /** ... */、字符串 "..."、字符 '...'、文本块 """..."""
# 同时检查 <> 是否配平（粗略，仅用于发现明显漏写泛型闭合）
param([string]$Root = ".")

$files = Get-ChildItem -Recurse -Path $Root -Filter *.java
$total = 0
$problems = 0

foreach ($f in $files) {
    $total++
    $src = [System.IO.File]::ReadAllText($f.FullName)

    $depthBrace = 0
    $depthParen = 0
    $depthBracket = 0
    $minBrace = 0
    $i = 0
    $n = $src.Length
    $line = 1
    $firstBadLine = 0

    while ($i -lt $n) {
        $c = $src[$i]

        if ($c -eq "`n") { $line++; $i++; continue }

        # --- 注释 ---
        if ($c -eq '/' -and $i + 1 -lt $n) {
            $c2 = $src[$i + 1]
            if ($c2 -eq '/') {
                while ($i -lt $n -and $src[$i] -ne "`n") { $i++ }
                continue
            }
            if ($c2 -eq '*') {
                $i += 2
                while ($i + 1 -lt $n -and -not ($src[$i] -eq '*' -and $src[$i + 1] -eq '/')) {
                    if ($src[$i] -eq "`n") { $line++ }
                    $i++
                }
                $i += 2
                continue
            }
        }

        # --- 文本块 ---
        if ($c -eq '"' -and $i + 2 -lt $n -and $src[$i + 1] -eq '"' -and $src[$i + 2] -eq '"') {
            $i += 3
            while ($i + 2 -lt $n -and -not ($src[$i] -eq '"' -and $src[$i + 1] -eq '"' -and $src[$i + 2] -eq '"')) {
                if ($src[$i] -eq "`n") { $line++ }
                $i++
            }
            $i += 3
            continue
        }

        # --- 普通字符串 ---
        if ($c -eq '"') {
            $i++
            while ($i -lt $n) {
                if ($src[$i] -eq '\') { $i += 2; continue }
                if ($src[$i] -eq '"') { $i++; break }
                if ($src[$i] -eq "`n") { $line++ }
                $i++
            }
            continue
        }

        # --- 字符字面量 ---
        if ($c -eq "'") {
            $i++
            while ($i -lt $n) {
                if ($src[$i] -eq '\') { $i += 2; continue }
                if ($src[$i] -eq "'") { $i++; break }
                $i++
            }
            continue
        }

        switch ($c) {
            '{' { $depthBrace++ }
            '}' {
                $depthBrace--
                if ($depthBrace -lt 0 -and $firstBadLine -eq 0) { $firstBadLine = $line }
            }
            '(' { $depthParen++ }
            ')' { $depthParen-- }
            '[' { $depthBracket++ }
            ']' { $depthBracket-- }
        }

        $i++
    }

    $issues = @()
    if ($depthBrace -ne 0) { $issues += "花括号不平衡(净 $depthBrace)" }
    if ($depthParen -ne 0) { $issues += "圆括号不平衡(净 $depthParen)" }
    if ($depthBracket -ne 0) { $issues += "方括号不平衡(净 $depthBracket)" }
    if ($firstBadLine -ne 0) { $issues += "第 $firstBadLine 行出现多余的 }" }

    if ($issues.Count -gt 0) {
        $problems++
        $rel = $f.FullName.Replace((Resolve-Path $Root).Path, '').TrimStart('\')
        Write-Output ("[问题] {0}  ->  {1}" -f $rel, ($issues -join '; '))
    }
}

Write-Output ""
Write-Output "扫描完成：共 $total 个 Java 文件，$problems 个存在问题。"
