package app.gitex

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/**
 * Language table + lexical highlighter.
 *
 * This is a fast single-pass lexer (keywords, types, strings, numbers, comments, calls),
 * not a full grammar parser. Unknown file types fall back to plain text. The lexer is
 * independent of the editor widget: it reports (line, column, token) transitions and
 * CodeEditor.kt adapts them to the editor.
 */
class LangDef(
    val id: String,
    val label: String,
    val exts: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    keywords: String = "",
    types: String = "",
    val line: List<String> = emptyList(),
    val blockStart: String? = null,
    val blockEnd: String? = null,
    val quotes: String = "\"'",
    val backtick: Boolean = false,
    val triple: Boolean = false,
    val ci: Boolean = false,
    val typeByCase: Boolean = false,
    val markup: Boolean = false,
    val keyColon: Boolean = false,
    val jsonKeys: Boolean = false,
    val markdown: Boolean = false,
    val atWords: Boolean = false,
    val dashIdent: Boolean = false,
    val hashDirective: Boolean = false,
    val colonIndent: Boolean = false,
    val iconKey: String = id,
) {
    val kw: HashSet<String> = HashSet(keywords.split(' ').filter { it.isNotEmpty() })
    val ty: HashSet<String> = HashSet(types.split(' ').filter { it.isNotEmpty() })

    /** Prefix used by "comment / uncomment lines" (null when the language has none). */
    val commentPrefix: String? get() = line.firstOrNull()
}

private const val JS_KW = "async await break case catch class const continue debugger default delete do else export extends false finally for from function if import in instanceof let new null of return static super switch this throw true try typeof undefined var void while with yield get set"
private const val TS_KW = "$JS_KW abstract as declare enum implements interface keyof namespace private protected public readonly type unknown never any is module override satisfies"
private const val JS_TY = "string number boolean object symbol bigint Array Promise Map Set Record"

object Languages {
    val text = LangDef("text", "Plain text", exts = listOf("txt"), iconKey = "text")

    val all: List<LangDef> = listOf(
        LangDef("python", "Python", exts = listOf("py", "pyw", "pyi"),
            keywords = "False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case self cls",
            types = "int str float bool list dict set tuple bytes object print len range",
            line = listOf("#"), triple = true, typeByCase = true, atWords = true, colonIndent = true),
        LangDef("kotlin", "Kotlin", exts = listOf("kt", "kts"),
            keywords = "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while by catch constructor finally get import init set where abstract annotation companion const crossinline data enum external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg",
            types = "Int Long Short Byte Float Double Boolean Char String Unit Any Nothing Array List Map Set",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", triple = true, typeByCase = true, atWords = true),
        LangDef("typescript", "TypeScript", exts = listOf("ts", "mts", "cts"), keywords = TS_KW, types = JS_TY,
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", backtick = true, typeByCase = true, atWords = true),
        LangDef("javascript", "JavaScript", exts = listOf("js", "mjs", "cjs", "jsx"), keywords = JS_KW, types = JS_TY,
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", backtick = true, typeByCase = true),
        LangDef("toml", "TOML", exts = listOf("toml"), keywords = "true false", line = listOf("#"), triple = true, keyColon = true, dashIdent = true),
        LangDef("gdscript", "GDScript", exts = listOf("gd"),
            keywords = "and as assert await break breakpoint class class_name const continue elif else enum extends false for func if in is match not null or pass preload return self signal static super true var while yield void",
            types = "int float bool String Vector2 Vector3 Array Dictionary Node",
            line = listOf("#"), triple = true, typeByCase = true, atWords = true, colonIndent = true),
        LangDef("cpp", "C++", exts = listOf("cpp", "cc", "cxx", "hpp", "hh", "hxx", "h"),
            keywords = "alignas alignof and asm auto bool break case catch char class const constexpr const_cast continue decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace new noexcept nullptr operator or private protected public register reinterpret_cast return short signed sizeof static static_assert static_cast struct switch template this throw true try typedef typeid typename union unsigned using virtual void volatile while override final",
            types = "size_t string vector map set unique_ptr shared_ptr int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", typeByCase = true, hashDirective = true),
        LangDef("swift", "Swift", exts = listOf("swift"),
            keywords = "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private precedencegroup protocol public rethrows static struct subscript typealias var break case catch continue default defer do else fallthrough for guard if in repeat return throw switch where while as false is nil self super throws true try async await actor some any override final lazy weak mutating",
            types = "Int Double Float Bool String Character Array Dictionary Set Optional Any AnyObject",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", triple = true, typeByCase = true, atWords = true),
        LangDef("java", "Java", exts = listOf("java"),
            keywords = "abstract assert break case catch class const continue default do else enum extends final finally for goto if implements import instanceof interface native new package private protected public return static strictfp super switch synchronized this throw throws transient try volatile while true false null var record sealed permits yield",
            types = "int long short byte float double boolean char void String Object Integer List Map Set",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", triple = true, typeByCase = true, atWords = true),
        LangDef("ruby", "Ruby", exts = listOf("rb", "rake", "gemspec"), names = listOf("gemfile", "rakefile"),
            keywords = "BEGIN END alias and begin break case class def defined? do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield puts require require_relative attr_accessor attr_reader attr_writer",
            line = listOf("#"), typeByCase = true),
        LangDef("php", "PHP", exts = listOf("php", "phtml"),
            keywords = "abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield true false null",
            types = "int float bool string array object mixed void self parent",
            line = listOf("//", "#"), blockStart = "/*", blockEnd = "*/", typeByCase = true),
        LangDef("rust", "Rust", exts = listOf("rs"),
            keywords = "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while macro_rules",
            types = "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", quotes = "\"", typeByCase = true),
        LangDef("tsx", "TSX", exts = listOf("tsx"), keywords = TS_KW, types = JS_TY,
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", backtick = true, typeByCase = true, atWords = true),
        LangDef("yaml", "YAML", exts = listOf("yml", "yaml"), keywords = "true false null yes no on off",
            line = listOf("#"), keyColon = true, dashIdent = true),
        LangDef("xml", "XML", exts = listOf("xml", "xsd", "xsl", "svg", "plist", "csproj"), markup = true, blockStart = "<!--", blockEnd = "-->"),
        LangDef("qt", "Qt Project", exts = listOf("pro", "pri"),
            keywords = "TEMPLATE TARGET QT CONFIG SOURCES HEADERS FORMS RESOURCES INCLUDEPATH LIBS DEFINES DESTDIR OBJECTS_DIR MOC_DIR UI_DIR RCC_DIR VERSION win32 unix macx android",
            line = listOf("#"), keyColon = true),
        LangDef("gitignore", "Git ignore", names = listOf(".gitignore", ".gitattributes", ".dockerignore"), line = listOf("#"), quotes = ""),
        LangDef("csharp", "C#", exts = listOf("cs"),
            keywords = "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long namespace new null object operator out override params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using var virtual void volatile while async await record init get set yield",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", typeByCase = true, hashDirective = true),
        LangDef("go", "Go", exts = listOf("go"),
            keywords = "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil iota",
            types = "int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 float32 float64 string bool byte rune error any",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", backtick = true, quotes = "\"'", typeByCase = true),
        LangDef("dart", "Dart", exts = listOf("dart"),
            keywords = "abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory false final finally for get hide if implements import in interface is late library mixin new null on operator part required rethrow return set show static super switch sync this throw true try typedef var void while with yield",
            types = "int double num bool String List Map Set Future Stream Object",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", triple = true, typeByCase = true, atWords = true),
        LangDef("sql", "SQL", exts = listOf("sql"), ci = true,
            keywords = "select from where insert into values update set delete create table alter drop index view join inner left right outer full cross on group by order having limit offset union all distinct as and or not null is in like between exists case when then else end primary key foreign references default unique check constraint begin commit rollback transaction add column if with returning asc desc",
            types = "int integer bigint smallint varchar char text date time timestamp boolean decimal numeric float double blob serial",
            line = listOf("--"), blockStart = "/*", blockEnd = "*/", backtick = true),
        LangDef("html", "HTML", exts = listOf("html", "htm", "xhtml"), markup = true, blockStart = "<!--", blockEnd = "-->"),
        LangDef("css", "CSS", exts = listOf("css", "scss", "less"),
            keywords = "important inherit initial unset none auto",
            blockStart = "/*", blockEnd = "*/", line = listOf("//"), keyColon = true, dashIdent = true, atWords = true),
        LangDef("json", "JSON", exts = listOf("json", "jsonc", "json5", "geojson"), keywords = "true false null", quotes = "\"", jsonKeys = true),
        LangDef("bash", "Shell", exts = listOf("sh", "bash", "zsh"), names = listOf(".bashrc", ".zshrc", ".profile"),
            keywords = "if then else elif fi for while until do done case esac in function select time return exit break continue local export readonly declare unset source alias echo cd set shift trap eval exec true false",
            line = listOf("#"), backtick = true),
        LangDef("lua", "Lua", exts = listOf("lua"),
            keywords = "and break do else elseif end false for function goto if in local nil not or repeat return then true until while self",
            line = listOf("--"), blockStart = "--[[", blockEnd = "]]"),
        LangDef("powershell", "PowerShell", exts = listOf("ps1", "psm1", "psd1"), ci = true,
            keywords = "begin break catch class continue data do dynamicparam else elseif end exit filter finally for foreach from function if in param process return switch throw trap try until using var while workflow",
            line = listOf("#"), blockStart = "<#", blockEnd = "#>"),
        LangDef("docker", "Dockerfile", names = listOf("dockerfile", "containerfile"), ci = true,
            keywords = "from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as",
            line = listOf("#")),
        LangDef("makefile", "Makefile", exts = listOf("mk"), names = listOf("makefile", "gnumakefile"),
            keywords = "ifeq ifneq ifdef ifndef else endif include define endef export override vpath .PHONY",
            line = listOf("#")),
        LangDef("r", "R", exts = listOf("r", "rmd"),
            keywords = "if else repeat while function for in next break TRUE FALSE NULL Inf NaN NA library require return",
            line = listOf("#")),
        LangDef("julia", "Julia", exts = listOf("jl"),
            keywords = "abstract baremodule begin break catch const continue do else elseif end export false finally for function global if import in let local macro module mutable primitive quote return struct true try type using where while",
            types = "Int Int64 Float64 Bool String Char Vector Matrix Array Dict Any Nothing",
            line = listOf("#"), blockStart = "#=", blockEnd = "=#", triple = true, typeByCase = true),
        LangDef("matlab", "MATLAB", exts = listOf("m", "mlx"),
            keywords = "break case catch classdef continue else elseif end for function global if otherwise parfor persistent return switch try while true false",
            line = listOf("%"), blockStart = "%{", blockEnd = "%}"),
        LangDef("scala", "Scala", exts = listOf("scala", "sc", "sbt"),
            keywords = "abstract case catch class def do else extends false final finally for forSome if implicit import lazy match new null object override package private protected return sealed super this throw trait true try type val var while with yield given using enum then",
            types = "Int Long Double Float Boolean Char String Unit Any Nothing List Map Set Option Seq",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", triple = true, typeByCase = true, atWords = true),
        LangDef("c", "C", exts = listOf("c"),
            keywords = "auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while NULL true false",
            types = "size_t uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t FILE bool",
            line = listOf("//"), blockStart = "/*", blockEnd = "*/", hashDirective = true),
        LangDef("graphql", "GraphQL", exts = listOf("graphql", "gql"),
            keywords = "query mutation subscription fragment on type interface union enum input scalar extend schema directive implements true false null",
            types = "Int Float String Boolean ID", line = listOf("#"), typeByCase = true, atWords = true),
        LangDef("haskell", "Haskell", exts = listOf("hs", "lhs"),
            keywords = "case class data default deriving do else foreign if import in infix infixl infixr instance let module newtype of then type where forall qualified as hiding",
            types = "Int Integer Float Double Bool Char String Maybe Either IO",
            line = listOf("--"), blockStart = "{-", blockEnd = "-}", quotes = "\"", typeByCase = true),
        LangDef("elixir", "Elixir", exts = listOf("ex", "exs"),
            keywords = "after alias and case catch cond def defp defmodule defmacro defstruct defprotocol defimpl do else end false fn for if import in nil not or quote raise receive require rescue true try unless unquote use when with",
            line = listOf("#"), triple = true, typeByCase = true, atWords = true),
        LangDef("erlang", "Erlang", exts = listOf("erl", "hrl"),
            keywords = "after and andalso band begin bnot bor bsl bsr bxor case catch cond div end fun if let not of or orelse receive rem try when xor",
            line = listOf("%"), quotes = "\"", typeByCase = true),
        LangDef("perl", "Perl", exts = listOf("pl", "pm", "t"),
            keywords = "and cmp continue do else elsif eq exists for foreach ge gt if last le local lt my ne next no not or our package redo return sub tie unless until use while xor print push pop shift unshift keys values defined undef ref die",
            line = listOf("#")),
        LangDef("assembly", "Assembly", exts = listOf("asm", "s", "nasm"), ci = true,
            keywords = "mov push pop add sub mul div inc dec and or xor not shl shr cmp jmp je jne jg jl jge jle call ret int nop lea test loop section global extern db dw dd dq resb resw resd resq equ times syscall eax ebx ecx edx esi edi esp ebp rax rbx rcx rdx rsi rdi rsp rbp ldr str bl bx b",
            line = listOf(";", "//", "@"), blockStart = "/*", blockEnd = "*/"),
        LangDef("zig", "Zig", exts = listOf("zig"),
            keywords = "addrspace align allowzero and anyframe anytype asm async await break callconv catch comptime const continue defer else enum errdefer error export extern fn for if inline linksection noalias nosuspend opaque or orelse packed pub resume return struct suspend switch test threadlocal try union unreachable usingnamespace var volatile while true false null undefined",
            types = "u8 u16 u32 u64 usize i8 i16 i32 i64 isize f32 f64 bool void type anyerror",
            line = listOf("//"), quotes = "\"", typeByCase = true, atWords = true),
        LangDef("nix", "Nix", exts = listOf("nix"),
            keywords = "if then else let in with rec inherit assert import true false null or",
            line = listOf("#"), blockStart = "/*", blockEnd = "*/"),
        LangDef("terraform", "Terraform", exts = listOf("tf", "tfvars", "hcl"),
            keywords = "resource variable output module provider data locals terraform required_providers backend for_each count depends_on lifecycle true false null",
            line = listOf("#", "//"), blockStart = "/*", blockEnd = "*/", quotes = "\""),
        LangDef("vue", "Vue", exts = listOf("vue"), markup = true, blockStart = "<!--", blockEnd = "-->"),
        LangDef("svelte", "Svelte", exts = listOf("svelte"), markup = true, blockStart = "<!--", blockEnd = "-->"),
        LangDef("clojure", "Clojure", exts = listOf("clj", "cljs", "cljc", "edn"),
            keywords = "def defn defmacro defmulti defmethod defprotocol defrecord deftype do fn if let loop recur quote ns require import use when when-not cond case for doseq nil true false",
            line = listOf(";"), quotes = "\""),
        LangDef("markdown", "Markdown", exts = listOf("md", "markdown", "mdx"), markdown = true, quotes = ""),
        text,
    )

    private val byExt = HashMap<String, LangDef>()
    private val byName = HashMap<String, LangDef>()

    init {
        for (l in all) {
            for (e in l.exts) byExt[e.lowercase()] = l
            for (n in l.names) byName[n.lowercase()] = l
        }
    }

    fun byId(id: String): LangDef? = all.firstOrNull { it.id == id }

    /** Language for a file name, or null for unknown types (plain text, no highlighting). */
    fun forName(fileName: String): LangDef? {
        val n = fileName.lowercase()
        byName[n]?.let { return it }
        val dot = n.lastIndexOf('.')
        if (dot >= 0 && dot < n.length - 1) return byExt[n.substring(dot + 1)]
        return null
    }
}

// ---------------------------------------------------------------------------
// Syntax colour schemes (the "Syntax Highlight Theme" setting)
// ---------------------------------------------------------------------------

object Tok {
    const val PLAIN = 0
    const val KEYWORD = 1
    const val TYPE = 2
    const val STRING = 3
    const val NUMBER = 4
    const val COMMENT = 5
    const val FUNC = 6
    const val COUNT = 7
}

class SyntaxScheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    private val kw: Long,
    private val type: Long,
    private val str: Long,
    private val num: Long,
    private val cmt: Long,
    private val func: Long,
) {
    /**
     * ARGB colour per token kind. [bg] and [fg] come from the app theme: any scheme colour
     * that would be hard to read on [bg] is blended toward [fg], so a dark scheme stays
     * legible on a light app theme and vice versa.
     */
    fun colors(bg: Color, fg: Color): IntArray {
        val out = IntArray(Tok.COUNT)
        fun fix(v: Long): Int {
            var c = Color(v)
            if (abs(c.luminance() - bg.luminance()) < 0.28f) c = lerp(c, fg, 0.65f)
            return c.toArgb()
        }
        out[Tok.PLAIN] = fg.toArgb()
        out[Tok.KEYWORD] = fix(kw)
        out[Tok.TYPE] = fix(type)
        out[Tok.STRING] = fix(str)
        out[Tok.NUMBER] = fix(num)
        out[Tok.COMMENT] = fix(cmt)
        out[Tok.FUNC] = fix(func)
        return out
    }
}

object Syntax {
    val schemes = listOf(
        SyntaxScheme("dark_plus", "Dark+", true, 0xFF569CD6, 0xFF4EC9B0, 0xFFCE9178, 0xFFB5CEA8, 0xFF6A9955, 0xFFDCDCAA),
        SyntaxScheme("one_dark", "One Dark", true, 0xFFC678DD, 0xFFE5C07B, 0xFF98C379, 0xFFD19A66, 0xFF5C6370, 0xFF61AFEF),
        SyntaxScheme("dracula", "Dracula", true, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF1FA8C, 0xFFBD93F9, 0xFF6272A4, 0xFF50FA7B),
        SyntaxScheme("monokai", "Monokai", true, 0xFFF92672, 0xFF66D9EF, 0xFFE6DB74, 0xFFAE81FF, 0xFF75715E, 0xFFA6E22E),
        SyntaxScheme("nord", "Nord", true, 0xFF81A1C1, 0xFF8FBCBB, 0xFFA3BE8C, 0xFFB48EAD, 0xFF616E88, 0xFF88C0D0),
        SyntaxScheme("github_dark", "GitHub Dark", true, 0xFFFF7B72, 0xFFFFA657, 0xFFA5D6FF, 0xFF79C0FF, 0xFF8B949E, 0xFFD2A8FF),
        SyntaxScheme("solarized_dark", "Solarized Dark", true, 0xFF859900, 0xFFB58900, 0xFF2AA198, 0xFFD33682, 0xFF586E75, 0xFF268BD2),
        SyntaxScheme("light_plus", "Light+", false, 0xFF0000FF, 0xFF267F99, 0xFFA31515, 0xFF098658, 0xFF008000, 0xFF795E26),
        SyntaxScheme("github_light", "GitHub Light", false, 0xFFCF222E, 0xFF953800, 0xFF0A3069, 0xFF0550AE, 0xFF6E7781, 0xFF8250DF),
        SyntaxScheme("solarized_light", "Solarized Light", false, 0xFF859900, 0xFFB58900, 0xFF2AA198, 0xFFD33682, 0xFF93A1A1, 0xFF268BD2),
    )

    fun byId(id: String): SyntaxScheme = schemes.firstOrNull { it.id == id } ?: schemes[0]
}

// ---------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------

object Lexer {
    /**
     * Scans [text] and calls [emit] (line, column, token kind) whenever the token kind changes;
     * every line gets one entry at column 0. [cancelled] is polled so a stale pass stops early.
     */
    fun run(text: CharSequence, lang: LangDef, cancelled: () -> Boolean, emit: (Int, Int, Int) -> Unit) {
        LexRun(text, lang, cancelled, emit).run()
    }
}

private class LexRun(
    private val t: CharSequence,
    private val lang: LangDef,
    private val cancelled: () -> Boolean,
    private val emit: (Int, Int, Int) -> Unit,
) {
    private val n = t.length
    private var i = 0
    private var line = 0
    private var col = 0
    private var last = -1
    private var state = 0 // 0 normal, 1 block comment, 2 multi-line string
    private var strEnd = ""
    private var inTag = false
    private var lineBlank = true

    private fun put(kind: Int) {
        if (kind != last) {
            emit(line, col, kind)
            last = kind
        }
    }

    private fun adv(k: Int) {
        i += k
        col += k
    }

    private fun starts(s: String): Boolean {
        if (i + s.length > n) return false
        for (k in s.indices) if (t[i + k] != s[k]) return false
        return true
    }

    private fun toEol() {
        while (i < n && t[i] != '\n') adv(1)
    }

    fun run() {
        var guard = 0
        while (i < n) {
            if ((++guard and 0x3FFF) == 0 && cancelled()) return
            if (col == 0) {
                last = -1
                lineBlank = true
                put(if (state == 1) Tok.COMMENT else if (state == 2) Tok.STRING else Tok.PLAIN)
            }
            val c = t[i]
            if (c == '\n') {
                i++; line++; col = 0
                continue
            }
            when (state) {
                1 -> blockBody()
                2 -> strBody()
                else -> normal(c)
            }
        }
    }

    private fun blockBody() {
        val end = lang.blockEnd ?: "*/"
        while (i < n) {
            val c = t[i]
            if (c == '\n') return
            if (c == end[0] && starts(end)) {
                adv(end.length); state = 0; return
            }
            adv(1)
        }
    }

    private fun strBody() {
        while (i < n) {
            val c = t[i]
            if (c == '\n') return
            if (c == '\\' && strEnd != "```" && i + 1 < n && t[i + 1] != '\n') {
                adv(2); continue
            }
            if (c == strEnd[0] && starts(strEnd)) {
                adv(strEnd.length); state = 0; return
            }
            adv(1)
        }
    }

    private fun normal(c: Char) {
        if (lang.markdown) { markdown(c); return }
        if (lang.markup) { markup(c); return }
        if (c == ' ' || c == '\t' || c == '\r') {
            put(Tok.PLAIN); adv(1); return
        }
        val bs = lang.blockStart
        if (bs != null && c == bs[0] && starts(bs)) {
            put(Tok.COMMENT); adv(bs.length); state = 1; lineBlank = false; return
        }
        for (lc in lang.line) {
            if (c == lc[0] && starts(lc)) {
                put(Tok.COMMENT); toEol(); return
            }
        }
        if (lang.hashDirective && c == '#' && lineBlank) {
            put(Tok.KEYWORD)
            var j = i + 1
            while (j < n && t[j].isLetter()) j++
            adv(j - i); lineBlank = false; return
        }
        if (lang.triple && (c == '"' || c == '\'') && i + 2 < n && t[i + 1] == c && t[i + 2] == c) {
            strEnd = "$c$c$c"
            put(Tok.STRING); adv(3); state = 2; lineBlank = false; return
        }
        if (lang.backtick && c == '`') {
            strEnd = "`"
            put(Tok.STRING); adv(1); state = 2; lineBlank = false; return
        }
        if (lang.quotes.indexOf(c) >= 0) {
            var j = i + 1
            while (j < n) {
                val d = t[j]
                if (d == '\n') break
                if (d == '\\' && j + 1 < n && t[j + 1] != '\n') { j += 2; continue }
                if (d == c) { j++; break }
                j++
            }
            var kind = Tok.STRING
            if (lang.jsonKeys) {
                var m = j
                while (m < n && (t[m] == ' ' || t[m] == '\t')) m++
                if (m < n && t[m] == ':') kind = Tok.TYPE
            }
            put(kind); adv(j - i); lineBlank = false; return
        }
        if (c in '0'..'9' && (i == 0 || !(t[i - 1].isLetterOrDigit() || t[i - 1] == '_'))) {
            var j = i
            while (j < n) {
                val d = t[j]
                if (d.isLetterOrDigit() || d == '_' || (d == '.' && j + 1 < n && t[j + 1] in '0'..'9')) j++ else break
            }
            put(Tok.NUMBER); adv(j - i); lineBlank = false; return
        }
        if (c == '@' && lang.atWords && i + 1 < n && t[i + 1].isLetter()) {
            var j = i + 1
            while (j < n && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '.')) j++
            put(Tok.KEYWORD); adv(j - i); lineBlank = false; return
        }
        if (c.isLetter() || c == '_' || c == '$') {
            ident(c); return
        }
        if (!(c == '-' && lang.keyColon)) lineBlank = false
        put(Tok.PLAIN); adv(1)
    }

    private fun ident(c: Char) {
        var j = i + 1
        while (j < n) {
            val d = t[j]
            if (d.isLetterOrDigit() || d == '_' || d == '$' || (lang.dashIdent && d == '-')) j++ else break
        }
        val len = j - i
        var kind = Tok.PLAIN
        if (len in 2..16) {
            val w = t.subSequence(i, j).toString()
            val key = if (lang.ci) w.lowercase() else w
            if (lang.kw.contains(key)) kind = Tok.KEYWORD
            else if (lang.ty.contains(key)) kind = Tok.TYPE
        }
        if (kind == Tok.PLAIN) {
            var m = j
            while (m < n && (t[m] == ' ' || t[m] == '\t')) m++
            val nx = if (m < n) t[m] else ' '
            if (lang.keyColon && lineBlank && (nx == ':' || nx == '=')) kind = Tok.TYPE
            else if (lang.typeByCase && c.isUpperCase() && len > 1) kind = Tok.TYPE
            else if (nx == '(') kind = Tok.FUNC
        }
        put(kind); adv(len); lineBlank = false
    }

    private fun markdown(c: Char) {
        if (col == 0 && starts("```")) {
            strEnd = "```"; put(Tok.STRING); adv(3); state = 2; return
        }
        if (col == 0 && c == '#') { put(Tok.KEYWORD); toEol(); return }
        if (col == 0 && c == '>') { put(Tok.COMMENT); toEol(); return }
        if (c == '`') {
            var j = i + 1
            while (j < n && t[j] != '`' && t[j] != '\n') j++
            if (j < n && t[j] == '`') { put(Tok.STRING); adv(j + 1 - i); return }
        }
        put(Tok.PLAIN); adv(1)
    }

    private fun markup(c: Char) {
        val bs = lang.blockStart
        if (!inTag) {
            if (bs != null && c == '<' && starts(bs)) {
                put(Tok.COMMENT); adv(bs.length); state = 1; return
            }
            if (c == '<' && i + 1 < n) {
                val d = t[i + 1]
                if (d.isLetter() || d == '/' || d == '!' || d == '?') {
                    put(Tok.KEYWORD)
                    var j = i + 1
                    if (d == '/' || d == '!' || d == '?') j++
                    while (j < n && (t[j].isLetterOrDigit() || t[j] == '-' || t[j] == ':' || t[j] == '_' || t[j] == '.')) j++
                    adv(j - i); inTag = true; return
                }
            }
            put(Tok.PLAIN); adv(1); return
        }
        if (c == '>') { put(Tok.KEYWORD); adv(1); inTag = false; return }
        if (c == '/' && i + 1 < n && t[i + 1] == '>') { put(Tok.KEYWORD); adv(2); inTag = false; return }
        if (c == '"' || c == '\'') {
            var j = i + 1
            while (j < n && t[j] != '\n' && t[j] != c) j++
            if (j < n && t[j] == c) j++
            put(Tok.STRING); adv(j - i); return
        }
        if (c.isLetter() || c == '_' || c == ':' || c == '@') {
            var j = i + 1
            while (j < n && (t[j].isLetterOrDigit() || t[j] == '-' || t[j] == '_' || t[j] == ':' || t[j] == '.')) j++
            put(Tok.TYPE); adv(j - i); return
        }
        put(Tok.PLAIN); adv(1)
    }
}