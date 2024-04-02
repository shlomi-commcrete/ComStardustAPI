package com.commcrete.stardust.util

fun isEmailValid(email: CharSequence): Boolean {
    return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
}

fun isTextValid(minLength: Int, text: String?): Boolean {
    return !(text.isNullOrBlank() || text.length < minLength)
}

fun String.trimPhoneNumber () : String =
    this.removePrefix("+972").removePrefix("0").replace("-", "").trim()

