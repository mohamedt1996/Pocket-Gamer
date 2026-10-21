// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// A Windows command line ("Z:\Games\My Game\game.exe" -windowed) split into the
// arguments it stands for, by the rules Windows programs parse with
// (CommandLineToArgvW). WineActivity hands Wine one argv item per argument:
// Wine quotes each item again when it builds the Windows command line, so the
// game sees the original quoting.

package org.pocketarcade.madeira.session

object CommandLine {
    @JvmStatic
    fun split(line: String?): List<String> {
        val out = ArrayList<String>()
        if (line == null) return out
        var i = 0
        val n = line.length
        while (true) {
            while (i < n && (line[i] == ' ' || line[i] == '\t')) i++
            if (i >= n) return out
            val arg = StringBuilder()
            var quoted = false
            while (i < n) {
                val c = line[i]
                if (!quoted && (c == ' ' || c == '\t')) break
                if (c == '\\') {
                    // Backslashes are literal unless they come before a quote.
                    var slashes = 0
                    while (i < n && line[i] == '\\') { slashes++; i++ }
                    if (i < n && line[i] == '"') {
                        repeat(slashes / 2) { arg.append('\\') }
                        if (slashes % 2 == 1) { arg.append('"'); i++ }
                    } else {
                        repeat(slashes) { arg.append('\\') }
                    }
                    continue
                }
                if (c == '"') {
                    // "" inside a quoted argument is a literal quote.
                    if (quoted && i + 1 < n && line[i + 1] == '"') { arg.append('"'); i += 2; continue }
                    quoted = !quoted; i++; continue
                }
                arg.append(c); i++
            }
            out += arg.toString()
        }
    }
}
