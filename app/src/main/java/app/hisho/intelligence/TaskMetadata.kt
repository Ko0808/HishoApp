package app.hisho.intelligence

data class TaskMetadata(
    val isCandidate: Boolean,
    val candidateReason: String,
    val deadlineEpochMillis: Long?,
    val deadlineType: DeadlineType,
    val effort: EffortBucket,
    val priority: Priority,
    val category: TaskCategory,
)

enum class DeadlineType { HARD, SOFT, NONE }

enum class EffortBucket(val minutes: Int) {
    XS(10), S(25), M(60), L(120), XL(240)
}

enum class Priority { LOW, NORMAL, HIGH }

enum class TaskCategory { COMMUNICATION, DOCUMENT, MEETING, ADMIN, OTHER }

