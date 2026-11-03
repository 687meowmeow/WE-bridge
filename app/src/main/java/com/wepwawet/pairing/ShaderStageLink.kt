package com.wepwawet.pairing

/**
 * Cross-stage varying interface harmonization — the link-time counterpart of
 * [ShaderEsslSafety].
 *
 * WHY THIS EXISTS (verified against every source of truth available):
 *
 *  - Wallpaper Engine PC stores its effect shaders in a D3D-flavoured GLSL
 *    where vertex/pixel stages are linked BY REGISTER SEMANTIC, never by
 *    declared type. fxc happily links a vertex that outputs
 *    `varying vec2 v_TexCoord` with a fragment that declares
 *    `varying vec4 v_TexCoord` — the register passes whatever the vertex
 *    wrote and the pixel shader reads whatever it declared.
 *  - wallpaperui.exe's mobile packer (fixShader @0x140015f20 + deprModFix
 *    @0x140017dd0) explicitly PRESERVES `varying` declaration lines
 *    (`^(uniform|attribute|varying|out|in)[\s]+` is in the keep-unchanged
 *    set) — it performs NO type unification of any kind.
 *  - The official Android engine (libscenejni.so) does a plain
 *    glCreateShader/glCompileShader/glCreateProgram/glLinkProgram with only
 *    a `#define varying out` (vertex) / `#define varying in` (fragment)
 *    macro header — no harmonization either.
 *  - Therefore a mismatched pair CANNOT LINK on Android. This is not
 *    theoretical: the shipped `effects/glitter` asset (WE 2.6, 2025-02)
 *    declares `varying vec2 v_TexCoord` in glitter_prepare.vert but
 *    `varying vec4 v_TexCoord` in glitter_prepare.frag, and the official
 *    Android app fails with exactly:
 *
 *        Failed linking program effects/glitter_prepare (Error: output
 *        v_TexCoord type mismatch with other stage. Error: Input Output
 *        Mismatch.)
 *
 *    (user device log 2026-11-01; the same failure is community-confirmed
 *    for real PC→phone transfers — Steam discussion "'Glitter' Effect does
 *    not work on mobile wallpaper", and glslangValidator reproduces the
 *    link error with `Types must match`).
 *
 * THE FIX — fxc-faithful, general (no per-effect patches):
 *
 * For every shader pair (.vert + optional .geom + .frag) in the package,
 * every varying that is declared in a producer stage and its consumer with
 * DIFFERENT float-vector types is unified, preserving D3D semantics:
 *
 *  Path A — the fragment only reads components the vertex actually
 *  declares (including "never reads it at all", the glitter case): the
 *  FRAGMENT declaration is retyped to the vertex's type. Whole-variable
 *  fragment reads keep their expression type by appending the fragment's
 *  old-size swizzle (`.xy` etc.), which is always valid on the larger
 *  declaration and evaluates to exactly the components D3D would have
 *  passed.
 *
 *  Path B — the fragment reads MORE components than the vertex declares
 *  (e.g. vertex vec2 writing .xy, fragment vec4 reading .zw): the VERTEX
 *  declaration is retyped (upsized) to the fragment's type. Component
 *  writes (`v.xy = …`) are already valid; whole-variable vertex writes
 *  `v = <vecN expr>` are zero-extended with the wider constructor
 *  (`vec4(expr, 0.0, 0.0)`); whole-variable vertex reads keep their type
 *  by appending the old-size swizzle. The extra components hold 0.0 — a
 *  deterministic value inside D3D's "undefined register contents"
 *  envelope, so behaviour is never worse than the PC's.
 *
 * CONSERVATIVE BY DESIGN (anything not provably safe is left untouched —
 * the pair is no worse than before, never broken):
 *  - only float/vec2/vec3/vec4 mismatches are rewritten; matrices, arrays,
 *    struct-typed varyings and `gl_`-prefixed names are skipped;
 *  - a name whose type differs across #if branches within one stage
 *    (combo-conditional, e.g. genericparticle's SPRITESHEET v_TexCoord)
 *    is only rewritten when the other stage also has exactly one type and
 *    the sets don't already match per-combo;
 *  - multi-name declarations (`varying vec2 a, b;`) are skipped (retyping
 *    would drag the other names along);
 *  - compound assignments (`v *= …`) on the upsize path are skipped (the
 *    RHS type cannot be proven scalar);
 *  - fragment-side varyings with no vertex declaration are NOT touched:
 *    GLSL ES 3.00 defines those as "undefined value", NOT a link error
 *    (verified with glslangValidator -l) — matching the PC's behaviour.
 *
 * The pass is pure Kotlin (no Android imports) and unit-testable on the
 * JVM. It runs AFTER the per-file chain (deprModFix → fixShader →
 * [ShaderEsslSafety]) in [MpkgPacker.fixShadersForMobile], over the final
 * bytes of every stage, and is idempotent: pairs whose declarations already
 * match are not modified in any way.
 */
object ShaderStageLink {

    // ═════════════════════════════ lexer ═══════════════════════════════

    private enum class TKind { IDENT, NUM, PUNCT, STRING, PREPROC }

    private class Tok(val kind: TKind, val s: String, val start: Int, val end: Int) {
        fun ident(id: String) = kind == TKind.IDENT && s == id
        override fun toString() = "$kind:'$s'@${start}"
    }

    /**
     * Tokenize into a compact stream (whitespace and comments skipped,
     * offsets preserved). Preprocessor lines collapse into a single PREPROC
     * token so identifiers inside `#define` bodies are never mistaken for
     * varying uses.
     */
    private fun lex(src: String): List<Tok> {
        val out = ArrayList<Tok>(src.length / 4)
        var i = 0
        val n = src.length
        outer@ while (i < n) {
            val c = src[i]
            val start = i
            when {
                c == '\n' || c == ' ' || c == '\t' || c == '\r' -> i++   // skipped
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    while (i < n && src[i] != '\n') i++                  // skipped
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    if (i + 1 >= n) return emptyList()      // unterminated
                    i += 2
                }
                c == '#' -> {
                    while (i < n && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < n) i++  // line continuation
                        i++
                    }
                    out.add(Tok(TKind.PREPROC, "", start, i))
                }
                c == '"' -> {
                    i++
                    while (i < n && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    if (i >= n || src[i] != '"') return emptyList()
                    i++
                    out.add(Tok(TKind.STRING, "", start, i))
                }
                c.isLetterOrDigit() || c == '_' -> {
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out.add(Tok(TKind.IDENT, src.substring(start, i), start, i))
                }
                else -> {
                    // multi-char operators used in the analysis
                    val three = if (i + 3 <= n) src.substring(i, i + 3) else ""
                    val two = if (i + 2 <= n) src.substring(i, i + 2) else ""
                    when {
                        three == "<<=" || three == ">>=" || three == "..." -> i += 3
                        two in setOf(
                            "==", "!=", "<=", ">=", "&&", "||", "++", "--",
                            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "->"
                        ) -> i += 2
                        else -> i++
                    }
                    out.add(Tok(TKind.PUNCT, src.substring(start, i), start, i))
                }
            }
        }
        return out
    }

    // ═══════════════════════ declarations ══════════════════════════════

    /** A single top-level interface declaration, e.g. `varying vec4 v_X;`. */
    private class Decl(
        val names: List<String>,      // every name in the declaration
        val type: String,             // declared type text ("vec4", …)
        val typeStart: Int,           // offset of the type token in the source
        val typeEnd: Int,
        val nameStarts: List<Int>,    // offsets of the name tokens
    )

    /** Interface declarations of one stage. */
    private class StageDecls(
        val produced: Map<String, List<Decl>>,   // vertex-role outputs
        val consumed: Map<String, List<Decl>>,   // fragment-role inputs
    )

    private val VEC_TYPES = mapOf("float" to 1, "vec2" to 2, "vec3" to 3, "vec4" to 4)
    private val PRECISION = setOf("lowp", "mediump", "highp")

    /**
     * Whether [kw] at top level introduces an interface declaration for
     * the given stage [role] (see [collectDecls] for the role semantics —
     * notably `in` in a vertex shader is an attribute, `out` in a fragment
     * shader is the render target, and neither is an interface).
     */
    private fun isInterfaceKeyword(kw: String, role: String): Boolean = when (kw) {
        "varying" -> true
        "in" -> role == "frag" || role == "geom"     // fragment input / geom input
        "out" -> role == "vert" || role == "geom"    // vertex output / geom output
        else -> false
    }

    /**
     * Collect the top-level interface declarations of one stage.
     *
     * @param role "vert" (varying/out → produced), "frag" (varying/in →
     *   consumed), or "geom" (in → consumed, out → produced). Direct
     *   `out`/`in` keywords are honoured in vert/frag too — ESSL-style
     *   workshop shaders may use them instead of WE's `varying` — but
     *   strictly per role: `in` in a vertex shader is an ATTRIBUTE and
     *   `out` in a fragment shader is the render target, so those are
     *   never interface declarations and never matched.
     */
    private fun collectDecls(src: String, role: String): StageDecls {
        val toks = lex(src)
        val produced = HashMap<String, MutableList<Decl>>()
        val consumed = HashMap<String, MutableList<Decl>>()

        var brace = 0
        var paren = 0
        var k = 0
        while (k < toks.size) {
            val t = toks[k]
            when {
                t.kind == TKind.PUNCT && t.s == "{" -> brace++
                t.kind == TKind.PUNCT && t.s == "}" -> brace--
                t.kind == TKind.PUNCT && t.s == "(" -> paren++
                t.kind == TKind.PUNCT && t.s == ")" -> paren--
                t.kind == TKind.IDENT && brace == 0 && paren == 0 &&
                    isInterfaceKeyword(t.s, role) -> {
                    // candidate declaration: [precision] TYPE name (, name)* ;
                    var j = k + 1
                    var typeTok: Tok? = null
                    while (j < toks.size) {
                        val u = toks[j]
                        if (u.kind == TKind.IDENT && u.s in PRECISION) { j++; continue }
                        if (u.kind == TKind.IDENT) { typeTok = u; j++ }
                        break
                    }
                    if (typeTok != null && j < toks.size && toks[j].kind == TKind.IDENT) {
                        // gather names until ';' — bail on anything unexpected
                        val names = ArrayList<String>()
                        val nameStarts = ArrayList<Int>()
                        var ok = true
                        var m = j
                        while (m < toks.size) {
                            val u = toks[m]
                            if (u.kind == TKind.IDENT) {
                                names.add(u.s); nameStarts.add(u.start); m++
                            } else if (u.kind == TKind.PUNCT && u.s == ",") {
                                m++
                            } else if (u.kind == TKind.PUNCT && u.s == ";") {
                                break
                            } else if (u.kind == TKind.PUNCT && u.s == "[") {
                                ok = false; break       // array — not supported
                            } else {
                                ok = false; break
                            }
                        }
                        if (ok && names.isNotEmpty()) {
                            val decl = Decl(names, typeTok.s, typeTok.start, typeTok.end, nameStarts)
                            val target =
                                if (role == "frag" || (role == "geom" && toks[k].s == "in"))
                                    consumed else produced
                            for (nm in names) {
                                target.getOrPut(nm) { ArrayList() }.add(decl)
                            }
                        }
                    }
                    k = j
                    continue
                }
            }
            k++
        }
        return StageDecls(produced, consumed)
    }

    // ═══════════════════════ usage analysis ════════════════════════════

    private class Use(
        val identStart: Int, val identEnd: Int,
        val needs: Int,               // components required by this use
        val isWholeVar: Boolean,      // bare identifier (no swizzle/index)
        val nextPunct: String?,       // punct token right after ident (if any)
        val nextPunctEnd: Int,        // end offset of that punct token (0 if none)
    )

    private val SWIZZLE = "xyzwrgbastpq"

    private val TYPE_KEYWORDS = setOf(
        "float", "vec2", "vec3", "vec4", "int", "uint", "bool",
        "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
        "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4",
        "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3", "mat3x4",
        "mat4x2", "mat4x3", "mat4x4", "sampler2D", "sampler3D",
        "samplerCube", "sampler2DShadow",
    )

    /**
     * True when the name is UNSAFE to rewrite in this source because its
     * lexical uses could belong to something other than the varying itself:
     *  - it is shadowed by a local variable or function parameter
     *    (`vec2 name = …`, `void f(vec2 name)`),
     *  - it is referenced inside a preprocessor line (a #define body would
     *    expand uses that token-level analysis cannot see).
     *
     * [declNameStarts] are the offsets of the varying's own declaration name
     * tokens (excluded — `varying vec2 name;` legitimately has the type
     * keyword immediately before the name).
     */
    private fun nameIsUnsafe(src: String, name: String, declNameStarts: Set<Int>): Boolean {
        // preprocessor references
        var i = 0
        val n = src.length
        while (i < n) {
            // start of a line
            var j = i
            while (j < n && (src[j] == ' ' || src[j] == '\t')) j++
            if (j < n && src[j] == '#') {
                // scan the (possibly continued) preprocessor line(s)
                var e = j
                while (e < n) {
                    if (src[e] == '\n') {
                        // line continuation?
                        var bs = e - 1
                        var cont = false
                        while (bs >= j && (src[bs] == '\r' || src[bs] == ' ' || src[bs] == '\t')) bs--
                        if (bs >= j && src[bs] == '\\') cont = true
                        if (!cont) break
                    }
                    e++
                }
                if (containsWord(src, j, e.coerceAtMost(n), name)) return true
                i = e + 1
                continue
            }
            // advance to next line
            while (i < n && src[i] != '\n') i++
            i++
        }
        // shadowing: `<type> name` with a type keyword immediately before,
        // outside the varying's own declarations
        val toks = lex(src)
        for (k in toks.indices) {
            val t = toks[k]
            if (t.kind == TKind.IDENT && t.s == name && t.start !in declNameStarts && k > 0) {
                val prev = toks[k - 1]
                if (prev.kind == TKind.IDENT && prev.s in TYPE_KEYWORDS) return true
            }
        }
        return false
    }

    private fun containsWord(src: String, from: Int, to: Int, word: String): Boolean {
        var i = from
        val n = to
        while (i < n) {
            if (src[i] == word[0]) {
                var j = i + 1
                var w = 1
                while (j < n && w < word.length && src[j] == word[w]) { j++; w++ }
                if (w == word.length) {
                    val beforeOk = i == from || !(src[i - 1].isLetterOrDigit() || src[i - 1] == '_')
                    val afterOk = j >= n || !(src[j].isLetterOrDigit() || src[j] == '_')
                    if (beforeOk && afterOk) return true
                }
            }
            i++
        }
        return false
    }

    /**
     * Every use of [name] outside its declarations. For each use the number
     * of leading components the expression requires is computed:
     *  - `v.xyz`        → 3 (max swizzle component + 1)
     *  - `v[2]`         → 3 (literal index + 1; dynamic index → declared size)
     *  - bare `v`       → [declaredSize] (whole-variable use)
     */
    private fun uses(src: String, name: String, declNameStarts: Set<Int>, declaredSize: Int): List<Use> {
        val toks = lex(src)
        val out = ArrayList<Use>()
        var k = 0
        while (k < toks.size) {
            val t = toks[k]
            if (t.kind == TKind.IDENT && t.s == name && t.start !in declNameStarts) {
                // skip a following identifier chain? A varying use is never
                // followed by another identifier except swizzles after '.'
                var needs = declaredSize
                var whole = true
                var nextPunct: String? = null
                var nextPunctEnd = 0
                if (k + 1 < toks.size) {
                    val u = toks[k + 1]
                    if (u.kind == TKind.PUNCT && u.s == "." && k + 2 < toks.size) {
                        val sw = toks[k + 2]
                        if (sw.kind == TKind.IDENT && sw.s.length in 1..4 &&
                            sw.s.all { it in SWIZZLE }
                        ) {
                            needs = 0
                            for (ch in sw.s) {
                                // v0.7.24 (C-S1 BUG FIX): the old table
                                // ("xrgs"->0, "ygbt"->1) matched top-down, so
                                // 'g' hit the FIRST branch (returning 0 instead
                                // of 1) and 'b' hit the SECOND (returning 1
                                // instead of 2). GLSL swizzle letters:
                                //   x=r=s -> 0;  y=g=t -> 1;  z=b=p -> 2;  w=a=q -> 3
                                val idx = when (ch) {
                                    in "xrs" -> 0
                                    in "ygt" -> 1
                                    in "zbp" -> 2
                                    else -> 3
                                }
                                if (idx + 1 > needs) needs = idx + 1
                            }
                            whole = false
                        } else {
                            needs = declaredSize   // e.g. v.length() — conservative
                            whole = false
                        }
                    } else if (u.kind == TKind.PUNCT && u.s == "[") {
                        whole = false
                        if (k + 2 < toks.size && toks[k + 2].kind == TKind.NUM) {
                            val idx = toks[k + 2].s.toIntOrNull()
                            needs = if (idx != null) idx + 1 else declaredSize
                        } else {
                            needs = declaredSize   // dynamic index
                        }
                    } else if (u.kind == TKind.PUNCT) {
                        nextPunct = u.s
                        nextPunctEnd = u.end
                    }
                }
                out.add(Use(t.start, t.end, needs, whole, nextPunct, nextPunctEnd))
            }
            k++
        }
        return out
    }

    // ═══════════════════════ harmonization ═════════════════════════════

    /** One staged rewrite: replace [start,end) with [text]. */
    private class Edit(val start: Int, val end: Int, val text: String)

    private fun sizeOf(ty: String): Int? = VEC_TYPES[ty]

    private fun swizzleOf(size: Int): String = when (size) {
        1 -> ".x"; 2 -> ".xy"; 3 -> ".xyz"; else -> ".xyzw"
    }

    /** Zero-fill suffix for `vecN(expr, …)` so the constructor takes exactly
     *  [targetSize] components when [expr] already supplies [exprSize]. */
    private fun ctorArgs(targetSize: Int, exprSize: Int): String =
        ", 0.0".repeat((targetSize - exprSize).coerceAtLeast(0))

    /**
     * Harmonize one producer/consumer interface pair.
     *
     * @param producerSrc source of the producing stage (vertex, or geom's out face)
     * @param consumerSrc source of the consuming stage (fragment, or geom's in face)
     * @param producerRole "vert" or "geom"
     * @param consumerRole "frag" or "geom"
     * @return (newProducer, newConsumer, report) or null when nothing changed
     */
    fun harmonizeInterface(
        producerSrc: String,
        consumerSrc: String,
        producerRole: String,
        consumerRole: String,
    ): Triple<String, String, List<String>>? {
        val p = collectDecls(producerSrc, producerRole)
        val c = collectDecls(consumerSrc, consumerRole)
        val producerEdits = ArrayList<Edit>()
        val consumerEdits = ArrayList<Edit>()
        val report = ArrayList<String>()

        for ((name, pDecls) in p.produced) {
            val cDecls = c.consumed[name] ?: continue
            // skip gl_ builtins and multi-declaration (combo) names
            if (name.startsWith("gl_")) continue
            if (pDecls.size != 1 || cDecls.size != 1) continue
            val pd = pDecls[0]
            val cd = cDecls[0]
            // multi-name declarations cannot be retyped safely
            if (pd.names.size > 1 || cd.names.size > 1) continue
            val vs = sizeOf(pd.type) ?: continue
            val fs = sizeOf(cd.type) ?: continue
            if (vs == fs) continue

            // shadowed names / preprocessor-referenced names are never rewritten
            if (nameIsUnsafe(producerSrc, name, setOf(pd.nameStarts[0])) ||
                nameIsUnsafe(consumerSrc, name, setOf(cd.nameStarts[0]))
            ) {
                report.add("$name: SKIPPED (shadowed or preprocessor-referenced)")
                continue
            }

            // fragment-side usage of the varying
            val fragUses = uses(consumerSrc, name, setOf(cd.nameStarts[0]), fs)
            val fragNeeds = fragUses.maxOfOrNull { it.needs } ?: 0

            if (fragNeeds <= vs) {
                // Path A — retype the FRAGMENT declaration to the vertex type.
                consumerEdits.add(Edit(cd.typeStart, cd.typeEnd, pd.type))
                // whole-variable fragment reads keep the old expression type
                for (u in fragUses) {
                    if (u.isWholeVar) {
                        consumerEdits.add(Edit(u.identEnd, u.identEnd, swizzleOf(fs)))
                        // swizzle-appended use — its needs were fs; still valid
                    }
                }
                report.add(
                    "$name: fragment ${cd.type}→${pd.type} " +
                        "(fragment needs $fragNeeds ≤ vertex size $vs; D3D passes the vertex register unchanged)"
                )
            } else {
                // Path B — retype the VERTEX declaration to the fragment type
                // (only ever an upsize: fragNeeds > vs and fragNeeds ≤ fs).
                val target = cd.type
                val vertUses = uses(producerSrc, name, setOf(pd.nameStarts[0]), vs)
                // compound assignments on the upsize path cannot be proven safe
                val hasCompound = vertUses.any {
                    it.nextPunct in setOf("*=", "+=", "-=", "/=", "%=")
                }
                if (hasCompound) {
                    report.add("$name: SKIPPED (compound assignment on upsize path)")
                    continue
                }
                // analyze ALL edits first; commit only if every one is provable
                val nameEdits = ArrayList<Edit>()
                nameEdits.add(Edit(pd.typeStart, pd.typeEnd, target))
                var analyzable = true
                for (u in vertUses) {
                    if (u.isWholeVar && u.nextPunct == "=") {
                        // whole-variable write `v = <old-type expr>;`
                        // → `v = <target>(<expr>, 0.0, …);`
                        // the constructor opens AFTER the '=' token
                        val rhsStart = u.nextPunctEnd
                        // find the ';' after '=' at the same nesting level
                        val toks = lex(producerSrc)
                        var end = -1
                        var depth = 0
                        for (t in toks) {
                            if (t.start < rhsStart) continue
                            if (t.kind == TKind.PUNCT) {
                                when (t.s) {
                                    "(", "[" -> depth++
                                    ")", "]" -> depth--
                                    ";" -> if (depth == 0) { end = t.start; break }
                                    "{", "}" -> { end = t.start; break }
                                }
                            }
                        }
                        if (end > rhsStart) {
                            // consume the whitespace between '=' and the RHS
                            // so the rewrite reads `v = vec4(expr, …)` cleanly
                            var rhsText = rhsStart
                            while (rhsText < end && (producerSrc[rhsText] == ' ' ||
                                    producerSrc[rhsText] == '\t')) rhsText++
                            nameEdits.add(Edit(rhsStart, rhsText, " $target("))
                            nameEdits.add(Edit(end, end, ctorArgs(fs, vs) + ")"))
                        } else {
                            analyzable = false
                            break
                        }
                    } else if (u.isWholeVar) {
                        // whole-variable read keeps its type via the old swizzle
                        nameEdits.add(Edit(u.identEnd, u.identEnd, swizzleOf(vs)))
                    }
                }
                if (!analyzable) {
                    report.add("$name: SKIPPED (write statement not analyzable)")
                    continue
                }
                producerEdits.addAll(nameEdits)
                report.add(
                    "$name: vertex ${pd.type}→$target " +
                        "(fragment needs $fragNeeds > vertex size $vs; extra components zero-filled)"
                )
            }
        }

        if (producerEdits.isEmpty() && consumerEdits.isEmpty()) return null
        val newP = applyEdits(producerSrc, producerEdits)
        val newC = applyEdits(consumerSrc, consumerEdits)
        return Triple(newP, newC, report)
    }

    private fun applyEdits(src: String, edits: List<Edit>): String {
        if (edits.isEmpty()) return src
        val sb = StringBuilder(src.length + 64)
        var pos = 0
        for (e in edits.sortedBy { it.start }.distinctBy { it.start to it.end }) {
            if (e.start < pos) continue          // overlapping — shouldn't happen
            sb.append(src, pos, e.start)
            sb.append(e.text)
            pos = e.end
        }
        sb.append(src, pos, src.length)
        return sb.toString()
    }

    // ═══════════════════════ entry point ═══════════════════════════════

    /**
     * Harmonize every shader stage pair (.vert + .frag) found in [entries].
     * Entry names are VFS paths such as `shaders/effects/glitter_prepare.vert`.
     *
     * Stems that also carry a .geom (engine-core particle shaders — they never
     * appear in wallpaper .mpkgs, and their bodies reach varyings through the
     * engine's IN. and v. structs which defeat name-based usage analysis) are
     * skipped entirely.
     *
     * @return a human-readable report line per rewrite (empty when nothing
     *   changed — the common case, so unbroken wallpapers are byte-identical).
     */
    fun harmonize(entries: MutableList<Pair<String, ByteArray>>): List<String> {
        val report = ArrayList<String>()
        // group stages by path stem
        // v0.7.24 (M-S9): LinkedHashMap for DETERMINISTIC iteration order —
        // a plain HashMap made the rewrite (and its report) depend on hash
        // seeding, so the same wallpaper could pack differently per run.
        val byStem = LinkedHashMap<String, LinkedHashMap<String, Int>>()  // stem -> ext -> index
        for ((i, e) in entries.withIndex()) {
            val lower = e.first.lowercase()
            if (!lower.endsWith(".vert") && !lower.endsWith(".frag") && !lower.endsWith(".geom")) continue
            val ext = e.first.substringAfterLast('.').lowercase()
            val stem = e.first.substringBeforeLast('.')
            byStem.getOrPut(stem) { LinkedHashMap() }[ext] = i
        }
        for ((stem, stages) in byStem) {
            val vertIdx = stages["vert"] ?: continue
            val fragIdx = stages["frag"] ?: continue
            if (stages.containsKey("geom")) continue   // particle/engine-core — never packed

            val vertSrc = String(entries[vertIdx].second, Charsets.UTF_8)
            val fragSrc = String(entries[fragIdx].second, Charsets.UTF_8)

            harmonizeInterface(vertSrc, fragSrc, "vert", "frag")?.let { (np, nc, rep) ->
                entries[vertIdx] = entries[vertIdx].first to np.toByteArray(Charsets.UTF_8)
                entries[fragIdx] = entries[fragIdx].first to nc.toByteArray(Charsets.UTF_8)
                report.addAll(rep.map { "$stem: $it" })
            }
        }
        return report
    }
}
