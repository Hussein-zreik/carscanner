/**
 * Honda-specific diagnostics: mode 22 over F-CAN (Phase 2) and K-line modules (Phase 3).
 *
 * SAFETY: every module gets an explicit allow-list of request IDs/services and the protocol
 * layer refuses anything not on it. No IDs are defined yet: Honda request IDs are unknown
 * until captured from the car. Never invent them; mark them TODO(capture).
 */
package com.crvdiag.honda
