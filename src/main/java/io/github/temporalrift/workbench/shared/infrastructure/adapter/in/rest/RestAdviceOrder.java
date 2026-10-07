package io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest;

/**
 * Ordering contract for {@code @RestControllerAdvice} beans. Advice order — not exception-type
 * specificity — decides handler lookup across advices.
 */
public final class RestAdviceOrder {

    /** Every module-scoped exception handler advice. */
    public static final int MODULE = 0;

    /**
     * Only the shared catch-all advice. Same value as {@code Ordered.LOWEST_PRECEDENCE} (kept as a
     * literal so this class stays framework-free).
     */
    public static final int GLOBAL_FALLBACK = Integer.MAX_VALUE;

    private RestAdviceOrder() {}
}
