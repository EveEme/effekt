package effekt
package regions

import effekt.source._
import effekt.context.{ Annotations, Context, ContextOps }
import effekt.symbols.{ BlockSymbol, Symbol, ValueSymbol, Effectful }

import effekt.context.assertions.SymbolAssertions

class RegionChecker extends Phase[ModuleDecl, ModuleDecl] {

  val phaseName = "region-checker"

  def run(input: ModuleDecl)(implicit C: Context): Option[ModuleDecl] = {
    Context.initRegionstate()
    Context.unifyAndSubstitute()

    // this should go into a precheck method
    input.defs.foreach {
      case f: FunDef =>
        Context.annotateRegions(f.symbol, C.staticRegion)
      case f: ExternFun =>
        Context.annotateRegions(f.symbol, Region.empty)
      case d: DataDef =>
        d.ctors.foreach { c =>
          Context.annotateRegions(c.symbol, Region.empty)
        }
      case d: RecordDef =>
        val sym = d.symbol
        Context.annotateRegions(sym, Region.empty)
        sym.fields.foreach { f =>
          Context.annotateRegions(f, Region.empty)
        }
      case _ => ()
    }
    check(input)
    Some(input)
  }

  // A traversal with the side effect to annotate all functions with their region
  // it returns a _concrete_ region set, all region variables have to be resolved
  // at that point.
  def checkTree(implicit C: Context): PartialFunction[Tree, RegionSet] = {

    case FunDef(id, tparams, params, ret, body) =>
      val sym = id.symbol

      // Since this function might be (mutally) recursive, annotate it with the current region
      // before checking.
      //
      // This is a conservative approximation that can be refined, later, potentially
      // by using region variables and collecting constraints.
      Context.annotateRegions(sym, C.staticRegion)

      // regions of parameters introduced by this function
      val boundRegions: RegionSet = bindRegions(params)

      val selfRegion = Region(sym)
      val bodyRegion = Context.inDynamicRegion(selfRegion) { check(body) }
      val reg = bodyRegion -- boundRegions -- selfRegion

      // check that the self region (used by resume and variables) does not escape the scope
      val tpe = Context.blockTypeOf(sym)
      val escapes = freeRegionVariables(tpe.ret) intersect selfRegion
      if (escapes.nonEmpty) {
        explain(sym, body).map(_.report)
        Context.abort(s"A value that is introduced in '${id.name}' leaves its scope.")
      }

      // safe inferred region on the function symbol
      Context.annotateRegions(id.symbol, reg)
      reg

    case l @ Lambda(id, params, body) =>
      val sym = l.symbol
      // annotated by typer
      val expected = Context.regionOf(sym)
      val boundRegions: RegionSet = bindRegions(params)

      val selfRegion = Region(sym)
      val bodyRegion = Context.inDynamicRegion(selfRegion) { check(body) }

      val inferredReg = bodyRegion -- boundRegions -- selfRegion

      // check that expected >: inferredReg
      val reg = expected.withRegion { allowed =>
        if (!inferredReg.subsetOf(allowed)) {
          Context.abort(s"Region not allowed here: ${inferredReg}")
        }
        allowed
      }.getOrElse { inferredReg }

      // check that the self region does not escape as part of the lambdas type
      val tpe = Context.blockTypeOf(sym)
      val escapes = freeRegionVariables(tpe.ret) intersect selfRegion
      if (escapes.nonEmpty) {
        explain(sym, body).map(_.report)
        Context.abort(s"A value that is introduced in this lambda leaves its scope.")
      }

      // safe inferred region on the function symbol
      Context.annotateRegions(sym, reg)

      // if expected was a variable, instantiate it.
      if (!expected.isInstantiated) {
        Context.instantiate(expected.asRegionVar, reg)
      }
      reg

    case BlockArg(params, body) =>
      val boundRegions: RegionSet = bindRegions(params)
      val bodyRegion = check(body)
      bodyRegion -- boundRegions

    case TryHandle(body, handlers) =>

      // regions for all the capabilities
      val caps = handlers.flatMap { h => h.capability }
      val boundRegions = bindRegions(caps)

      val bodyRegion = Context.inRegion(boundRegions) { check(body) }

      var reg = bodyRegion -- boundRegions

      // check that boundRegions do not escape as part of an inferred type
      val Effectful(tpe, _) = C.inferredTypeOf(body)

      val escapes = freeRegionVariables(tpe) intersect boundRegions
      if (escapes.nonEmpty) {
        val traces = escapes.regions.toList.map { sym =>
          TraceItem(s"The return type mentions capability ${sym}", body, explain(sym, body))
        }
        traces.foreach(_.report)
        Context.abort(s"The value returned from this handler has type ${tpe}. \nAs part of this type, the following capabilities leave their defining scope ${escapes}.")
      }

      handlers.foreach {
        case Handler(id, cap, clauses) => clauses.foreach {
          case OpClause(id, params, body, resumeId) =>
            val resumeSym = resumeId.symbol
            val resumeReg = Context.dynamicRegion
            Context.annotateRegions(resumeSym, resumeReg)
            reg ++= check(body)
        }
      }
      reg

    // capability call
    case MemberTarget(cap, op) =>
      Context.regionOf(cap.symbol).asRegionSet

    case tgt @ IdTarget(id) => id.symbol match {
      case b: BlockSymbol =>
        Context.regionOf(b).asRegionSet
      case t: ValueSymbol =>
        val symbols.FunType(tpe, reg) = Context.valueTypeOf(t)
        reg.asRegionSet
    }

    case ExprTarget(e) =>
      val reg = check(e)
      val Effectful(symbols.FunType(tpe, funReg), _) = Context.inferredTypeOf(e)
      reg ++ funReg.asRegionSet

    case VarDef(id, _, binding) =>
      Context.annotateRegions(id.symbol, Context.dynamicRegion)
      val reg = check(binding)
      // associate the mutable variable binding with the current scope
      reg

    case Var(id) if id.symbol.isInstanceOf[symbols.VarBinder] =>
      Context.regionOf(id.symbol).asRegionSet

    case Assign(id, expr) =>
      val res = check(expr) ++ Context.regionOf(id.symbol).asRegionSet
      res

    // TODO eventually we want to change the representation of block types to admit region polymorphism
    // everywhere where blocktypes are allowed. For now, we only support region polymorphism on known functions.
    // This restriction is fine, since we do only allow second-order blocks anyway.

    // calls to known functions

    // TODO check: resumptions can take block arguments (with bidirectional effects), but are not "known functions"
    case c @ Call(id: IdTarget, _, args) if id.definition.isInstanceOf[symbols.Fun] =>
      var reg = check(id)
      val fun = id.definition.asFun
      val Effectful(tpe, _) = Context.inferredTypeOf(c)

      (fun.params zip args).foreach {
        case (param, arg: ValueArgs) => reg ++= check(args)
        case (List(param: symbols.BlockParam), arg: BlockArg) =>
          val argReg = check(arg)
          reg ++= argReg

          // here we substitute the inferred region for the block parameter in the return type.
          substitute(param, argReg, tpe)
        case (param, arg: CapabilityArg) => ()
      }
      // check constraints again after substitution
      C.unifyAndSubstitute()

      reg

    // calls to unknown functions (block arguments, lambdas, etc.)
    case c @ Call(target, _, args) =>
      val Effectful(tpe, _) = Context.inferredTypeOf(c)
      args.foldLeft(check(target)) { case (reg, arg) => reg ++ check(arg) }
  }

  /**
   * When a region error occurs, explanations are gathered in form of a trace
   */
  case class TraceItem(msg: String, tree: Tree, subtrace: Trace = Nil) {

    def report(implicit C: Context): Unit = {
      C.info(tree, msg)
      subtrace.foreach { t => t.report }
    }

    def render(implicit C: Context): String = {
      val pos = C.positions.getStart(tree).map { p => s"(line ${p.line}) " }.getOrElse("")
      val sub = if (subtrace.isEmpty) "" else {
        "\n" + subtrace.map(_.render).mkString("\n").linesIterator.map("  " + _).mkString("\n")
      }
      s"- ${pos}${msg}${sub}"
    }
  }
  type Trace = List[TraceItem]
  def explainEscape(reg: Symbol)(implicit C: Context): PartialFunction[Tree, Trace] = {
    case f @ FunDef(id, tparams, params, ret, body) if uses(f, reg) =>
      TraceItem(s"Function '${id.name}' closes over '$reg'", body, explain(reg, body)) :: Nil

    case l @ Lambda(id, params, body) if uses(l, reg) =>
      TraceItem(s"The lambda closes over '$reg'", l, explain(reg, body)) :: Nil

    case m @ MemberTarget(cap, op) if C.symbolOf(cap) == reg =>
      TraceItem(s"The problematic effect is used here", m) :: Nil

    case tgt @ IdTarget(id) if uses(tgt, reg) =>
      TraceItem(s"Function ${id.name} is called, which closes over '${reg}'", tgt) :: Nil

    case v @ Return(e) =>
      val Effectful(tpe, _) = Context.inferredTypeOf(e)
      if (freeRegionVariables(tpe).contains(reg)) {
        TraceItem(s"A value is returned that mentions '${reg}' in its inferred type ($tpe)", e, explain(reg, e)) :: Nil
      } else {
        explain(reg, e)
      }
  }

  def render(l: List[TraceItem])(implicit C: Context): String =
    if (l.isEmpty) "" else s"\n\nExplanation:\n------------\n${l.map(_.render).mkString("\n")}"

  def explain(escapingRegion: Symbol, obj: Any)(implicit C: Context): Trace = obj match {
    case _: Symbol | _: String => Nil
    case t: Tree =>
      C.at(t) {
        if (explainEscape(escapingRegion).isDefinedAt(t)) {
          explainEscape(escapingRegion)(C)(t)
        } else if (C.inferredRegionOption(t).exists(_.contains(escapingRegion))) {
          t.productIterator.foldLeft(Nil: Trace) { case (r, t) => r ++ explain(escapingRegion, t) }
        } else {
          Nil
        }
      }
    case p: Product =>
      p.productIterator.foldLeft(Nil: Trace) { case (r, t) => r ++ explain(escapingRegion, t) }
    case t: Iterable[t] =>
      t.foldLeft(Nil: Trace) { case (r, t) => r ++ explain(escapingRegion, t) }
    case leaf =>
      Nil
  }

  def uses(t: Tree, reg: Symbol)(implicit C: Context): Boolean =
    C.inferredRegion(t).contains(reg)

  def bindRegions(params: List[ParamSection])(implicit C: Context): RegionSet = {
    var regs: RegionSet = Region.empty
    params.foreach {
      case b: BlockParam =>
        val sym = b.symbol
        val reg = Region(sym)
        Context.annotateRegions(sym, reg)
        regs ++= reg
      case b: CapabilityParam =>
        val sym = b.symbol
        val reg = Region(sym)
        Context.annotateRegions(sym, reg)
        regs ++= reg
      case v: ValueParams => ()
    }
    regs
  }

  def check(obj: Any)(implicit C: Context): RegionSet = obj match {
    case _: Symbol | _: String => Region.empty
    case t: Tree =>
      C.at(t) {
        val reg = if (checkTree.isDefinedAt(t)) {
          checkTree(C)(t)
        } else {
          t.productIterator.foldLeft(Region.empty) { case (r, t) => r ++ check(t) }
        }
        C.annotateRegion(t, reg)
        reg
      }
    case p: Product =>
      p.productIterator.foldLeft(Region.empty) { case (r, t) => r ++ check(t) }
    case t: Iterable[t] =>
      t.foldLeft(Region.empty) { case (r, t) => r ++ check(t) }
    case leaf =>
      Region.empty
  }

  /**
   * A generic traversal to collects all free region variables
   */
  def freeRegionVariables(o: Any)(implicit C: Context): RegionSet = o match {
    case _: Symbol | _: String => Region.empty // don't follow symbols
    case symbols.FunType(tpe, reg) =>
      freeRegionVariables(tpe) ++ reg.asRegionSet
    case t: Iterable[t] =>
      t.foldLeft(Region.empty) { case (r, t) => r ++ freeRegionVariables(t) }
    case p: Product =>
      p.productIterator.foldLeft(Region.empty) { case (r, t) => r ++ freeRegionVariables(t) }
    case _ =>
      Region.empty
  }

  def substitute(x: Symbol, r: RegionSet, o: Any)(implicit C: Context): Unit = o match {
    case _: Symbol | _: String => ()
    case y: RegionVar =>
      val reg = y.asRegionSet.substitute(x, r)
      y.instantiate(reg)
    case t: Iterable[t] =>
      t.foreach { t => substitute(x, r, t) }
    case p: Product =>
      p.productIterator.foreach { t => substitute(x, r, t) }
    case _ =>
      ()
  }
}

trait RegionCheckerOps extends ContextOps { self: Context =>

  // the current lexical region
  private[regions] var staticRegion: RegionSet = Region.empty

  // the current dynamical region (as approximated by the owner handler / lambda / function symbol )
  // only used for continuations!
  private[regions] var dynamicRegion: RegionSet = Region.empty

  private[regions] var constraints: List[RegionEq] = Nil

  private[regions] def initRegionstate(): Unit = {
    staticRegion = Region.empty
    constraints = annotation(Annotations.Unifier, module).constraints.toList
  }

  private[regions] def inRegion[T](r: RegionSet)(block: => T): T = {
    val staticBefore = staticRegion
    val dynamicBefore = dynamicRegion
    staticRegion = r
    dynamicRegion = r
    val res = block
    staticRegion = staticBefore
    dynamicRegion = dynamicBefore
    res
  }

  private[regions] def inDynamicRegion[T](r: RegionSet)(block: => T): T = {
    val dynamicBefore = dynamicRegion
    dynamicRegion = r
    val res = block
    dynamicRegion = dynamicBefore
    res
  }

  private[regions] def instantiate(x: RegionVar, r: RegionSet): Unit = {
    x.instantiate(r)
    unifyAndSubstitute()
  }

  private[regions] def annotateRegion(t: Tree, r: RegionSet): Unit =
    annotate(Annotations.InferredRegion, t, r)

  private[regions] def unifyAndSubstitute(): Unit = unifyAndSubstitute(constraints) match {
    case Left(RegionEq(RegionSet(x), RegionSet(y))) =>
      abort(s"Region mismatch: $x is not equal to $y")
    case Right(cs) => constraints = cs
  }

  /**
   * If unification is successful returns a new list of remaining constraints: Right(List[RegionEq])
   * If unification fails, returns the conflicting constraint.
   */
  private def unifyAndSubstitute(cs: List[RegionEq]): Either[RegionEq, List[RegionEq]] = cs.distinct match {

    // if both are instantiated -> compare their sets
    case (r @ RegionEq(RegionSet(x), RegionSet(y))) :: rest =>
      if (x != y) {
        Left(r)
      } else {
        unifyAndSubstitute(rest)
      }

    // if one is a variable -> instantiate
    case RegionEq(x: RegionVar, RegionSet(r)) :: rest =>
      x.instantiate(r);
      unifyAndSubstitute(rest)

    // if one is a variable -> instantiate
    case RegionEq(RegionSet(r), x: RegionVar) :: rest =>
      x.instantiate(r);
      unifyAndSubstitute(rest)

    // if both are variables -> keep constraint
    case RegionEq(x: RegionVar, y: RegionVar) :: rest =>
      unifyAndSubstitute(rest).map(RegionEq(x, y) :: _)

    case Nil => Right(Nil)
  }
}
