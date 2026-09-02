package app.hisho.scheduling

object TaskBlockPlanner {
    /** Long work is kept cognitively manageable and can cross days when needed. */
    fun split(effortMinutes: Int, maximumBlockMinutes: Int = 60): List<Int> {
        require(effortMinutes > 0)
        require(maximumBlockMinutes > 0)
        val blocks = mutableListOf<Int>()
        var remaining = effortMinutes
        while (remaining > 0) {
            val block = minOf(remaining, maximumBlockMinutes)
            blocks += block
            remaining -= block
        }
        return blocks
    }
}
