package checks.access.domain

final case class Admin(name: String)

final case class Session(token: String, admin: Admin, expiresAtMillis: Long)

// Who the identity provider says signed in: the name they go by there, and their groups.
final case class Signed(name: String, groups: Set[String])

// A sign-in sent to the provider and not yet back: what its answer is checked against, once.
final case class Pending(state: String, verifier: String, nonce: String, returnTo: String, expiresAtMillis: Long)

enum AccessError(msg: String) extends RuntimeException(msg):
  case NotAnAdmin(name: String)       extends AccessError(s"$name is in no group that may change the policy")
  case BadSignIn(why: String)         extends AccessError(s"that sign-in did not verify: $why")
  case NoSession                      extends AccessError("not signed in, or the session has ended")
  case ProviderDown(cause: Throwable) extends AccessError(s"the identity provider did not answer: ${cause.getMessage}")
  case Unavailable(cause: Throwable)  extends AccessError(s"sessions could not be read: ${cause.getMessage}")
