/*
 * Ported from Mika (github.com/wangzan101/Mika), Apache License 2.0.
 * Copyright 2026 wangzan101. Original code used with attribution.
 * Adaptations: renamed package, dropped web/windows/opened/multi-profile fields,
 * dropped double-tap / multi-profile / shell / OCR / idle-scroll / app-access methods.
 */
package io.github.ryoaonetsuki.mira.automation.agent

import kotlinx.coroutines.delay

/** 屏幕读屏的默认滑动时长。 */
const val DEFAULT_SWIPE_MS = 400L

/**
 * 读一次屏的结果。[changedDuringRead]:读这份列表的前后界面不一致;[protected]:受系统防截屏保护;
 * [hasWindow]:屏上有 App 窗口;[unreadableApp]:读不到窗口内容但知道前台是哪个 App 时的包名;
 * [captureError]:画面不可用但不是已证实的防截屏。
 */
data class MiraScreenSnapshot(
    val snapshotId: String,
    val packageName: String?,
    val elements: List<MikaUiElement>,
    val width: Int,
    val height: Int,
    val changedDuringRead: Boolean = false,
    val protected: Boolean = false,
    val hasWindow: Boolean = true,
    val unreadableApp: String? = null,
    val captureError: String? = null,
)

/** 动作执行结果。 */
sealed interface MiraActionResult {
    /**
     * 动作已发出。其余字段是端口事后核实到的事实,null/false 表示没有核实或不适用:
     * [changed] 动作前后界面是否变化;[appSwitched] 前台已不是目标 App 时的当前包名;
     * [relocated] 编号已过期、按原元素特征在新界面里找回后执行,[relocatedTo] 是找回的元素现在的名字;
     * [switchedByTarget] 新前台是目标 App 在自己的任务里打开的;[verification] 输入回读结果;
     * [hint] 端口对这次结果的补充说明;[secret] 输入的目标是密码框;[coveredBy] 打开的 App 已在最上面,
     * 但另一个包的窗口盖在它上面时那个包。
     */
    data class Ok(
        val changed: Boolean? = null,
        val appSwitched: String? = null,
        val relocated: Boolean = false,
        val relocatedTo: String? = null,
        val switchedByTarget: Boolean = false,
        val verification: Verification? = null,
        val hint: String? = null,
        val secret: Boolean = false,
        val coveredBy: String? = null,
    ) : MiraActionResult

    /** 引用已过期时 reason 为 "ref_expired"。[hint] 是端口的补充说明. */
    data class Failed(val reason: String, val hint: String? = null) : MiraActionResult
}

/** 屏幕诊断:端口不支持时为 null. */
data class MiraScreenCheck(val empty: Boolean, val protected: Boolean, val unreadableApp: String? = null)

/** Agent 观察和操作手机屏幕的唯一入口,由 Android 模块实现。 */
interface MiraScreenPort {
    /** 读一屏。 */
    suspend fun snapshot(): MiraScreenSnapshot

    /** 读一屏但不登记编号(模型手里的 ref 不受影响);不可逆判定用。 */
    suspend fun peek(): MiraScreenSnapshot = snapshot()

    /** 截图 PNG 字节;没有画面或端口不支持时为 null。 */
    suspend fun screenshotPng(): ByteArray?

    suspend fun tapRef(ref: String, snapshotId: String): MiraActionResult
    suspend fun tapXY(x: Int, y: Int): MiraActionResult
    suspend fun typeText(ref: String?, snapshotId: String?, text: String): MiraActionResult
    suspend fun clearText(ref: String?, snapshotId: String?): MiraActionResult

    /**
     * 往这一点([x],[y] 为像素)上的输入框里打字:先点它,点出了拿着焦点的输入框就照常打.
     */
    suspend fun typeAt(x: Int, y: Int, text: String): MiraActionResult =
        tapXY(x, y).let { if (it is MiraActionResult.Ok) typeText(null, null, text) else it }

    /** 清空这一点上的输入框,做法同 [typeAt]。 */
    suspend fun clearAt(x: Int, y: Int): MiraActionResult =
        tapXY(x, y).let { if (it is MiraActionResult.Ok) clearText(null, null) else it }

    suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): MiraActionResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = DEFAULT_SWIPE_MS): MiraActionResult

    /** 多点手势；每条路径是按时间先后排列的像素点，所有手指同时移动。 */
    suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): MiraActionResult =
        MiraActionResult.Failed("unsupported")

    suspend fun back(): MiraActionResult
    suspend fun pressKey(keyCode: Int): MiraActionResult
    suspend fun openApp(packageName: String, cold: Boolean = false): MiraActionResult

    /**
     * 在 Agent 的屏上打开一个链接或安卓设置页([action] 为 null 时按 VIEW 打开 [uri]);[packageName] 限定由哪个 App 打开。
     * 失败原因:link_unresolved(没有 App 能打开)、invalid_link、app_in_use_by_user。不支持的端口返回 unsupported。
     */
    suspend fun openLink(action: String?, uri: String?, packageName: String?): MiraActionResult =
        MiraActionResult.Failed("unsupported")

    suspend fun waitStable(timeoutMs: Long): Boolean
    /**
     * 等这块屏的下一次界面变化,最多 [timeoutMs];等到返回 true。不支持的端口等满时限返回 false.
     */
    suspend fun awaitChange(timeoutMs: Long): Boolean { delay(timeoutMs.coerceAtLeast(0)); return false }
    /** 等待事件驱动的界面条件；condition: appear/disappear/activity/stable。 */
    suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = false
    /** 屏幕诊断;端口不支持时为 null. */
    suspend fun checkScreen(): MiraScreenCheck? = null
    /** 当前页面是否受系统防截屏保护。 */
    suspend fun isProtected(): Boolean = false
    /** 此刻画面缩成的亮度格子(判动作效果用,见 [FrameGrid.sample]);没有画面、端口不支持时为 null。要便宜:不编码图片。 */
    suspend fun frameGrid(): FrameGrid? = null
}

/**
 * 从 Mika 的 ScreenPort 故意不迁移的项:
 * - doubleTapRef/doubleTapXY: Mira 没有调用方
 * - openAppCopy/awaitAppFree/otherProfiles/appCopies: 多用户资料 / 虚拟显示
 * - shareText/systemAction/bulkCalendarInsert/setWheel: 无 Mira 调用方; 轮代码已丢弃
 * - nextEvent/watchEvents/systemCommand/listApps: 无 Mira 调用方 / shell
 * - readVisibleText: Mira 无 OCR
 * - idleScroll: 无调用方
 * - appAccess/checkAppAccess: Mika 的多任务 AppAccessPolicy; Mira 单任务在用户屏上运行
 * - afterTask: 尚无任务结束处理
 */
