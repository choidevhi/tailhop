package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 여러 개 이름 바꾸기(docs/PROTOCOL.md §18.2, 2.2.1). 허브가 hello caps에 "rename"을 알릴 때만 쓴다.
 * 새 이름 만들기(찾아 바꾸기·앞뒤에 붙이기·번호 매기기·대소문자)와 미리보기의 규칙 검사, 요청·응답 형식. 안드로이드 의존성 없음(JVM 단위 테스트).
 */
object CloudRename {
    const val MAX_ITEMS = 200
    /** 번호 매기기 패턴에서 번호가 들어갈 자리. 없으면 패턴 끝에 붙인다. */
    const val NUMBER_MARK = "{n}"
    const val MAX_DIGITS = 6
    private const val NAME_MAX = 150

    enum class Mode { REPLACE, PREFIX, SUFFIX, NUMBER, CASE }
    enum class Case { LOWER, UPPER, TITLE }

    /**
     * 바꾸는 방법 하나. REPLACE는 find → replace(이름 전체, matchCase가 아니면 대소문자 무시), PREFIX·SUFFIX는 text를 이름 앞·확장자 앞에,
     * NUMBER는 pattern의 {n}을 start부터의 번호(digits자리, 앞을 0으로)로 바꾸고 확장자를 붙인다, CASE는 대소문자를 바꾼다(확장자는 그대로).
     */
    data class Rule(
        val mode: Mode, val find: String = "", val replace: String = "", val matchCase: Boolean = false,
        val text: String = "", val pattern: String = "", val start: Int = 1, val digits: Int = 3, val case: Case = Case.LOWER,
    )

    /** 이름 바꾸기 한 건(경로와 새 이름). */
    data class Item(val path: String, val name: String)

    /** 미리보기 한 줄: problem이 null이면 보낼 것, UNCHANGED면 그대로 둘 것(보내지 않음), 나머지는 규칙 위반의 이유. */
    data class Plan(val path: String, val old: String, val new: String, val problem: String?)

    const val EMPTY = "empty"
    const val BAD_NAME = "bad_name"
    const val TOO_LONG = "too_long"
    const val PART = "part"
    const val EXISTS = "exists"
    const val DUPLICATE = "duplicate"
    const val UNCHANGED = "unchanged"

    /**
     * 이름을 확장자 앞(stem)과 확장자(`.jpg`, 점 포함)로. 폴더·확장자가 없거나 이상한 이름은 확장자 없음.
     * 확장자 = 마지막 `.` 뒤 1–16자(맨 앞의 `.`은 확장자가 아니다, §5 파일명 정리와 같은 길이).
     */
    fun split(name: String, dir: Boolean): Pair<String, String> {
        if (dir) return name to ""
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return name to ""
        val ext = name.substring(dot + 1)
        return if (ext.codePointCount(0, ext.length) in 1..16) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    /** 번호(0으로 앞을 채운 digits자리, 넘치면 그대로 길게). */
    fun number(n: Int, digits: Int): String = n.toString().padStart(digits.coerceIn(1, MAX_DIGITS), '0')

    /** 규칙대로 만든 새 이름(검사 전). index는 고른 순서(0부터, 번호 매기기에 쓴다). */
    fun apply(rule: Rule, name: String, dir: Boolean, index: Int): String {
        val (stem, ext) = split(name, dir)
        return when (rule.mode) {
            Mode.REPLACE -> if (rule.find.isEmpty()) name else name.replace(rule.find, rule.replace, ignoreCase = !rule.matchCase)
            Mode.PREFIX -> rule.text + name
            Mode.SUFFIX -> stem + rule.text + ext
            Mode.NUMBER -> {
                val n = number(rule.start + index, rule.digits)
                val p = if (NUMBER_MARK in rule.pattern) rule.pattern else rule.pattern + NUMBER_MARK
                p.replace(NUMBER_MARK, n) + ext
            }
            Mode.CASE -> when (rule.case) {
                Case.LOWER -> stem.lowercase(Locale.ROOT) + ext
                Case.UPPER -> stem.uppercase(Locale.ROOT) + ext
                Case.TITLE -> title(stem) + ext
            }
        }
    }

    /** 낱말(공백·`_`·`-`로 나뉜)마다 첫 글자는 대문자, 나머지는 소문자. */
    private fun title(s: String): String {
        val sb = StringBuilder(s.length)
        var start = true
        for (c in s) {
            sb.append(if (start) c.uppercaseChar() else c.lowercaseChar())
            start = c == ' ' || c == '_' || c == '-'
        }
        return sb.toString()
    }

    /**
     * 새 이름 하나의 규칙 검사(§12.1 조각 규칙, 허브가 bad_name으로 거부할 것을 미리): 비었으면 EMPTY, 150자를 넘으면 TOO_LONG,
     * `.part`로 끝나면 PART(목록에 보이지 않게 된다), §5 파일명 정리를 거쳐 달라지면(쓸 수 없는 글자, 앞의 `.`, 끝의 공백·`.`,
     * 장치 이름) BAD_NAME. 맞으면 null. 앞뒤 공백도 이름의 일부다(자르지 않는다 — 끝 공백은 BAD_NAME).
     */
    fun check(name: String): String? = when {
        name.isEmpty() -> EMPTY
        name.codePointCount(0, name.length) > NAME_MAX -> TOO_LONG
        !CloudPath.isSegment(name) -> BAD_NAME
        CloudPath.isPartName(name) -> PART
        else -> null
    }

    /**
     * 미리보기: 고른 순서대로 새 이름을 만들고 검사한다. siblings는 폴더(부모 경로)마다 지금 아는 이름들(목록에서 본 것, 대소문자 무관으로 비교).
     * 허브는 앞에서부터 차례로 바꾸므로(§18.2) 같은 순서로 따라 한다: 한 건을 바꾸면 그 폴더에서 옛 이름이 빠지고 새 이름이 생긴다.
     * 새 이름이 이미 있으면 EXISTS(번호를 붙이지 않는다), 앞의 다른 항목이 이번에 그 이름을 가져갔으면 DUPLICATE.
     * 옛 이름과 글자 하나까지 같으면 UNCHANGED(보내지 않는다). 대소문자만 바꾸는 것은 된다.
     */
    fun plan(items: List<Pair<String, Boolean>>, siblings: Map<String, Collection<String>>, make: (String, Boolean, Int) -> String): List<Plan> {
        val names = HashMap<String, HashSet<String>>()
        fun folder(dir: String) = names.getOrPut(dir.lowercase(Locale.ROOT)) {
            siblings.entries.filter { it.key.equals(dir, ignoreCase = true) }.flatMapTo(HashSet()) { e -> e.value.map { low(it) } }
        }
        val taken = HashSet<String>() // 이번에 새로 생긴 이름(폴더|이름, 소문자)
        return items.mapIndexed { i, (path, dir) ->
            val parent = CloudPath.parent(path)
            val old = CloudPath.name(path)
            val new = make(old, dir, i)
            val set = folder(parent)
            val key = low(parent) + "|" + low(new)
            val problem = when {
                new == old -> UNCHANGED
                else -> check(new) ?: when {
                    low(new) != low(old) && low(new) in set -> if (key in taken) DUPLICATE else EXISTS
                    else -> null
                }
            }
            if (problem == null) {
                set.remove(low(old))
                taken.remove(low(parent) + "|" + low(old))
                set += low(new)
                taken += key
            }
            Plan(path, old, new, problem)
        }
    }

    private fun low(s: String) = s.lowercase(Locale.ROOT)

    /** 보낼 것만(problem이 null인 줄). */
    fun sendable(plan: List<Plan>): List<Item> = plan.filter { it.problem == null }.map { Item(it.path, it.new) }

    /** 요청의 items. 1–MAX_ITEMS개, 경로는 빈 경로가 아닌 규칙에 맞는 것, 새 이름은 check를 지난 것(부른 쪽의 실수면 IllegalArgumentException). */
    fun itemsJson(items: List<Item>): JSONArray {
        require(items.size in 1..MAX_ITEMS && items.all { it.path.isNotEmpty() && CloudPath.isValid(it.path) && check(it.name) == null })
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("path", it.path).put("name", it.name)) }
        return arr
    }

    /** 응답(§18.2): renamed·failed 중 하나는 배열이어야 한다. 모양이 틀린 줄은 버린다(옮기기 응답과 같은 검사, 결과도 같은 모양). */
    fun parse(reply: JSONObject): MoveResult {
        val renamed = reply.opt("renamed") as? JSONArray
        val failed = reply.opt("failed") as? JSONArray
        if (renamed == null && failed == null) throw ProtocolException("bad_reply")
        val o = JSONObject()
        if (renamed != null) o.put("moved", renamed)
        if (failed != null) o.put("failed", failed)
        return CloudMove.parse(o)
    }

    /**
     * MAX_ITEMS개씩 나눠 보내고 결과를 합친다(같은 경로는 처음 것만). 첫 묶음이 통째로 거부되면 그 예외를 던진다(아무것도 바꾸지 않았다).
     * 뒤 묶음이 통째로 거부되면 앞의 결과는 살리고 그 묶음을 그 코드로 failed에 넣는다(옮기기와 같다).
     */
    fun run(items: List<Item>, send: (List<Item>) -> MoveResult): MoveResult {
        var out = MoveResult.EMPTY
        items.distinctBy { it.path }.chunked(MAX_ITEMS).forEachIndexed { i, chunk ->
            out += try { send(chunk) } catch (e: ProtocolException) {
                if (i == 0) throw e
                MoveResult(emptyList(), chunk.map { MoveResult.Failed(it.path, e.code) })
            }
        }
        return out
    }
}
