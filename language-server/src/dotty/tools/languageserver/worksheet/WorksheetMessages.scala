package dotty.tools.languageserver.worksheet

import java.net.URI

/** The parameter for the `worksheet/exec` request. */
case class WorksheetExecParams(uri: URI) {
  // Used for deserialization
  // see https://github.com/lampepfl/dotty/pull/5102#discussion_r222055355
  def this() = this(null)
}

/** The response to a `worksheet/exec` request. */
case class WorksheetExecResponse(success: Boolean) {
  // Used for deserialization
  // see https://github.com/lampepfl/dotty/pull/5102#discussion_r222055355
  def this() = this(false)
}

/**
 * A notification that tells the client that a line of a worksheet
 * produced the specified output.
 */
case class WorksheetExecOutput(uri: URI, line: Int, content: String) {
  // Used for deserialization
  // see https://github.com/lampepfl/dotty/pull/5102#discussion_r222055355
  def this() = this(null, 0, null)
}
