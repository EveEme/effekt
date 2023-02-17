package effekt

import effekt.PhaseResult.CoreLifted
import effekt.context.Context
import effekt.core.{ DirectStyleMutableState, Transformer }
import effekt.lifted.LiftInference
import effekt.namer.Namer
import effekt.source.{ Elaborator, ModuleDecl }
import effekt.symbols.Module
import effekt.typer.{ PostTyper, PreTyper, Typer }
import effekt.util.messages.FatalPhaseError
import effekt.util.{ SourceTask, Task, VirtualSource, paths }
import effekt.generator.js.JavaScript
import kiama.output.PrettyPrinterTypes.Document
import kiama.util.{ Positions, Source }

/**
 * Intermediate results produced by the various phases.
 *
 * All phases have a source field, which is mostly used to invalidate caches based on the timestamp.
 */
enum PhaseResult {

  val source: Source

  /**
   * The result of [[Parser]] parsing a single file into a [[effekt.source.Tree]].
   */
  case Parsed(source: Source, tree: ModuleDecl)

  /**
   * The result of [[Namer]] resolving all names in a given syntax tree. The resolved symbols are
   * annotated in the [[Context]] using [[effekt.context.Annotations]].
   */
  case NameResolved(source: Source, tree: ModuleDecl, mod: symbols.Module)

  /**
   * The result of [[Typer]] type checking a given syntax tree.
   *
   * We can notice that [[NameResolved]] and [[Typechecked]] haave the same fields.
   * Like, [[Namer]], [[Typer]] writes to the types of each tree into the DB, using [[effekt.context.Annotations]].
   * This might change in the future, when we switch to elaboration.
   */
  case Typechecked(source: Source, tree: ModuleDecl, mod: symbols.Module)

  /**
   * The result of [[Transformer]] ANF transforming [[source.Tree]] into the core representation [[core.Tree]].
   */
  case CoreTransformed(source: Source, tree: ModuleDecl, mod: symbols.Module, core: effekt.core.ModuleDecl)

  /**
   * The result of running the [[Compiler.Middleend]] on all dependencies.
   */
  case AllTransformed(source: Source, main: PhaseResult.CoreTransformed, dependencies: List[PhaseResult.CoreTransformed])

  /**
   * The result of [[LiftInference]] transforming [[core.Tree]] into the lifted core representation [[lifted.Tree]].
   */
  case CoreLifted(source: Source, tree: ModuleDecl, mod: symbols.Module, core: effekt.lifted.ModuleDecl)

  /**
   * The result of [[effekt.generator.Backend]], consisting of a mapping from filename to output to be written.
   */
  case Compiled(source: Source, mainFile: String, outputFiles: Map[String, Document])
}
export PhaseResult.*

/**
 * The compiler for the Effekt language.
 *
 * The compiler is set up in the following large phases that consist itself of potentially multiple phases
 *
 *   1. Parser    (Source      -> source.Tree)  Load file and parse it into an AST
 *
 *   2. Frontend  (source.Tree -> source.Tree)  Perform name analysis, typechecking, region inference,
 *                                             and other rewriting of the source AST
 *
 *   3. Middleend (source.Tree -> core.Tree)    Perform an ANF transformation into core, and
 *                                             other rewritings on the core AST
 *
 *   4. Backend  (core.Tree   -> Document)     Generate code in a target language
 *
 * The compiler itself does not read from or write to files. This is important since, we need to
 * virtualize the file system to also run the compiler in the browser.
 *
 * - Reading files is performed by the mixin [[effekt.context.ModuleDB]], which is implemented
 *   differently for the JS and JVM versions.
 * - Writing to files is performed by the hook [[Compiler.saveOutput]], which is implemented
 *   differently for the JS and JVM versions.
 */
trait Compiler[Executable] { self: BackendCompiler[Executable] =>

  /**
   * @note The result of parsing needs to be cached.
   *
   *       [[Intelligence]] uses both the results of [[getAST]] and [[runFrontend]].
   *       Since we associate trees and symbols by the *object identity* of the tree,
   *       running parser multiple times on the same input results in different trees.
   *       In consequence, the symbols can't be found anymore. To avoid this, we
   *       use a separate task for parsing.
   *
   *       Having access to the parse trees separately is also helpful for programs
   *       that fail in later phases (for instance type checking). This way some
   *       editor services can be provided, even in presence of errors.
   */
  val CachedParser = Phase.cached("cached-parser") { Parser }

  /**
   * Frontend
   */
  val Frontend = Phase.cached("frontend") {
    /**
     * Parses a file to a syntax tree
     *   [[Source]] --> [[Parsed]]
     */
    CachedParser andThen
      /**
       * Performs name analysis and associates Id-trees with symbols
       *    [[Parsed]] --> [[NameResolved]]
       */
      Namer andThen
      /**
       * Explicit box transformation
       *   [[NameResolved]] --> [[NameResolved]]
       */
      PreTyper andThen
      /**
       * Type checks and annotates trees with inferred types and effects
       *   [[NameResolved]] --> [[Typechecked]]
       */
      Typer andThen
      /**
       * Wellformedness checks (exhaustivity, non-escape)
       *   [[Typechecked]] --> [[Typechecked]]
       */
      PostTyper
  }

  /**
   * Middleend
   */
  val Middleend = Phase.cached("middleend", cacheBy = (in: Typechecked) => paths.lastModified(in.source)) {
    /**
     * Uses annotated effects to translate to explicit capability passing
     * [[Typechecked]] --> [[Typechecked]]
     */
    Elaborator andThen
    /**
     * Translates a source program to a core program
     * [[Typechecked]] --> [[CoreTransformed]]
     */
    Transformer
  }

  def allToCore(phase: Phase[Source, CoreTransformed]): Phase[Source, AllTransformed] = new Phase[Source, AllTransformed] {
    val phaseName = "core-dependencies"

    def run(input: Source)(using Context) = for {
      main @ CoreTransformed(_, _, mod, _) <- phase(input)
      dependencies <- mod.dependencies.foldRight[Option[List[CoreTransformed]]](Some(Nil)) {
        case (dep, Some(deps)) => phase(dep.source).map(_ :: deps)
        case (_, _) => None
      }
    } yield AllTransformed(input, main, dependencies)
  }

  lazy val Aggregate = Phase[AllTransformed, CoreTransformed]("aggregate") {
    case AllTransformed(_, CoreTransformed(src, tree, mod, main), deps) =>
      val dependencies = deps.map(d => d.core)

      // collect all information
      var declarations: List[core.Declaration] = Nil
      var externs: List[core.Extern] = Nil
      var definitions: List[core.Definition] = Nil
      var exports: List[symbols.Symbol] = Nil

      (dependencies :+ main).foreach { module =>
        externs ++= module.externs
        declarations ++= module.declarations
        definitions ++= module.definitions
        exports ++= module.exports
      }

      val aggregated = core.ModuleDecl(main.path, Nil, declarations, externs, definitions, exports)

      // TODO in the future check for duplicate exports
      CoreTransformed(src, tree, mod, aggregated)
  }

  lazy val Machine = Phase("machine") {
    case CoreLifted(source, tree, mod, core) =>
      val main = Context.checkMain(mod)
      machine.Transformer.transform(main, core)
  }
}
