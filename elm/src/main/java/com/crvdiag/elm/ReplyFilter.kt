package com.crvdiag.elm

/**
 * Keeps only the reply lines that answer the request just sent. Lines that belong to an earlier
 * request (a late answer the clone printed after its prompt) or adapter junk are dropped.
 * An empty result means "this reply was not for us": [Elm327] keeps waiting for the real one.
 */
fun interface ReplyFilter {
    fun keep(lines: List<String>): List<String>
}
