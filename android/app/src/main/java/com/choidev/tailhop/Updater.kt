package com.choidev.tailhop

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream

/**
 * 업데이트(1.9.0, docs/PROTOCOL.md §13). 페어링된 PC 중 배포 PC가 hello로 알린 새 APK를 그 PC에서 받아(update_get),
 * 크기·SHA-256과 서명 인증서(지금 앱과 같아야 함)를 확인한 뒤 PackageInstaller로 설치한다. 인터넷을 거치지 않는다.
 * 찾은 업데이트는 설정에 적어 두어(PC IP·버전·code·크기) 홈 화면 줄이 다시 열어도 보인다. 자동 확인은 6시간에 한 번.
 */
object Updater {
    private const val PREFS = "settings"
    private const val K_LAST = "updateCheckedAt"
    private const val K_IP = "updateIp"
    private const val K_VERSION = "updateVersion"
    private const val K_CODE = "updateCode"
    private const val K_SIZE = "updateSize"
    /** 설치를 넘긴 버전(업데이트 뒤 MY_PACKAGE_REPLACED에서 "업데이트했습니다" 알림을 띄울 때 쓴다). */
    private const val K_INSTALLING = "updateInstalling"
    const val AUTO_MS = 6 * 3600_000L
    private const val ACTION_STATUS = "com.choidev.tailhop.UPDATE_STATUS"

    data class Found(val pc: Pairing, val offer: UpdateOffer)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 저장해 둔 업데이트. 그 PC가 연결 해제됐거나 지금 버전이 이미 그 이상이면 null. */
    fun found(ctx: Context): Found? {
        val sp = prefs(ctx)
        val ip = sp.getString(K_IP, null) ?: return null
        val offer = UpdateOffer(sp.getString(K_VERSION, "") ?: "", sp.getInt(K_CODE, 0), sp.getLong(K_SIZE, 0))
        if (!Updates.isNewer(offer, BuildConfig.VERSION_CODE)) return null
        val p = Store.get(ctx, ip) ?: return null
        return Found(p, offer)
    }

    private fun remember(ctx: Context, f: Found?) {
        val e = prefs(ctx).edit()
        if (f == null) e.remove(K_IP).remove(K_VERSION).remove(K_CODE).remove(K_SIZE)
        else e.putString(K_IP, f.pc.pcIp).putString(K_VERSION, f.offer.version).putInt(K_CODE, f.offer.code).putLong(K_SIZE, f.offer.size)
        e.apply()
    }

    /**
     * 모든 PC에 hello로 묻는다(동시에, 네트워크를 쓰므로 메인 스레드에서 부르지 않는다). 지금보다 높은 것 중 가장 높은 code를 찾아 적는다.
     * (찾은 것, 한 PC라도 대답했는지). 아무도 대답하지 않았으면 예전에 찾아 둔 것을 지우지 않는다.
     */
    fun check(ctx: Context): Pair<Found?, Boolean> {
        val app = ctx.applicationContext
        val pcs = Store.all(app)
        val results = arrayOfNulls<Xfer.Hello>(pcs.size)
        val answered = BooleanArray(pcs.size)
        val threads = pcs.mapIndexed { i, p ->
            Thread({
                try { results[i] = Net.hello(p, null, null); answered[i] = true } catch (e: Exception) { }
            }, "tailhop-update-check").also { it.start() }
        }
        threads.forEach { try { it.join(20_000) } catch (e: InterruptedException) { } }
        val offers = pcs.indices.mapNotNull { i -> results[i]?.update?.let { pcs[i] to it } }
        val best = Updates.best(offers, BuildConfig.VERSION_CODE)?.let { Found(it.first, it.second) }
        val any = answered.any { it }
        prefs(app).edit().putLong(K_LAST, System.currentTimeMillis()).apply()
        if (any || best != null) remember(app, best)
        return best to any
    }

    /** 앱을 열 때: 마지막 확인이 6시간 전보다 오래면 조용히 따로 스레드에서 확인하고 then(찾은 것)을 부른다. */
    fun autoCheckIfDue(ctx: Context, then: (Found?) -> Unit) {
        val app = ctx.applicationContext
        val last = prefs(app).getLong(K_LAST, 0L)
        val now = System.currentTimeMillis()
        if (now - last in 0 until AUTO_MS) return
        prefs(app).edit().putLong(K_LAST, now).apply() // 여러 번 열어도 한 번만
        Thread({ then(try { check(app).first } catch (e: Exception) { null }) }, "tailhop-update-auto").start()
    }

    private fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "update"), "TailHop-update.apk")

    /**
     * 받는다(§13.2): 앱 캐시에 쓰면서 SHA-256을 계산하고, 크기(readData)와 해시(checkSha)가 첫 프레임과 같은지 본다.
     * 다르거나 끊기면 받은 파일을 지운다(이어 받지 않는다 — APK는 작고 처음부터 받는 편이 단순하다).
     */
    fun download(ctx: Context, f: Found, token: SendToken, onProgress: (Long, Long) -> Unit): File {
        val file = apkFile(ctx)
        file.parentFile?.mkdirs()
        file.delete()
        val md = Xfer.sha256()
        try {
            val head = Net.updateGet(f.pc, token, open = { head ->
                val free = file.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (free < head.size + 16L * 1024 * 1024) throw ProtocolException("disk_full")
                DigestOutputStream(FileOutputStream(file), md)
            }, onProgress = onProgress)
            Updates.checkSha(head, md)
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /**
     * 받은 APK가 이 앱의 새 버전이고(패키지 이름이 같고 versionCode가 지금보다 높음, 아니면 update_bad),
     * 서명 인증서가 지금 앱과 같은지(아니면 update_signer) 본다. 인증서는 SHA-256으로 비교한다.
     */
    fun verify(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val archive = archiveInfo(pm, apk.path) ?: run { apk.delete(); throw ProtocolException("update_bad") }
        if (archive.packageName != ctx.packageName || versionCode(archive) <= BuildConfig.VERSION_CODE) {
            apk.delete(); throw ProtocolException("update_bad")
        }
        val installed = try { installedInfo(pm, ctx.packageName) } catch (e: Exception) { null }
        if (installed == null || !Updates.sameSigner(certs(installed), certs(archive))) {
            apk.delete(); throw ProtocolException("update_signer")
        }
    }

    @Suppress("DEPRECATION")
    private fun signFlags(): Int = if (Build.VERSION.SDK_INT >= 28)
        PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? = pm.getPackageArchiveInfo(path, signFlags())

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = pm.getPackageInfo(pkg, signFlags())

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** 서명 인증서들의 SHA-256. API 28+는 signingInfo의 지금 서명자, 없으면(일부 기기의 보관 파일) 예전 signatures. */
    @Suppress("DEPRECATION")
    private fun certs(info: PackageInfo): List<String> {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            val sigs = si?.apkContentsSigners
            if (!sigs.isNullOrEmpty()) return sigs.map { Updates.certDigest(it.toByteArray()) }
        }
        return info.signatures.orEmpty().map { Updates.certDigest(it.toByteArray()) }
    }

    /**
     * PackageInstaller 세션으로 설치한다(MODE_FULL_INSTALL, 우리 패키지 이름만). Android 12+는 사용자 확인 없이 바뀌도록 요청한다
     * (TailHop이 설치한 앱이 아니면 시스템이 그래도 확인을 묻는다). 결과는 이 앱의 내보내지 않은 UpdateReceiver로 온다.
     */
    fun install(ctx: Context, apk: File) {
        val app = ctx.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    s.fsync(out)
                }
                // 상태 인텐트는 시스템이 채워 넣으므로 Android 12+에서 FLAG_MUTABLE이어야 한다. 받는 쪽은 명시적인 우리 리시버.
                val status = Intent(app, UpdateReceiver::class.java).setAction(ACTION_STATUS).setPackage(app.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(app, id, status, flags)
                prefs(app).edit().putString(K_INSTALLING, found(app)?.offer?.version ?: "").commit()
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            try { installer.abandonSession(id) } catch (x: Exception) { }
            throw if (e is ProtocolException) e else ProtocolException("update_install", e.message)
        }
    }

    /** 설치가 실패·취소됨: 다음 업데이트 뒤 알림을 잘못 띄우지 않게 표시를 지운다. */
    fun installFailed(ctx: Context) { prefs(ctx).edit().remove(K_INSTALLING).apply() }

    /**
     * 이 앱이 새 버전으로 바뀜(MY_PACKAGE_REPLACED): 받기 서비스는 BootReceiver가 다시 켠다. 여기서는 이 앱이 넘긴 설치였으면
     * "업데이트했습니다" 알림을 띄우고, 받은 APK·찾아 둔 업데이트를 지운다.
     */
    fun onReplaced(ctx: Context) {
        val sp = prefs(ctx)
        val mine = sp.contains(K_INSTALLING)
        sp.edit().remove(K_INSTALLING).apply()
        if (found(ctx) == null) remember(ctx, null)
        apkFile(ctx).delete()
        if (mine) Notifs.updated(Lang.wrap(ctx), BuildConfig.VERSION_NAME)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.forEach { try { it() } catch (e: Exception) { } } }
}

/**
 * PackageInstaller 결과(exported=false, Updater.install의 명시적 PendingIntent로만 온다). 확인이 필요하면 시스템 확인 화면을 띄우고,
 * 백그라운드라 화면을 못 띄우면 탭해서 확인하는 알림을 남긴다. 실패·취소는 짧게 알린다.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = Lang.wrap(context)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (e: Exception) { Notifs.updateConfirm(ctx, confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> { } // 이 프로세스는 곧 끝나고, 새 버전이 MY_PACKAGE_REPLACED를 받는다
            else -> {
                Updater.installFailed(context)
                val why = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) ctx.getString(R.string.update_aborted)
                else intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(200) ?: ctx.getString(R.string.unknown_error)
                Toast.makeText(ctx, ctx.getString(R.string.update_failed, why), Toast.LENGTH_LONG).show()
                Updater.changed()
            }
        }
    }
}
