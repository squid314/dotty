package dotty.tools.dotc
package transform

import core._
import Contexts._, Symbols._, Types._, Constants._, StdNames._, Decorators._
import ast.Trees._
import ast.untpd
import Erasure.Boxing._
import TypeErasure._
import ValueClasses._
import SymUtils._
import core.Flags._
import util.Spans._
import reporting._
import config.Printers.{ transforms => debug }

/** This transform normalizes type tests and type casts,
 *  also replacing type tests with singleton argument type with reference equality check
 *  Any remaining type tests
 *   - use the object methods $isInstanceOf and $asInstanceOf
 *   - have a reference type as receiver
 *   - can be translated directly to machine instructions
 *
 * Unfortunately this phase ended up being not Y-checkable unless types are erased. A cast to an ConstantType(3) or x.type
 * cannot be rewritten before erasure. That's why TypeTestsCasts is called from Erasure.
 */
object TypeTestsCasts {
  import ast.tpd._
  import typer.Inferencing.maximizeType
  import typer.ProtoTypes.constrained

  /** Whether `(x:X).isInstanceOf[P]` can be checked at runtime?
   *
   *  First do the following substitution:
   *  (a) replace `T @unchecked` and pattern binder types (e.g., `_$1`) in P with WildcardType
   *  (b) replace pattern binder types (e.g., `_$1`) in X:
   *      - variance = 1  : hiBound
   *      - variance = -1 : loBound
   *      - variance = 0  : OrType(Any, Nothing) // TODO: use original type param bounds
   *
   *  Then check:
   *
   *  1. if `X <:< P`, TRUE
   *  2. if `P` is a singleton type, TRUE
   *  3. if `P` refers to an abstract type member or type parameter, FALSE
   *  4. if `P = Array[T]`, checkable(E, T) where `E` is the element type of `X`, defaults to `Any`.
   *  5. if `P` is `pre.F[Ts]` and `pre.F` refers to a class which is not `Array`:
   *     (a) replace `Ts` with fresh type variables `Xs`
   *     (b) constrain `Xs` with `pre.F[Xs] <:< X`
   *     (c) maximize `pre.F[Xs]` and check `pre.F[Xs] <:< P`
   *  6. if `P = T1 | T2` or `P = T1 & T2`, checkable(X, T1) && checkable(X, T2).
   *  7. if `P` is a refinement type, FALSE
   *  8. otherwise, TRUE
   */
  def checkable(X: Type, P: Type, span: Span)(using Context): Boolean = {
    def isAbstract(P: Type) = !P.dealias.typeSymbol.isClass

    def replaceP(tp: Type)(using Context) = new TypeMap {
      def apply(tp: Type) = tp match {
        case tref: TypeRef if tref.typeSymbol.isPatternBound =>
          WildcardType
        case AnnotatedType(_, annot) if annot.symbol == defn.UncheckedAnnot =>
          WildcardType
        case _ => mapOver(tp)
      }
    }.apply(tp)

    def replaceX(tp: Type)(using Context) = new TypeMap {
      def apply(tp: Type) = tp match {
        case tref: TypeRef if tref.typeSymbol.isPatternBound =>
          if (variance == 1) tref.info.hiBound
          else if (variance == -1) tref.info.loBound
          else OrType(defn.AnyType, defn.NothingType)
        case _ => mapOver(tp)
      }
    }.apply(tp)

    /** Approximate type parameters depending on variance */
    def stripTypeParam(tp: Type)(using Context) = new ApproximatingTypeMap {
      def apply(tp: Type): Type = tp match {
        case _: MatchType =>
          tp // break cycles
        case tp: TypeRef if isBounds(tp.underlying) =>
          val lo = apply(tp.info.loBound)
          val hi = apply(tp.info.hiBound)
          range(lo, hi)
        case _ =>
          mapOver(tp)
      }
    }.apply(tp)

    /** Returns true if the type arguments of `P` can be determined from `X` */
    def typeArgsTrivial(X: Type, P: AppliedType)(using Context) = inContext(ctx.fresh.setExploreTyperState().setFreshGADTBounds) {
      val AppliedType(tycon, _) = P

      def underlyingLambda(tp: Type): TypeLambda = tp.ensureLambdaSub match {
        case tp: TypeLambda => tp
        case tp: TypeProxy => underlyingLambda(tp.superType)
      }
      val typeLambda = underlyingLambda(tycon)
      val tvars = constrained(typeLambda, untpd.EmptyTree, alwaysAddTypeVars = true)._2.map(_.tpe)
      val P1 = tycon.appliedTo(tvars)

      debug.println("P : " + P.show)
      debug.println("P1 : " + P1.show)
      debug.println("X : " + X.show)

      // It does not matter if P1 is not a subtype of X.
      // It just tries to infer type arguments of P1 from X if the value x
      // conforms to the type skeleton pre.F[_]. Then it goes on to check
      // if P1 <: P, which means the type arguments in P are trivial,
      // thus no runtime checks are needed for them.
      P1 <:< X

      // Maximization of the type means we try to cover all possible values
      // which conform to the skeleton pre.F[_] and X. Then we have to make
      // sure all of them are actually of the type P, which implies that the
      // type arguments in P are trivial (no runtime check needed).
      maximizeType(P1, span, fromScala2x = false)

      val res = P1 <:< P

      debug.println(TypeComparer.explained(P1 <:< P))

      debug.println("P1 : " + P1.show)
      debug.println("P1 <:< P = " + res)

      res
    }

    def recur(X: Type, P: Type): Boolean = (X <:< P) || (P.dealias match {
      case _: SingletonType     => true
      case _: TypeProxy
      if isAbstract(P)          => false
      case defn.ArrayOf(tpT)    =>
        X match {
          case defn.ArrayOf(tpE)   => recur(tpE, tpT)
          case _                   => recur(defn.AnyType, tpT)
        }
      case tpe: AppliedType     =>
        X.widen match {
          case OrType(tp1, tp2) =>
            // This case is required to retrofit type inference,
            // which cut constraints in the following two cases:
            //   - T1 <:< T2 | T3
            //   - T1 & T2 <:< T3
            // See TypeComparer#either
            recur(tp1, P) && recur(tp2, P)
          case _ =>
            // always false test warnings are emitted elsewhere
            X.classSymbol.exists && P.classSymbol.exists &&
              !X.classSymbol.asClass.mayHaveCommonChild(P.classSymbol.asClass) ||
            // first try without striping type parameters for performance
            typeArgsTrivial(X, tpe) ||
            typeArgsTrivial(stripTypeParam(X), tpe)
        }
      case AndType(tp1, tp2)    => recur(X, tp1) && recur(X, tp2)
      case OrType(tp1, tp2)     => recur(X, tp1) && recur(X, tp2)
      case AnnotatedType(t, _)  => recur(X, t)
      case _: RefinedType       => false
      case _                    => true
    })

    val res = recur(replaceX(X.widen), replaceP(P))

    debug.println(i"checking  ${X.show} isInstanceOf ${P} = $res")

    res
  }

  def interceptTypeApply(tree: TypeApply)(using Context): Tree = trace(s"transforming ${tree.show}", show = true) {
    /** Intercept `expr.xyz[XYZ]` */
    def interceptWith(expr: Tree): Tree =
      if (expr.isEmpty) tree
      else {
        val sym = tree.symbol

        def isPrimitive(tp: Type) = tp.classSymbol.isPrimitiveValueClass

        def derivedTree(expr1: Tree, sym: Symbol, tp: Type) =
          cpy.TypeApply(tree)(expr1.select(sym).withSpan(expr.span), List(TypeTree(tp)))

        def effectiveClass(tp: Type): Symbol =
          if tp.isRef(defn.PairClass) then effectiveClass(erasure(tp))
          else if tp.isRef(defn.AnyValClass) then defn.AnyClass
          else tp.classSymbol

        def foundClasses(tp: Type, acc: List[Symbol]): List[Symbol] = tp match
          case OrType(tp1, tp2) => foundClasses(tp2, foundClasses(tp1, acc))
          case _ => effectiveClass(tp) :: acc

        def inMatch =
          tree.fun.symbol == defn.Any_typeTest ||  // new scheme
          expr.symbol.is(Case)                // old scheme

        def transformIsInstanceOf(expr: Tree, testType: Type, flagUnrelated: Boolean): Tree = {
          def testCls = effectiveClass(testType.widen)

          def unreachable(why: => String)(using Context): Boolean = {
            if (flagUnrelated)
              if (inMatch) report.error(em"this case is unreachable since $why", expr.srcPos)
              else report.warning(em"this will always yield false since $why", expr.srcPos)
            false
          }

          /** Are `foundCls` and `testCls` classes that allow checks
           *  whether a test would be always false?
           */
          def isCheckable(foundCls: Symbol) =
            foundCls.isClass && testCls.isClass &&
            !(testCls.isPrimitiveValueClass && !foundCls.isPrimitiveValueClass) &&
               // if `test` is primitive but `found` is not, we might have a case like
               // found = java.lang.Integer, test = Int, which could be true
               // (not sure why that is so, but scalac behaves the same way)
            !(!testCls.isPrimitiveValueClass && foundCls.isPrimitiveValueClass) &&
               // foundCls can be `Boolean`, while testCls is `Integer`
               // it can happen in `(3: Boolean | Int).isInstanceOf[Int]`
            !isDerivedValueClass(foundCls) && !isDerivedValueClass(testCls)
               // we don't have the logic to handle derived value classes

          /** Check whether a runtime test that a value of `foundCls` can be a `testCls`
           *  can be true in some cases. Issues a warning or an error otherwise.
           */
          def checkSensical(foundClasses: List[Symbol])(using Context): Boolean =
            def exprType = i"type ${expr.tpe.widen.stripAnnots}"
            def check(foundCls: Symbol): Boolean =
              if (!isCheckable(foundCls)) true
              else if (!foundCls.derivesFrom(testCls)) {
                val unrelated = !testCls.derivesFrom(foundCls) && (
                  testCls.is(Final) || !testCls.is(Trait) && !foundCls.is(Trait)
                )
                if (foundCls.is(Final))
                  unreachable(i"$exprType is not a subclass of $testCls")
                else if (unrelated)
                  unreachable(i"$exprType and $testCls are unrelated")
                else true
              }
              else true
            end check

            val foundEffectiveClass = effectiveClass(expr.tpe.widen)

            if foundEffectiveClass.isPrimitiveValueClass && !testCls.isPrimitiveValueClass then
              report.error(i"cannot test if value of $exprType is a reference of $testCls", tree.srcPos)
              false
            else foundClasses.exists(check)
          end checkSensical

          if (expr.tpe <:< testType)
            if (expr.tpe.isNotNull) {
              if (!inMatch) report.warning(TypeTestAlwaysSucceeds(expr.tpe, testType), tree.srcPos)
              constant(expr, Literal(Constant(true)))
            }
            else expr.testNotNull
          else {
            val nestedCtx = ctx.fresh.setNewTyperState()
            val foundClsSyms = foundClasses(expr.tpe.widen, Nil)
            val sensical = checkSensical(foundClsSyms)(using nestedCtx)
            if (!sensical) {
              nestedCtx.typerState.commit()
              constant(expr, Literal(Constant(false)))
            }
            else if (testCls.isPrimitiveValueClass)
              foundClsSyms match
                case List(cls) if cls.isPrimitiveValueClass =>
                  constant(expr, Literal(Constant(foundClsSyms.head == testCls)))
                case _ =>
                  transformIsInstanceOf(expr, defn.boxedType(testCls.typeRef), flagUnrelated)
            else
              derivedTree(expr, defn.Any_isInstanceOf, testType)
          }
        }

        def transformAsInstanceOf(testType: Type): Tree = {
          def testCls = effectiveClass(testType.widen)
          def foundClsSymPrimitive = {
            val foundClsSyms = foundClasses(expr.tpe.widen, Nil)
            foundClsSyms.size == 1 && foundClsSyms.head.isPrimitiveValueClass
          }
          if (erasure(expr.tpe) <:< testType)
            Typed(expr, tree.args.head) // Replace cast by type ascription (which does not generate any bytecode)
          else if (testCls eq defn.BoxedUnitClass)
            // as a special case, casting to Unit always successfully returns Unit
            Block(expr :: Nil, Literal(Constant(()))).withSpan(expr.span)
          else if (foundClsSymPrimitive)
            if (testCls.isPrimitiveValueClass) primitiveConversion(expr, testCls)
            else derivedTree(box(expr), defn.Any_asInstanceOf, testType)
          else if (testCls.isPrimitiveValueClass)
            unbox(expr.ensureConforms(defn.ObjectType), testType)
          else if (isDerivedValueClass(testCls))
            expr // adaptToType in Erasure will do the necessary type adaptation
          else if (testCls eq defn.NothingClass) {
            // In the JVM `x.asInstanceOf[Nothing]` would throw a class cast exception except when `x eq null`.
            // To avoid this loophole we execute `x` and then regardless of the result throw a `ClassCastException`
            val throwCCE = Throw(New(defn.ClassCastExceptionClass.typeRef, defn.ClassCastExceptionClass_stringConstructor,
                Literal(Constant("Cannot cast to scala.Nothing")) :: Nil))
            Block(expr :: Nil, throwCCE).withSpan(expr.span)
          }
          else
            derivedTree(expr, defn.Any_asInstanceOf, testType)
        }

        /** Transform isInstanceOf OrType
         *
         *    expr.isInstanceOf[A | B]  ~~>  expr.isInstanceOf[A] | expr.isInstanceOf[B]
         *    expr.isInstanceOf[A & B]  ~~>  expr.isInstanceOf[A] & expr.isInstanceOf[B]
         *    expr.isInstanceOf[Tuple]          ~~>  scala.runtime.Tuple.isInstanceOfTuple(expr)
         *    expr.isInstanceOf[EmptyTuple]     ~~>  scala.runtime.Tuple.isInstanceOfEmptyTuple(expr)
         *    expr.isInstanceOf[NonEmptyTuple]  ~~>  scala.runtime.Tuple.isInstanceOfNonEmptyTuple(expr)
         *    expr.isInstanceOf[*:[_, _]]       ~~>  scala.runtime.Tuple.isInstanceOfNonEmptyTuple(expr)
         *
         *  The transform happens before erasure of `testType`, thus cannot be merged
         *  with `transformIsInstanceOf`, which depends on erased type of `testType`.
         */
        def transformTypeTest(expr: Tree, testType: Type, flagUnrelated: Boolean): Tree = testType.dealias match {
          case tref: TermRef if tref.symbol == defn.EmptyTupleModule =>
            ref(defn.RuntimeTuple_isInstanceOfEmptyTuple).appliedTo(expr)
          case _: SingletonType =>
            expr.isInstance(testType).withSpan(tree.span)
          case OrType(tp1, tp2) =>
            evalOnce(expr) { e =>
              transformTypeTest(e, tp1, flagUnrelated = false)
                .or(transformTypeTest(e, tp2, flagUnrelated = false))
            }
          case AndType(tp1, tp2) =>
            evalOnce(expr) { e =>
              transformTypeTest(e, tp1, flagUnrelated)
                .and(transformTypeTest(e, tp2, flagUnrelated))
            }
          case defn.MultiArrayOf(elem, ndims) if isUnboundedGeneric(elem) =>
            def isArrayTest(arg: Tree) =
              ref(defn.runtimeMethodRef(nme.isArray)).appliedTo(arg, Literal(Constant(ndims)))
            if (ndims == 1) isArrayTest(expr)
            else evalOnce(expr) { e =>
              derivedTree(e, defn.Any_isInstanceOf, e.tpe)
                .and(isArrayTest(e))
            }
          case tref: TypeRef if tref.symbol == defn.TupleClass =>
            ref(defn.RuntimeTuple_isInstanceOfTuple).appliedTo(expr)
          case tref: TypeRef if tref.symbol == defn.NonEmptyTupleClass =>
            ref(defn.RuntimeTuple_isInstanceOfNonEmptyTuple).appliedTo(expr)
          case AppliedType(tref: TypeRef, _) if tref.symbol == defn.PairClass =>
            ref(defn.RuntimeTuple_isInstanceOfNonEmptyTuple).appliedTo(expr)
          case _ =>
            transformIsInstanceOf(expr, erasure(testType), flagUnrelated)
        }

        if (sym.isTypeTest) {
          val argType = tree.args.head.tpe
          val isTrusted = tree.hasAttachment(PatternMatcher.TrustedTypeTestKey)
          if (!isTrusted && !checkable(expr.tpe, argType, tree.span))
            report.warning(i"the type test for $argType cannot be checked at runtime", tree.srcPos)
          transformTypeTest(expr, tree.args.head.tpe, flagUnrelated = true)
        }
        else if (sym.isTypeCast)
          transformAsInstanceOf(erasure(tree.args.head.tpe))
        else tree
      }
    val expr = tree.fun match {
      case Select(expr, _) => expr
      case i: Ident =>
        val expr = desugarIdentPrefix(i)
        if (expr.isEmpty) expr
        else expr.withSpan(i.span)
      case _ => EmptyTree
    }
    interceptWith(expr)
  }
}
