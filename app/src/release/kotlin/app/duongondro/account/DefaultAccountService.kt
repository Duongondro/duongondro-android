package app.duongondro.account

/** Release builds never link the fake; until the network layer lands, account calls fail honestly. */
fun defaultAccountService(): AccountService = UnavailableAccountService
