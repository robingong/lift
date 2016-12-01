package benchmarks

import lift.arithmetic.SizeVar
import ir._
import ir.ast._
import opencl.ir._
import opencl.ir.pattern._

class MatrixVector (override val f: Seq[(String, Array[Lambda])]) extends Benchmark("Matrix Vector Multiplication (gemv)", Seq(4096, 4096), f, 0.0f) {

  override def runScala(inputs: Any*): Array[Float] = {
    val matrix = inputs(0).asInstanceOf[Array[Array[Float]]]
    val vectorX = inputs(1).asInstanceOf[Array[Float]]
    val vectorY = inputs(2).asInstanceOf[Array[Array[Float]]]
    val alpha = inputs(3).asInstanceOf[Float]
    val beta = inputs(4).asInstanceOf[Float]


    val tmp = matrix.map(
      (row) => (row, vectorX).zipped.map(_ * _).sum * alpha
    )

    val scaledY = vectorY.map(_.head * beta)

    (tmp, scaledY).zipped.map(_ + _)
  }

  override def generateInputs(): Seq[Any] = {
    val inputSizeN = inputSizes()(0)
    val inputSizeM = inputSizes()(1)

    var matrix = Array.fill(inputSizeN, inputSizeM)(util.Random.nextInt(5).toFloat)
    val vectorX = Array.fill(inputSizeM)(util.Random.nextInt(5).toFloat)
    val vectorY = Array.fill(inputSizeN, 1)(util.Random.nextInt(5).toFloat)

    val alpha = 2.5f
    val beta = 1.5f

    if (variant == 4)
      matrix = matrix.transpose

    Seq(matrix, vectorX, vectorY, alpha, beta)
  }
}

object MatrixVector {

  val N = SizeVar("N")
  val M = SizeVar("M")

  val fullMatrixVectorFusedOpenCL = fun(
    ArrayType(ArrayType(Float, N), M),
    ArrayType(Float, N),
    ArrayType(ArrayType(Float, 1), M),
    Float,
    Float,
    (matrix, vectorX, vectorY, alpha, beta) => {
      MapWrg(
        Join() o  toGlobal(MapLcl(MapSeq(fun( x => multAndSumUp(Get(x, 0), Get(x, 1), beta))))) o Split(1) o
          fun( t => Zip(
            Join() o  MapLcl(MapSeq(fun( x => mult(alpha, x) ))) o Split(1) o
              Join() o  toLocal(MapLcl(toLocal(MapSeq(id)) o ReduceSeq(fun((acc, y) => multAndSumUp.apply(acc, Get(y, 0), Get(y, 1))), 0.0f))) o Split(N) $ Zip(vectorX, Get(t, 0)),
            Get(t, 1)) )
      ) $ Zip(matrix, vectorY)
    })

  val fullMatrixVectorFusedOpenCL_ =
    fun(ArrayType(ArrayType(Float, N), M),
        ArrayType(Float, N),
        ArrayType(ArrayType(Float, 1), M),
        Float,
        Float,
        (matrix, vectorX, vectorY, alpha, beta) => {
    Zip(matrix, vectorY) :>>
    MapWrg(
      fun(t =>
        Zip(
          Zip(vectorX, t._0) :>>
          Split(N) :>>
          toLocal(MapLcl(
            ReduceSeq(fun((acc, y) => multAndSumUp(acc, y._0, y._1)), 0.0f) >>>
            toLocal(MapSeq(id))
          )) :>>
          Join() :>>
          Split(1) :>>
          MapLcl(
            MapSeq(fun(x => mult(alpha, x)))
          ) :>>
          Join()
          ,
          Get(t, 1)
        )
      ) >>>
      Split(1) >>>
      toGlobal(MapLcl(MapSeq(fun(x => multAndSumUp(x._0, x._1, beta))))) >>>
      Join()
    )
  })

  val fullMatrixVectorFusedOpenCLAMD = fun(
    ArrayType(ArrayType(Float, N), M),
    ArrayType(Float, N),
    ArrayType(ArrayType(Float, 1), M),
    Float,
    Float,
    (matrix, vectorX, vectorY, alpha, beta) => {
      MapWrg(
        Join() o  toGlobal(MapLcl(MapSeq(fun( x => multAndSumUp(Get(x, 0), Get(x, 1), beta))))) o Split(1) o
          fun( t => Zip(
            Join() o  MapLcl(toLocal(MapSeq(id)) o ReduceSeq(add, 0.0f)) o Split(128) o
              Join() o  MapLcl(MapSeq(fun( x => mult(alpha, x) ))) o Split(1) o
              Join() o  toLocal(MapLcl(toLocal(MapSeq(id)) o ReduceSeq(fun((acc, y) => multAndSumUp.apply(acc, Get(y, 0), Get(y, 1))), 0.0f))) o Split(N/^128) o ReorderStride(128) $ Zip(vectorX, Get(t, 0)),
            Get(t, 1)) )
      ) $ Zip(matrix, vectorY)
    })

  // The same expression as 'fullMatrixVectorFusedOpenCLAMD' but written in a
  // dataflow / more imperative style
  val fullMatrixVectorFusedOpenCLAMD_ = fun(
     ArrayType(ArrayType(Float, N), M),
     ArrayType(Float, N),
     ArrayType(ArrayType(Float, 1), M),
     Float,
     Float,
     (matrix, vectorX, vectorY, alpha, beta) => {
       Zip(matrix, vectorY) :>>
       MapWrg(
         \(pair => {
           val matrixRow = pair._0
           val y_i       = pair._1

           val partialDotProdcut = {
             Zip(vectorX, matrixRow) :>>
             ReorderStride(128) :>>
             Split(N /^ 128) :>>
             toLocal(MapLcl(
               ReduceSeq(\((acc, y) => multAndSumUp(acc, y._0, y._1)), 0.0f) >>>
               toLocal(MapSeq(id))
             )) :>>
             Join()
           }

           val timesAlpha = {
             partialDotProdcut :>>
             Split(1) :>> MapLcl(MapSeq(\(x => mult(alpha, x)))) :>> Join()
           }

           val fullDotProdcut = {
             timesAlpha  :>>
             Split(128) :>> MapLcl(ReduceSeq(add, 0.0f) >>> toLocal(MapSeq(id))) :>> Join()
           }

           Zip(fullDotProdcut, y_i)
         }) >>>
         Split(1) >>>
         toGlobal(MapLcl(MapSeq(fun(x => multAndSumUp(x._0, x._1, beta))))) >>>
         Join()
       )
     })

  val clblast_N = fun(
      ArrayType(ArrayType(Float, N), M),
      ArrayType(Float, N),
      ArrayType(Float,M),
      Float,
      Float,
      (matrix, vectorX, vectorY, alpha, beta) =>
        Join() o MapWrg(fun( matChunk =>

          MapSeq(
            toGlobal(fun(y =>
              MapLcl(fun(x =>
                add(
                  toPrivate(mult)(x._0, alpha),
                  toPrivate(mult)(x._1, beta)
                )
              )) $ Zip(y, Map(Get(1)) $ matChunk)
            ))
          ) o
            ReduceSeq(fun((acc, next) =>
              Let(localX =>
                Join() o MapLcl(fun(x => ReduceSeq(fun((acc2, next2) =>
                  multAndSumUp(acc2, Get(next2, 0), Get(next2, 1)))
                  , Get(x, 0)) $ Zip(Get(x, 1), localX))) $ Zip(acc, Get(next, 0))
              )  o toLocal(MapLcl(id)) $ Get(next, 1)),

              MapLcl(id) $ Value(0.0f, ArrayType(Float, 64)))
            $ Zip(Transpose() o Map(Split(64) o Get(0)) $ matChunk, Split(64) $ vectorX)
        )) o Split(64) $ Zip(matrix, vectorY)
    )

  val clblast_T = fun(
      ArrayType(ArrayType(Float, M), N),
      ArrayType(Float, N),
      ArrayType(Float,M),
      Float,
      Float,
      (matrix, vectorX, vectorY, alpha, beta) =>
        Join() o MapWrg(fun( matChunk =>

          MapSeq(
            toGlobal(fun(y =>
              MapLcl(fun(x =>
                add(
                  toPrivate(mult)(x._0, alpha),
                  toPrivate(mult)(x._1, beta)
                )
              )) $ Zip(y, Map(Get(1)) $ matChunk)
            ))
          ) o
            ReduceSeq(fun((acc, next) =>
              Let(localX =>
                Join() o MapLcl(fun(x => ReduceSeq(fun((acc2, next2) =>
                  multAndSumUp(acc2, Get(next2, 0), Get(next2, 1)))
                  , Get(x, 0)) $ Zip(Get(x, 1), localX))) $ Zip(acc, Get(next, 0))
              )  o toLocal(MapLcl(id)) $ Get(next, 1)),

              MapLcl(id) $ Value(0.0f, ArrayType(Float, 64)))
            $ Zip(Transpose() o Map(Split(64) o Get(0)) $ matChunk, Split(64) $ vectorX)
        )) o Split(64) $ Zip(Transpose() $ matrix, vectorY)
    )

  def apply() = new MatrixVector(Seq(
    ("FULL_MATRIX_VECTOR_FUSED_OPENCL", Array[Lambda](fullMatrixVectorFusedOpenCL)),
    ("FULL_MATRIX_VECTOR_FUSED_OPENCL_AMD", Array[Lambda](fullMatrixVectorFusedOpenCLAMD)),
    ("FULL_MATRIX_VECTOR_FUSED_OPENCL_AMD_", Array[Lambda](fullMatrixVectorFusedOpenCLAMD_)),
    ("clblast_N", Array[Lambda](clblast_N)),
    ("clblast_T", Array[Lambda](clblast_T))
  ))

  def main(args: Array[String]): Unit = {
    MatrixVector().run(args)
  }
}
