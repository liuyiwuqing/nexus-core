package dev.nexus.core.ai;

/** A safe error code, without upstream body, credentials or private request data. */
public final class AnalysisFailure extends RuntimeException {
    public AnalysisFailure(String code) { super(code); }
}
