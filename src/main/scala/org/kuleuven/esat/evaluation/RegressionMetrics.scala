package org.kuleuven.esat.evaluation

import breeze.linalg.DenseVector
import org.apache.log4j.{Priority, Logger}

/**
 * Class implementing the calculation
 * of regression performance evaluation
 * metrics
 *
 * */

class RegressionMetrics(
    override protected val scoresAndLabels: List[(Double, Double)],
    val len: Int)
  extends Metrics[Double] {
  private val logger = Logger.getLogger(this.getClass)
  val length: Int = len

  val rmse: Double = math.sqrt(scoresAndLabels.map((p) =>
    math.pow(p._1 - p._2, 2)/length).sum)

  val mae: Double = scoresAndLabels.map((p) =>
    math.abs(p._1 - p._2)/length).sum

  val rmsle: Double = math.sqrt(scoresAndLabels.map((p) =>
    math.pow(math.log(1 + math.abs(p._1)) - math.log(math.abs(p._2) + 1),
      2)/length).sum)

  val Rsq: Double = RegressionMetrics.computeRsq(scoresAndLabels, length)

  override def print(): Unit = {
    logger.log(Priority.INFO, "Regression Model Performance")
    logger.log(Priority.INFO, "============================")
    logger.log(Priority.INFO, "MAE: " + mae)
    logger.log(Priority.INFO, "RMSE: " + rmse)
    logger.log(Priority.INFO, "RMSLE: " + rmsle)
    logger.log(Priority.INFO, "R^2: " + Rsq)
  }

  override def kpi() = DenseVector(mae, rmse, Rsq)

}

object RegressionMetrics {
  def computeRsq(scoresAndLabels: Iterable[(Double, Double)], size: Int): Double = {

    val mean: Double = scoresAndLabels.map{coup => coup._2}.sum/size
    var SSres = 0.0
    var SStot = 0.0
    scoresAndLabels.foreach((couple) => {
      SSres += math.pow(couple._2 - couple._1, 2)
      SStot += (math.pow(couple._2 - couple._1, 2) + math.pow(couple._1 - mean, 2))
    })
    1 - (SSres/SStot)
  }
}