package com.choidev.tailhop

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 언어. 기본은 시스템 언어를 따르고(한국어·일본어·간체 중국어가 아니면 영어 = values/), 앱 안에서 고를 수도 있다.
 *
 * - Android 13 이상: AppCompatDelegate → 시스템 앱별 언어(LocaleManager). 설정 앱의 "앱 언어"에서 바꿔도 같다.
 *   서비스·알림·공유 화면도 시스템이 고른 언어로 뜬다.
 * - Android 12 이하: AppCompat이 언어를 저장해 AppCompat 화면에 적용한다(manifest의 autoStoreLocales).
 *   AppCompat이 아닌 곳(수신 서비스, 공유 화면, 브로드캐스트)은 wrap()으로 같은 언어를 입힌다.
 */
object Lang {
    /** 앱에서 고를 수 있는 언어 태그. ""는 시스템 설정 따르기. */
    val TAGS = listOf("", "ko", "en", "ja", "zh-CN")

    private const val PREFS = "settings"
    private const val K_LANG = "lang"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 지금 고른 언어 태그(""이면 시스템). */
    fun chosen(ctx: Context): String {
        val tags = if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags() ?: ""
        } else prefs(ctx).getString(K_LANG, "") ?: ""
        val first = tags.substringBefore(',')
        return TAGS.firstOrNull { it.isNotEmpty() && first.startsWith(it, ignoreCase = true) } ?: ""
    }

    fun choose(ctx: Context, tag: String) {
        prefs(ctx).edit().putString(K_LANG, tag).apply()
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
    }

    fun displayName(ctx: Context, tag: String): String = when (tag) {
        "ko" -> ctx.getString(R.string.lang_name_ko)
        "en" -> ctx.getString(R.string.lang_name_en)
        "ja" -> ctx.getString(R.string.lang_name_ja)
        "zh-CN" -> ctx.getString(R.string.lang_name_zh)
        else -> ctx.getString(R.string.lang_system)
    }

    /** AppCompat 밖(서비스·공유 화면·브로드캐스트)에서 앱 언어를 입힌 Context. Android 13+는 시스템이 해 주므로 그대로. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString(K_LANG, "") ?: ""
        if (tag.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(cfg)
    }

    /** 한국어 리소스(상대에게 보내는 오류 문장용 — 예전 PC가 그대로 보여 주므로 화면 언어와 상관없이 한국어). */
    fun korean(ctx: Context): Context {
        val cfg = Configuration(ctx.resources.configuration)
        cfg.setLocale(Locale.KOREAN)
        return ctx.createConfigurationContext(cfg)
    }

    fun locale(ctx: Context): Locale = ctx.resources.configuration.locales[0]

    // ---------- 날짜·시간(화면 언어 기준) ----------
    fun time(ctx: Context, ts: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ts))

    fun dayHeader(ctx: Context, ts: Long): String {
        val loc = locale(ctx)
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "MMMMdEEEE"), loc).format(Date(ts))
    }

    fun shortTime(ctx: Context, ts: Long): String = when {
        DateUtils.isToday(ts) -> time(ctx, ts)
        DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS) -> ctx.getString(R.string.yesterday)
        else -> {
            val loc = locale(ctx)
            SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "MMMd"), loc).format(Date(ts))
        }
    }
}
