#!/usr/bin/env python3
"""
tools/lucide-convert.py
Converts Lucide SVG icons into Android Jetpack Compose ImageVector builder code.
Generates app/src/main/java/io/github/ryoaonetsuki/mira/ui/icons/Lucide.kt
"""

import json
import os
import sys
import xml.etree.ElementTree as ET

# Root directories
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS_DIR = os.path.join(REPO_ROOT, "tools")
ICONS_JSON_PATH = os.path.join(TOOLS_DIR, "icons.json")
LUCIDE_SRC_DIR = "/tmp/lucide/icons"
OUTPUT_KT_PATH = os.path.join(
    REPO_ROOT,
    "app", "src", "main", "java", "io", "github", "ryoaonetsuki", "mira", "ui", "icons", "Lucide.kt"
)

# Mapping from Material Rounded icon name to canonical Lucide icon name (kebab-case)
MATERIAL_TO_LUCIDE = {
    "Accessibility": "accessibility",
    "Add": "plus",
    "AddComment": "message-square-plus",
    "Alarm": "alarm-clock",
    "Apps": "layout-grid",
    "ArrowBack": "arrow-left",
    "ArrowBackIosNew": "chevron-left",
    "ArrowForward": "arrow-right",
    "ArrowForwardIos": "chevron-right",
    "ArrowOutward": "arrow-up-right",
    "AutoAwesome": "sparkles",
    "Backup": "cloud-upload",
    "BatteryChargingFull": "battery-charging",
    "Book": "book",
    "Brush": "brush",
    "CalendarToday": "calendar",
    "Call": "phone-call",
    "CameraAlt": "camera",
    "Campaign": "megaphone",
    "Celebration": "party-popper",
    "CenterFocusStrong": "focus",
    "Chat": "message-circle",
    "ChatBubble": "message-square",
    "Check": "check",
    "CheckCircle": "circle-check",
    "ChevronRight": "chevron-right",
    "Close": "x",
    "CloudQueue": "cloud",
    "Code": "code",
    "Coffee": "coffee",
    "ContentCopy": "copy",
    "DeleteOutline": "trash",
    "DeleteSweep": "broom",
    "Description": "file-text",
    "Download": "download",
    "Email": "mail",
    "Extension": "puzzle",
    "Face": "face-slightly-smiling",
    "Favorite": "heart",
    "FitnessCenter": "dumbbell",
    "Folder": "folder",
    "FolderOpen": "folder-open",
    "GraphicEq": "audio-lines",
    "Headphones": "headphones",
    "Home": "house",
    "Html": "file-code",
    "Image": "image",
    "Info": "info",
    "Language": "globe",
    "Laptop": "laptop",
    "Layers": "layers",
    "LineWeight": "pen-line",
    "Link": "link",
    "Lock": "lock",
    "Map": "map",
    "MarkChatUnread": "message-square-dot",
    "Menu": "menu",
    "Mic": "mic",
    "Mood": "face-slightly-smiling",
    "MusicNote": "eighth-note",
    "Newspaper": "newspaper",
    "NightsStay": "moon-star",
    "Notifications": "bell",
    "NotificationsNone": "bell",
    "OpenInNew": "external-link",
    "Person": "user",
    "PhoneAndroid": "smartphone",
    "PhotoLibrary": "images",
    "PlayArrow": "play",
    "PlayCircle": "circle-play",
    "QrCodeScanner": "scan-qr-code",
    "RecordVoiceOver": "speech",
    "Refresh": "refresh-cw",
    "Rule": "scale",
    "Save": "save",
    "School": "graduation-cap",
    "Search": "search",
    "Security": "shield-check",
    "Send": "send",
    "SentimentSatisfied": "face-slightly-smiling",
    "Settings": "settings",
    "Share": "share-2",
    "Sms": "message-square-text",
    "Stop": "square",
    "SystemUpdate": "arrow-down-to-line",
    "TrendingUp": "trending-up",
    "Tune": "sliders-horizontal",
    "Undo": "undo",
    "Upload": "upload",
    "VerifiedUser": "user-check",
    "Visibility": "eye",
    "VisibilityOff": "eye-off",
    "VpnKey": "key",
    "WbSunny": "sun"
}

def to_camel_case(s: str) -> str:
    parts = s.split('-')
    return parts[0] + ''.join(p.capitalize() for p in parts[1:])

def format_float(val: float) -> str:
    s = f"{val:.4f}".rstrip('0').rstrip('.')
    if '.' not in s:
        s += ".0"
    return s + "f"

def parse_svg_path(d: str):
    i = 0
    n = len(d)

    def skip_wsp_and_comma():
        nonlocal i
        while i < n and (d[i].isspace() or d[i] == ','):
            i += 1

    def read_number():
        nonlocal i
        skip_wsp_and_comma()
        if i >= n:
            return None
        start = i
        if d[i] in '+-':
            i += 1
        has_dot = False
        while i < n:
            ch = d[i]
            if ch.isdigit():
                i += 1
            elif ch == '.' and not has_dot:
                has_dot = True
                i += 1
            elif ch in 'eE' and i < n - 1 and (d[i+1].isdigit() or d[i+1] in '+-'):
                i += 2
                while i < n and d[i].isdigit():
                    i += 1
                break
            else:
                break
        if start == i:
            return None
        return float(d[start:i])

    def read_flag():
        nonlocal i
        skip_wsp_and_comma()
        if i >= n:
            return None
        ch = d[i]
        if ch in ('0', '1'):
            i += 1
            return int(ch)
        return None

    commands = []
    current_cmd = None

    while i < n:
        skip_wsp_and_comma()
        if i >= n:
            break
        c = d[i]
        if c in 'MmLlHhVvCcSsQqTtAaZz':
            current_cmd = c
            i += 1
        elif current_cmd is None:
            break

        if current_cmd in 'Zz':
            commands.append((current_cmd, []))
            current_cmd = None
        elif current_cmd in 'HhVv':
            val = read_number()
            if val is not None:
                commands.append((current_cmd, [val]))
        elif current_cmd in 'MmLlTt':
            x = read_number()
            y = read_number()
            if x is not None and y is not None:
                commands.append((current_cmd, [x, y]))
                if current_cmd == 'M':
                    current_cmd = 'L'
                elif current_cmd == 'm':
                    current_cmd = 'l'
        elif current_cmd in 'SsQq':
            x1 = read_number()
            y1 = read_number()
            x = read_number()
            y = read_number()
            if None not in (x1, y1, x, y):
                commands.append((current_cmd, [x1, y1, x, y]))
        elif current_cmd in 'Cc':
            x1 = read_number()
            y1 = read_number()
            x2 = read_number()
            y2 = read_number()
            x = read_number()
            y = read_number()
            if None not in (x1, y1, x2, y2, x, y):
                commands.append((current_cmd, [x1, y1, x2, y2, x, y]))
        elif current_cmd in 'Aa':
            rx = read_number()
            ry = read_number()
            rot = read_number()
            large_arc = read_flag()
            sweep = read_flag()
            x = read_number()
            y = read_number()
            if None not in (rx, ry, rot, large_arc, sweep, x, y):
                commands.append((current_cmd, [rx, ry, rot, large_arc, sweep, x, y]))

    return commands

def convert_svg_to_vector_builder(svg_path: str, icon_name: str, prop_name: str) -> str:
    tree = ET.parse(svg_path)
    root = tree.getroot()

    lines = []
    lines.append(f"    val {prop_name}: ImageVector by lazy {{")
    lines.append("        ImageVector.Builder(")
    lines.append(f'            name = "{icon_name}",')
    lines.append("            defaultWidth = 24.dp,")
    lines.append("            defaultHeight = 24.dp,")
    lines.append("            viewportWidth = 24f,")
    lines.append("            viewportHeight = 24f")
    lines.append("        ).apply {")
    lines.append("            path(")
    lines.append("                stroke = SolidColor(Color.Black),")
    lines.append("                strokeLineWidth = 2f,")
    lines.append("                strokeLineCap = StrokeCap.Round,")
    lines.append("                strokeLineJoin = StrokeJoin.Round")
    lines.append("            ) {")

    for elem in root:
        tag = elem.tag.split('}')[-1]
        if tag == 'circle':
            cx = float(elem.attrib.get('cx', '0'))
            cy = float(elem.attrib.get('cy', '0'))
            r = float(elem.attrib.get('r', '0'))
            lines.append(f"                moveTo({format_float(cx - r)}, {format_float(cy)})")
            lines.append(f"                arcTo({format_float(r)}, {format_float(r)}, 0.0f, isMoreThanHalf = false, isPositiveArc = true, {format_float(cx + r)}, {format_float(cy)})")
            lines.append(f"                arcTo({format_float(r)}, {format_float(r)}, 0.0f, isMoreThanHalf = false, isPositiveArc = true, {format_float(cx - r)}, {format_float(cy)})")
            lines.append("                close()")
        elif tag == 'line':
            x1 = float(elem.attrib.get('x1', '0'))
            y1 = float(elem.attrib.get('y1', '0'))
            x2 = float(elem.attrib.get('x2', '0'))
            y2 = float(elem.attrib.get('y2', '0'))
            lines.append(f"                moveTo({format_float(x1)}, {format_float(y1)})")
            lines.append(f"                lineTo({format_float(x2)}, {format_float(y2)})")
        elif tag == 'rect':
            x = float(elem.attrib.get('x', '0'))
            y = float(elem.attrib.get('y', '0'))
            w = float(elem.attrib.get('width', '0'))
            h = float(elem.attrib.get('height', '0'))
            rx = float(elem.attrib.get('rx', '0'))
            ry = float(elem.attrib.get('ry', str(rx)))
            if rx > 0 or ry > 0:
                if rx == 0: rx = ry
                if ry == 0: ry = rx
                lines.append(f"                moveTo({format_float(x + rx)}, {format_float(y)})")
                lines.append(f"                lineTo({format_float(x + w - rx)}, {format_float(y)})")
                lines.append(f"                arcTo({format_float(rx)}, {format_float(ry)}, 0.0f, isMoreThanHalf = false, isPositiveArc = true, {format_float(x + w)}, {format_float(y + ry)})")
                lines.append(f"                lineTo({format_float(x + w)}, {format_float(y + h - ry)})")
                lines.append(f"                arcTo({format_float(rx)}, {format_float(ry)}, 0.0f, isMoreThanHalf = false, isPositiveArc = true, {format_float(x + w - rx)}, {format_float(y + h)})")
                lines.append(f"                lineTo({format_float(x + rx)}, {format_float(y + h)})")
                lines.append(f"                arcTo({format_float(rx)}, {format_float(ry)}, 0.0f, isMoreThanHalf = false, isPositiveArc = true, {format_float(x)}, {format_float(y + h - ry)})")
                lines.append(f"                lineTo({format_float(x)}, {format_float(y + ry)})")
                lines.append(f"                arcTo({format_float(rx)}, {format_float(ry)}, 0.0f, isMoreThanHalf = false, isPositiveArc = true, {format_float(x + rx)}, {format_float(y)})")
                lines.append("                close()")
            else:
                lines.append(f"                moveTo({format_float(x)}, {format_float(y)})")
                lines.append(f"                lineTo({format_float(x + w)}, {format_float(y)})")
                lines.append(f"                lineTo({format_float(x + w)}, {format_float(y + h)})")
                lines.append(f"                lineTo({format_float(x)}, {format_float(y + h)})")
                lines.append("                close()")
        elif tag in ('polyline', 'polygon'):
            raw_pts = elem.attrib.get('points', '').replace(',', ' ').split()
            pts = [float(p) for p in raw_pts]
            if len(pts) >= 2:
                lines.append(f"                moveTo({format_float(pts[0])}, {format_float(pts[1])})")
                for p_i in range(2, len(pts), 2):
                    lines.append(f"                lineTo({format_float(pts[p_i])}, {format_float(pts[p_i+1])})")
                if tag == 'polygon':
                    lines.append("                close()")
        elif tag == 'path':
            d = elem.attrib.get('d', '')
            cmds = parse_svg_path(d)
            for c_idx, (c, args) in enumerate(cmds):
                # In SVG spec, initial 'm' command on a path is treated as absolute moveTo
                if c == 'm' and c_idx == 0:
                    lines.append(f"                moveTo({format_float(args[0])}, {format_float(args[1])})")
                elif c == 'M':
                    lines.append(f"                moveTo({format_float(args[0])}, {format_float(args[1])})")
                elif c == 'm':
                    lines.append(f"                moveToRelative({format_float(args[0])}, {format_float(args[1])})")
                elif c == 'L':
                    lines.append(f"                lineTo({format_float(args[0])}, {format_float(args[1])})")
                elif c == 'l':
                    lines.append(f"                lineToRelative({format_float(args[0])}, {format_float(args[1])})")
                elif c == 'H':
                    lines.append(f"                horizontalLineTo({format_float(args[0])})")
                elif c == 'h':
                    lines.append(f"                horizontalLineToRelative({format_float(args[0])})")
                elif c == 'V':
                    lines.append(f"                verticalLineTo({format_float(args[0])})")
                elif c == 'v':
                    lines.append(f"                verticalLineToRelative({format_float(args[0])})")
                elif c == 'C':
                    lines.append(f"                curveTo({format_float(args[0])}, {format_float(args[1])}, {format_float(args[2])}, {format_float(args[3])}, {format_float(args[4])}, {format_float(args[5])})")
                elif c == 'c':
                    lines.append(f"                curveToRelative({format_float(args[0])}, {format_float(args[1])}, {format_float(args[2])}, {format_float(args[3])}, {format_float(args[4])}, {format_float(args[5])})")
                elif c == 'S':
                    lines.append(f"                reflectiveCurveTo({format_float(args[0])}, {format_float(args[1])}, {format_float(args[2])}, {format_float(args[3])})")
                elif c == 's':
                    lines.append(f"                reflectiveCurveToRelative({format_float(args[0])}, {format_float(args[1])}, {format_float(args[2])}, {format_float(args[3])})")
                elif c == 'A':
                    lines.append(f"                arcTo({format_float(args[0])}, {format_float(args[1])}, {format_float(args[2])}, isMoreThanHalf = {str(args[3] == 1).lower()}, isPositiveArc = {str(args[4] == 1).lower()}, {format_float(args[5])}, {format_float(args[6])})")
                elif c == 'a':
                    lines.append(f"                arcToRelative({format_float(args[0])}, {format_float(args[1])}, {format_float(args[2])}, isMoreThanHalf = {str(args[3] == 1).lower()}, isPositiveArc = {str(args[4] == 1).lower()}, {format_float(args[5])}, {format_float(args[6])})")
                elif c in ('Z', 'z'):
                    lines.append("                close()")

    lines.append("            }")
    lines.append("        }.build()")
    lines.append("    }")
    return '\n'.join(lines)

def main():
    # 1. Validate icons against icons.json
    if not os.path.exists(ICONS_JSON_PATH):
        print(f"Error: {ICONS_JSON_PATH} not found.")
        sys.exit(1)

    with open(ICONS_JSON_PATH, "r", encoding="utf-8") as f:
        official_icons = json.load(f)

    distinct_lucide_names = sorted(set(MATERIAL_TO_LUCIDE.values()))
    print(f"Validating {len(distinct_lucide_names)} distinct Lucide icons against official icons.json...")

    for name in distinct_lucide_names:
        if name not in official_icons:
            print(f"Validation ERROR: '{name}' is not in official Lucide icons.json!")
            sys.exit(1)
        svg_file = os.path.join(LUCIDE_SRC_DIR, f"{name}.svg")
        if not os.path.exists(svg_file):
            print(f"Validation ERROR: SVG file '{svg_file}' not found!")
            sys.exit(1)

    print("All Lucide icons successfully validated against official set.")

    # 2. Generate ui/icons/Lucide.kt
    os.makedirs(os.path.dirname(OUTPUT_KT_PATH), exist_ok=True)

    header = """package io.github.ryoaonetsuki.mira.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lucide icon set for Mira (Round 4, Phase 5).
 * Converted from official Lucide SVGs (ISC Licence).
 * Viewport: 24x24, StrokeWidth: 2, Caps/Joins: Round, Stroke: Color.Black.
 */
object Lucide {
"""

    code_blocks = []
    for icon_name in distinct_lucide_names:
        prop_name = to_camel_case(icon_name)
        svg_file = os.path.join(LUCIDE_SRC_DIR, f"{icon_name}.svg")
        code_blocks.append(convert_svg_to_vector_builder(svg_file, icon_name, prop_name))

    footer = "}\n"

    content = header + "\n\n".join(code_blocks) + "\n" + footer

    with open(OUTPUT_KT_PATH, "w", encoding="utf-8") as out:
        out.write(content)

    print(f"Generated {OUTPUT_KT_PATH} with {len(distinct_lucide_names)} icons ({len(content)} bytes).")

if __name__ == "__main__":
    main()
