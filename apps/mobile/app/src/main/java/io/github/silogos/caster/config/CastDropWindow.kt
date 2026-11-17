package io.github.silogos.caster.config

/**
 * How long a cast waits for its link to come back before it ends (ADR-036):
 * the same five minutes over the cable and over Wi-Fi (product owner,
 * 2026-11-18). The projection stays alive for the whole wait, so a link that
 * returns in time needs no new Share and no new consent.
 */
object CastDropWindow {
    const val MS = 5 * 60 * 1000L
}
