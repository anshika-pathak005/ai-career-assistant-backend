package com.careerplatform.career_assistant.enums;

/**
 * Subscription plan.
 *
 * Stored with @Enumerated(EnumType.STRING) — never ORDINAL. Ordinal storage writes 0/1
 * and silently reinterprets every existing row if anyone reorders the constants, which
 * on this field means handing PRO limits to FREE users.
 *
 * The names must stay exactly "FREE" and "PRO": rows written before this enum existed
 * hold those strings, and the JSON API still exposes them via name().
 */
public enum Plan {
    FREE,   // monthly generation limits apply
    PRO     // limits skipped, usage still counted
}
