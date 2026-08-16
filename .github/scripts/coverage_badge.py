#!/usr/bin/env python3
"""JaCoCo 形式の XML レポート（複数可）から行カバレッジを算出し、shields 風のバッジ SVG を書き出す。

入力は kover XML（JVM ユニットテストレーン）と AGP の JaCoCo XML（エミュレータの instrumented
テストレーン）の混在を想定する。同じクラスが複数レーンのレポートに現れるため、counter の単純合算では
二重カウントになる。(パッケージ, ソースファイル, 行番号) をキーにしたライン単位のユニオンで数える:
いずれかのレーンで実行された行はカバー済み、分母は全レポートに現れた実行可能行の和集合。

指標は LINE（行）: 一部の命令だけ実行された行もカバー済みと数える、一般に引用される標準的な定義。

使い方: coverage_badge.py <report.xml>... <output.svg>
"""
import sys
import xml.etree.ElementTree as ET

TEMPLATE = """\
<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="20" role="img" aria-label="coverage: {value}">
  <title>coverage: {value}</title>
  <linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>
  <clipPath id="r"><rect width="{w}" height="20" rx="3" fill="#fff"/></clipPath>
  <g clip-path="url(#r)">
    <rect width="{lw}" height="20" fill="#555"/>
    <rect x="{lw}" width="{vw}" height="20" fill="{color}"/>
    <rect width="{w}" height="20" fill="url(#s)"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="110" text-rendering="geometricPrecision">
    <text x="{lx}" y="150" transform="scale(.1)" fill="#010101" fill-opacity=".3">coverage</text>
    <text x="{lx}" y="140" transform="scale(.1)">coverage</text>
    <text x="{vx}" y="150" transform="scale(.1)" fill="#010101" fill-opacity=".3">{value}</text>
    <text x="{vx}" y="140" transform="scale(.1)">{value}</text>
  </g>
</svg>
"""


def color(pct: float) -> str:
    if pct >= 90:
        return "#4c1"  # brightgreen
    if pct >= 80:
        return "#97ca00"  # green
    if pct >= 70:
        return "#dfb317"  # yellow
    if pct >= 60:
        return "#fe7d37"  # orange
    return "#e05d44"  # red


def main() -> None:
    reports, out = sys.argv[1:-1], sys.argv[-1]
    lines: dict[tuple[str, str, str], bool] = {}
    for path in reports:
        for package in ET.parse(path).getroot().findall("package"):
            for sourcefile in package.findall("sourcefile"):
                for line in sourcefile.findall("line"):
                    key = (package.get("name"), sourcefile.get("name"), line.get("nr"))
                    lines[key] = lines.get(key, False) or int(line.get("ci", "0")) > 0
    covered = sum(lines.values())
    total = len(lines)
    # 計測対象コードがまだ無い（スケルトン段階）でもバッジ生成を落とさない
    if total == 0:
        value, badge_color = "n/a", "#9f9f9f"  # lightgrey
    else:
        pct = covered / total * 100
        value, badge_color = f"{pct:.1f}%", color(pct)

    label_width = 61
    value_width = 12 + 8 * len(value)
    svg = TEMPLATE.format(
        value=value,
        color=badge_color,
        w=label_width + value_width,
        lw=label_width,
        vw=value_width,
        lx=label_width * 5,
        vx=(label_width + value_width / 2) * 10,
    )
    with open(out, "w") as f:
        f.write(svg)
    print(value)


if __name__ == "__main__":
    main()
