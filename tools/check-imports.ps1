# 交叉引用检查：
#  1) 收集所有项目内类型的"简单名 -> 全限定名"
#  2) 对每个文件的 import / 代码引用，检查：
#     - 引用了项目内类型，但没有 import，也没有用全限定名，也不在同包 —— 报错
#     - import 了项目内不存在的类型（拼写错误）—— 报错
param([string]$Root = ".")

$rootPath = (Resolve-Path $Root).Path
$files = Get-ChildItem -Recurse -Path $Root -Filter *.java

# ---------- 1) 建表 ----------
$typeToFqcn = @{}
$fileInfo = @()

foreach ($f in $files) {
    $src = [System.IO.File]::ReadAllText($f.FullName)
    $pkg = ''
    if ($src -match '(?m)^\s*package\s+([\w\.]+)\s*;') { $pkg = $Matches[1] }

    $types = New-Object System.Collections.Generic.List[string]
    # 顶层声明（行首无缩进）
    foreach ($m in [regex]::Matches($src, '(?m)^(?:@\w+[^\r\n]*[\r\n]\s*)*(?:public\s+|final\s+|abstract\s+|sealed\s+)*(?:class|interface|enum|record)\s+(\w+)')) {
        $types.Add($m.Groups[1].Value)
    }
    # 嵌套声明（有缩进，包含 record / 静态内部类等）
    foreach ($m in [regex]::Matches($src, '(?m)^\s+(?:public\s+|private\s+|protected\s+|static\s+|final\s+)*(?:class|interface|enum|record)\s+(\w+)')) {
        if (-not $types.Contains($m.Groups[1].Value)) { $types.Add($m.Groups[1].Value) }
    }

    foreach ($t in $types) {
        if (-not $typeToFqcn.ContainsKey($t)) {
            $typeToFqcn[$t] = "$pkg.$t"
        } else {
            # 同名类型冲突，标记为歧义
            $typeToFqcn[$t] = "AMBIGUOUS"
        }
    }

    $fileInfo += [pscustomobject]@{
        Path = $f.FullName
        Rel  = $f.FullName.Replace($rootPath, '').TrimStart('\')
        Pkg  = $pkg
        Src  = $src
        Types = $types
    }
}

Write-Output "=== 项目内类型：$($typeToFqcn.Count) 个 ==="

# ---------- 2) 逐文件检查 ----------
$errors = 0
$warnings = 0

foreach ($info in $fileInfo) {
    $src = $info.Src

    # 本项目已有 import
    $imports = New-Object System.Collections.Generic.List[string]
    foreach ($m in [regex]::Matches($src, '(?m)^\s*import\s+(?:static\s+)?([\w\.]+)\s*;')) {
        $imports.Add($m.Groups[1].Value)
    }

    # 去掉注释与字符串，避免误判
    $code = [regex]::Replace($src, '(?s)/\*.*?\*/', ' ')
    $code = [regex]::Replace($code, '(?m)//.*$', ' ')
    $code = [regex]::Replace($code, '(?s)""".*?"""', '""')
    $code = [regex]::Replace($code, '"(?:\\.|[^"\\])*"', '""')
    $code = [regex]::Replace($code, "'(?:\\.|[^'\\])*'", "''")

    # 2a) import 项目内类型，但该类型不存在（拼写错误）
    foreach ($imp in $imports) {
        if (-not $imp.StartsWith('com.qqlink.')) { continue }
        $simple = $imp.Split('.')[-1]
        if (-not $typeToFqcn.ContainsKey($simple)) {
            Write-Output "[未知类型的 import] $($info.Rel)  ->  $imp"
            $errors++
        }
    }

    # 2b) 代码里引用了项目内类型，但既没 import 也不同包
    foreach ($kv in $typeToFqcn.GetEnumerator()) {
        $simple = $kv.Key
        $fqcn = $kv.Value
        if ($fqcn -eq 'AMBIGUOUS') { continue }
        if ($info.Types -contains $simple) { continue }   # 自己声明的类型

        # 是否在代码里作为"独立标识符"出现
        if ($code -notmatch "(?<![\w\.\$])$([regex]::Escape($simple))(?![\w\$])") { continue }

        $typePkg = $fqcn.Substring(0, $fqcn.Length - $simple.Length - 1)
        if ($typePkg -eq $info.Pkg) { continue }          # 同包，无需 import

        $imported = $false
        foreach ($imp in $imports) {
            if ($imp -eq $fqcn) { $imported = $true; break }
            if ($imp.EndsWith(".$simple")) { $imported = $true; break }
        }
        if (-not $imported) {
            Write-Output "[缺少 import] $($info.Rel)  ->  $fqcn"
            $errors++
        }
    }
}

Write-Output ""
Write-Output "检查完成：$errors 个错误。"
