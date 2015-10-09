package cgoSearch

import java.nio.file.{Files, Paths}

import apart.arithmetic.{Var, ArithExpr, Cst}
import exploration.utils.Utils
import exploration.{InferNDRange, TestLowLevelRewrite}
import ir.TypeChecker
import ir.ast.Lambda
import opencl.executor.{Execute, Eval, Executor}
import opencl.generator.OpenCLGenerator

import scala.collection.immutable.Map
import scala.io.Source

/**
 * This main currently runs a parameter space exploration over the serialized low level expressions.
 * It requires the lambdas/ and lowered/ folders with the generated index files.
 *
 * This version passes a transposed matrix to the kernel.
 */
object Main {
  val generate_counter: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(0)

  def main(args: Array[String]) {
    Executor.loadLibrary()
    Executor.init()

    // Various counters
    var passed = 0
    var skipped = 0
    var avoided = 0
    var failed = 0
    var crashed = 0
    var best_time = Double.PositiveInfinity
    var all_times: List[Double] = List.empty
    var best_substitutions = Seq[ArithExpr]()

    // Prepare the input
    val mSize = SearchParameters.matrix_size
    val kSize = SearchParameters.matrix_size
    val nSize = SearchParameters.matrix_size

    println("Generating data")
    val matrixA = Array.tabulate(mSize, kSize)((r, c) => (((r * 3 + c * 2) % 10) + 1) * 1.0f)
    val matrixB = Array.tabulate(kSize, nSize)((r, c) => (((r * 7 + c * 3) % 10) + 1) * 1.0f)
    // Kernel parameters
    val values = Seq(matrixA.transpose, matrixB)

    println("Computing gold solution")
    val gold = Executor.nativeMatrixMultiply(matrixA.flatten, matrixB.flatten, mSize, nSize, kSize)

    // create the execution engine
    val executor = new ExecutionHarness(gold)

    // list all the high level expression
    val all_files = Source.fromFile("lambdas/index").getLines().toList

    var expr_counter = 0
    all_files.foreach(filename => {
      var failure_guard = 0

      val high_level_hash = filename.split("/").last
      if (Files.exists(Paths.get(filename))) {
        expr_counter = expr_counter + 1
        println(s"Expression : $expr_counter / ${all_files.size}")

        val high_level_str = Source.fromFile(filename).getLines().mkString("\n").replace("idfloat", "id")
        val high_level_expr = Eval(high_level_str)

        TestLowLevelRewrite.replaceInputTypes(high_level_expr)

        TypeChecker(high_level_expr)

        val all_substitution_tables = ParameterSearch(high_level_expr)
        println(s"Found ${all_substitution_tables.size} valid parameter sets")


        if (Files.exists(Paths.get(s"lower/$high_level_hash/index"))
          && all_substitution_tables.size < 800000 && failure_guard < 10) {
          val low_level_expr_list = Source.fromFile(s"lower/$high_level_hash/index").getLines().toList


          var low_level_counter = 0
          println(s"Found ${low_level_expr_list.size} low level expressions")

          low_level_expr_list.par.foreach(low_level_filename => {
            var counter = 0

            val low_level_hash = low_level_filename.split("/").last
            low_level_counter = low_level_counter + 1
            val low_level_str = Source.fromFile(low_level_filename).getLines().mkString("\n").replace("idfloat", "id")
            val low_level_factory = Eval.getMethod(low_level_str)

            println(s"Expression number $low_level_counter")


            println("Propagating parameters...")
            val potential_expressions = all_substitution_tables.map(st => {
              val params = st.map(a => a).toSeq.sortBy(_._1.toString.substring(3).toInt).map(_._2)
              try {
                val expr = low_level_factory(
                  Seq(Cst(SearchParameters.matrix_size),
                      Cst(SearchParameters.matrix_size),
                      Cst(SearchParameters.matrix_size)) ++ params)
                TypeChecker(expr)
                if(ExpressionFilter(expr) == ExpressionFilter.Status.Success)
                  Some((low_level_factory(
                    Seq(Var(""),
                      Var(""),
                      Var("")) ++ params), params))
                else
                  None
                } catch {
                case x: ir.TypeException => None
                case x: Throwable =>
                   x.printStackTrace()
                   println(low_level_hash)
                   println(params.mkString("; "))
                   println(low_level_str)
                   println(SearchParameters.matrix_size)
                   //System.exit(-1)
                   None
                }
            }).collect{ case Some(x) => x }.toList

            println(s"Found ${potential_expressions.size} / ${all_substitution_tables.size} filtered expressions")

            if (!SearchParameters.onlyGenerateOpenCL)
              executePotentialExpressions()
            else
              dumpOpenCLToFiles(potential_expressions, low_level_hash, high_level_hash, values)

            def executePotentialExpressions(): Unit = {
              potential_expressions.foreach(expr => {
                if (failure_guard < 10) {
                  //println(st.map(x => s"${x._1} -> ${x._2}").mkString("; "))
                  val (test, time) = executor(Math.min(best_time, 1000f), expr._1, values: _*)

                  import ExecutionHarness.Status._
                  test match {
                    case Success =>
                      passed = passed + 1
                      all_times = time :: all_times
                      if (time < best_time) {
                        best_time = time
                        best_substitutions = expr._2
                        println()
                        println(expr._1)
                      }

                    case Skipped =>
                      skipped = skipped + 1

                    case ValidationError =>
                      println()
                      println(expr._2.map(x => s"$x").mkString("; "))
                      println(expr._1)
                      failed = failed + 1
                      failure_guard = failure_guard + 1

                    case Avoided =>
                      avoided = avoided + 1

                    case x =>
                      println()
                      println(x)
                      println(expr._2.map(x => s"$x").mkString("; "))
                      println(expr._1)
                      crashed = crashed + 1
                  }
                  print(s"\r$expr_counter / ${all_files.size}; $low_level_counter / ${low_level_expr_list.size}; $counter / ${potential_expressions.size} " +
                    s"($passed passed, $skipped skipped, $avoided avoided, $failed failed, $crashed crashed) best = $best_time                   ")


                }
                counter = counter + 1
              })
            }
          })
        }
      }
    })
  }

  def dumpOpenCLToFiles(expressions: List[(Lambda, Seq[ArithExpr])],
                        lowLevelHash: String,
                        highLevelHash: String,
                        values: Seq[Any] ): Unit = {
    expressions.foreach(pair => {
     try {
      val lambda = pair._1
      val substitutionMap = pair._2

      val (local, global) = InferNDRange(lambda)
      val valueMap = Execute.createValueMap(lambda, values:_*)

      val code = OpenCLGenerator.generate(lambda, local, InferNDRange.substituteInNDRange(global, valueMap), valueMap)

      val kernel =
      s"""
        |// Substitutions: $substitutionMap
        |// Local sizes: ${local.map(_.eval).mkString(", ")}
        |// Global sizes: ${global.mkString(", ")}
        |// High-level hash: $highLevelHash
        |// Low-level hash: $lowLevelHash
        |// Input size: ${SearchParameters.matrix_size}
        |
        |$code
      """.stripMargin

      val variables = Utils.findVariables(kernel)
      val variablesReplacedInKernel = Utils.replaceVariableDeclarations(kernel, variables)

      val hash = Utils.Sha256Hash(variablesReplacedInKernel)
      val filename = hash + ".cl"

      /*val path = "kernels/"+
        pathForHash(highLevelHash) + "/" +
        pathForHash(lowLevelHash) + "/" +
        pathForHash(filename)*/
      val path = s"cl/$lowLevelHash"

        val (_,globalBuffers) = OpenCLGenerator.getMemories(lambda)
        // FIXME(tlutz): some buffer sizes overflow


        // Dump only the code if the minimal amount of temporary global arrays doesn't overflow
        val min_map = globalBuffers.map(x => {
          val subst = collection.immutable.Map(x.mem.size.varList.map(v => (v, Cst(1024)).asInstanceOf[(ArithExpr, ArithExpr)]).toSeq:_*)
          ArithExpr.substitute(x.mem.size, subst).eval
        })

        if(min_map.forall(_ > 0)) {
          val v = Main.generate_counter.incrementAndGet()
          if(v % 100 == 0) println(s"Generated $v source files")

          Utils.dumpToFile(variablesReplacedInKernel, filename, path)


          Seq(1024,2048,4096,8192,16384).foreach(i => {

            // Add to the CSV if there are no overflow
            val cur_temp_alloc = globalBuffers.map(x => {
              val subst = collection.immutable.Map(x.mem.size.varList.map(v => (v, Cst(i)).asInstanceOf[(ArithExpr, ArithExpr)]).toSeq:_*)
              ArithExpr.substitute(x.mem.size, subst).eval
            })

            if(cur_temp_alloc.forall(_ > 0)) {
              val fw = new java.io.FileWriter(s"$path/exec_$i.csv", true)
              fw.write(i + "," +
                global.map(x => {
                  val subst = collection.immutable.Map(x.varList.map(v => (v, Cst(i)).asInstanceOf[(ArithExpr, ArithExpr)]).toSeq: _*)
                  ArithExpr.substitute(x, subst)
                }).mkString(",") + "," +
                local.map(x => {
                  val subst = collection.immutable.Map(x.varList.map(v => (v, Cst(i)).asInstanceOf[(ArithExpr, ArithExpr)]).toSeq: _*)
                  ArithExpr.substitute(x, subst)
                }).mkString(",") + s",$hash," + (globalBuffers.length - 3) + "," +
                cur_temp_alloc.mkString(",") + "\n")
              fw.close()
            }
          })
        }
     } catch {
       case _: Throwable =>
     }
    })

  }

  def pathForHash(hash: String): String =
    hash.charAt(0) + "/" + hash.charAt(1) + "/" + hash
}