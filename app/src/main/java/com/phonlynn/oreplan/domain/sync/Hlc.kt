package com.phonlynn.oreplan.domain.sync

/**
 * 混合逻辑时钟（HLC）—— 云同步用来给「谁先谁后」定序。
 *
 * ## 为什么不能用墙钟
 *
 * 墙钟在两端之间**不可比**：手机时间快 3 分钟、或用户手动改过时间，
 * 就会出现「A 的旧修改赢过 B 的新修改」。同步里的胜负判断一旦建立在墙钟上，
 * 表现是**偶发、难复现的数据回退**——用户改了东西，过一会儿又变回去了。
 *
 * HLC 的做法：保留物理时间（可读性），但保证**单调**（可比性）：
 *
 * - 本地事件：`physical = max(now, lastPhysical)`；同一毫秒内多次事件则 `counter++`
 * - 收到远端事件：把本地时钟**推到不小于远端**，再取 `counter + 1`
 *
 * 于是「时间戳大的就是后发生的」在**任何**两台设备之间都成立。
 *
 * ## 编码成字符串
 *
 * 形如 `0000019a3f2b1c40-0003-a1b2c3d4`：物理毫秒（16 位十六进制，定长便于字典序比较）
 * `-` 逻辑计数（4 位十六进制）`-` deviceId 前缀。
 *
 * **定长 + 十六进制**是为了让字符串的字典序 == 时间的先后序，
 * 于是可以直接用于排序键、也可安全地存进 `entity_ext`（抽屉只支持字符串/数字/布尔）。
 */
data class Hlc(
    /** 物理时间（毫秒）。 */
    val physical: Long,
    /** 同一毫秒内的第几次事件。 */
    val counter: Int,
    /** 产生该事件的设备。用于「物理时间与计数都相同」时的最终 tie-break。 */
    val deviceId: String,
) : Comparable<Hlc> {

    override fun compareTo(other: Hlc): Int {
        physical.compareTo(other.physical).let { if (it != 0) return it }
        counter.compareTo(other.counter).let { if (it != 0) return it }
        // 前两项都相同：用 deviceId 兜底，**保证全序**。
        // 若这里返回 0，两台设备的并发修改会被判为「相等」，
        // 合并结果就依赖到达顺序 —— 那是不可复现的。
        return deviceId.compareTo(other.deviceId)
    }

    /** 编码成定长十六进制串（字典序 == 时间序）。 */
    fun encode(): String = buildString {
        append(physical.toString(16).padStart(16, '0'))
        append('-')
        append(counter.toString(16).padStart(4, '0'))
        append('-')
        append(deviceId)
    }

    companion object {
        /** 时钟起点。任何真实事件的 HLC 都大于它。 */
        val ZERO = Hlc(0L, 0, "")

        fun decode(text: String): Hlc? {
            val parts = text.split('-')
            if (parts.size < 3) return null
            val physical = parts[0].toLongOrNull(16) ?: return null
            val counter = parts[1].toIntOrNull(16) ?: return null
            return Hlc(physical, counter, parts.drop(2).joinToString("-"))
        }

        /**
         * 本地发生一次事件后的新时钟。
         *
         * @param now 当前墙钟毫秒
         * @param last 本机此前的时钟（没有就用 [ZERO]）
         */
        fun tick(now: Long, last: Hlc, deviceId: String): Hlc {
            // 墙钟倒退（用户改时间/NTP 校正）时**不跟随**，只在物理值上单调递增。
            val physical = maxOf(now, last.physical)
            val counter = if (physical == last.physical) last.counter + 1 else 0
            return Hlc(physical, counter, deviceId)
        }

        /**
         * 收到远端事件后本机时钟应有的值。
         *
         * 规则来自 HLC 定义：新时钟必须**大于**本地与远端两者，
         * 否则本机后续事件会被判成"早于"已经见过的远端事件。
         */
        fun merge(now: Long, local: Hlc, remote: Hlc, deviceId: String): Hlc {
            val physical = maxOf(now, local.physical, remote.physical)
            val counter = when {
                physical == local.physical && physical == remote.physical ->
                    maxOf(local.counter, remote.counter) + 1
                physical == local.physical -> local.counter + 1
                physical == remote.physical -> remote.counter + 1
                else -> 0
            }
            return Hlc(physical, counter, deviceId)
        }
    }
}
