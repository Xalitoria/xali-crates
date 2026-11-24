package mc.xalitoria.crates.paper.data.model

/** Inclusive integer bounds for a configuration property; read by both [Validation] and the web editor schema builder. */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Within(val min: Long = Long.MIN_VALUE, val max: Long = Long.MAX_VALUE)

/** Inclusive decimal bounds for a configuration property; read by both [Validation] and the web editor schema builder. */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class WithinRange(val min: Double = Double.NEGATIVE_INFINITY, val max: Double = Double.POSITIVE_INFINITY)
