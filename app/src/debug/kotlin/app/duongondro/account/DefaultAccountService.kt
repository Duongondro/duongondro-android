package app.duongondro.account

/** Debug builds walk the whole flow against [FakeAccountService]. */
fun defaultAccountService(): AccountService = FakeAccountService()
