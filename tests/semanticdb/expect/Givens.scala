package a
package b

object Givens:

  extension [A](any: A)
    def sayHello = s"Hello, I am $any"

  extension [B](any: B)
    def sayGoodbye = s"Goodbye, from $any"
    def saySoLong = s"So Long, from $any"

  val hello1 = 1.sayHello
  val goodbye1 = 1.sayGoodbye
  val soLong1 = 1.saySoLong

  trait Monoid[A]:
    def empty: A
    extension (x: A) def combine(y: A): A

  given Monoid[String]:
    def empty = ""
    extension (x: String) def combine(y: String) = x + y

  inline given int2String as Conversion[Int, String] = _.toString

  def foo[A](using A: Monoid[A]): A = A.extension_combine(A.empty)(A.empty)
