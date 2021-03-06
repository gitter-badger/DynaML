package io.github.mandar2812.dynaml.examples

import java.io.File
import java.text.SimpleDateFormat
import java.util.{Calendar, GregorianCalendar, Date}

import breeze.linalg.{DenseMatrix, DenseVector}
import com.github.tototoshi.csv.CSVWriter
import io.github.mandar2812.dynaml.evaluation.RegressionMetrics
import io.github.mandar2812.dynaml.kernels._
import io.github.mandar2812.dynaml.models.gp.{GPNarModel, GPRegression}
import io.github.mandar2812.dynaml.optimization.{GPMLOptimizer, GridSearch}
import io.github.mandar2812.dynaml.pipes.{StreamDataPipe, DynaMLPipe, DataPipe}
import com.quantifind.charts.Highcharts._
import org.apache.log4j.Logger


/**
  * @author mandar2812 datum 22/11/15.
  *
  * Test a GP-NAR model on the Omni Data set
  */
object TestOmniAR {

  def apply(year: Int, start: String = "2006/12/28/00", end: String = "2006/12/29/23",
            kernel: CovarianceFunction[DenseVector[Double], Double, DenseMatrix[Double]],
            delta: Int, timeLag:Int,
            noise: CovarianceFunction[DenseVector[Double], Double, DenseMatrix[Double]],
            num_training: Int,
            column: Int, grid: Int,
            step: Double, globalOpt: String,
            stepSize: Double = 0.05,
            maxIt: Int = 200,
            action: String = "test") =
    runExperiment(year, start, end,
      kernel, delta, timeLag, stepPred = 0, noise,
      num_training, column, grid, step, globalOpt,
      Map("tolerance" -> "0.0001",
        "step" -> stepSize.toString,
        "maxIterations" -> maxIt.toString), action)

  def runExperiment(year: Int = 2006, start: String = "", end: String = "",
                    kernel: CovarianceFunction[DenseVector[Double], Double, DenseMatrix[Double]],
                    deltaT: Int = 2, timelag:Int = 0, stepPred: Int = 3,
                    noise: CovarianceFunction[DenseVector[Double], Double, DenseMatrix[Double]],
                    num_training: Int = 200, column: Int = 40, grid: Int = 5,
                    step: Double = 0.2, globalOpt: String = "ML",
                    opt: Map[String, String],
                    action: String = "test"): Seq[Seq[Double]] = {
    //Load Omni data into a stream
    //Extract the time and Dst values

    val names = Map(24 -> "Solar Wind Speed",
      16 -> "I.M.F Bz",
      40 -> "Dst",
      41 -> "AE",
      38 -> "Kp",
      39 -> "Sunspot Number",
      28 -> "Plasma Flow Pressure")

    val logger = Logger.getLogger(this.getClass)

    val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy/MM/dd/HH")
    val dateS: Date = sdf.parse(start)
    val dateE: Date = sdf.parse(end)

    val greg: GregorianCalendar = new GregorianCalendar()
    greg.setTime(dateS)
    val dayStart = greg.get(Calendar.DAY_OF_YEAR)
    val hourStart = greg.get(Calendar.HOUR_OF_DAY)
    val stampStart = (dayStart * 24) + hourStart
    val yearTest = greg.get(Calendar.YEAR)


    greg.setTime(dateE)
    val dayEnd = greg.get(Calendar.DAY_OF_YEAR)
    val hourEnd = greg.get(Calendar.HOUR_OF_DAY)
    val stampEnd = (dayEnd * 24) + hourEnd

    //pipe training data to model and then generate test predictions
    //create RegressionMetrics instance and produce plots
    val modelTrainTest =
      (trainTest: ((Stream[(DenseVector[Double], Double)],
        Stream[(DenseVector[Double], Double)]),
        (DenseVector[Double], DenseVector[Double]))) => {
        val model = new GPNarModel(deltaT, kernel, noise, trainTest._1._1.toSeq)

        val gs = globalOpt match {
          case "GS" => new GridSearch[model.type](model)
            .setGridSize(grid)
            .setStepSize(step)
            .setLogScale(false)

          case "ML" => new GPMLOptimizer[DenseVector[Double],
            Seq[(DenseVector[Double], Double)],
            GPRegression](model)
        }

        val startConf = kernel.state ++ noise.state
        val (_, conf) = gs.optimize(startConf, opt)

        model.setState(conf)
        model.persist()

        val res = model.test(trainTest._1._2.toSeq)

        val deNormalize = DataPipe((list: List[(Double, Double, Double, Double)]) =>
          list.map{l => (l._1*trainTest._2._2(-1) + trainTest._2._1(-1),
            l._2*trainTest._2._2(-1) + trainTest._2._1(-1),
            l._3*trainTest._2._2(-1) + trainTest._2._1(-1),
            l._4*trainTest._2._2(-1) + trainTest._2._1(-1))})

        val deNormalize1 = DataPipe((list: List[(Double, Double)]) =>
          list.map{l => (l._1*trainTest._2._2(-1) + trainTest._2._1(-1),
            l._2*trainTest._2._2(-1) + trainTest._2._1(-1))})

        val scoresAndLabelsPipe =
          DataPipe(
            (res: Seq[(DenseVector[Double], Double, Double, Double, Double)]) =>
              res.map(i => (i._3, i._2, i._4, i._5)).toList) > deNormalize

        val scoresAndLabels = scoresAndLabelsPipe.run(res)

        val metrics = new RegressionMetrics(scoresAndLabels.map(i => (i._1, i._2)),
          scoresAndLabels.length)

        val (name, name1) =
          if(names.contains(column)) (names(column), names(column))
          else ("Value","Time Series")

        metrics.setName(name)

        /*val incrementsPipe =
          DataPipe(
            (res: Seq[(DenseVector[Double], Double, Double, Double, Double)]) =>
              res.map(i => (i._3, i._2)).toList) >
             deNormalize1 >
            DataPipe((list: List[(Double, Double)]) =>
              list.sliding(2).map(i => (i(1)._1 - i.head._1,
                i(1)._2 - i.head._2)).toList)

        val increments = incrementsPipe.run(res)

        val incrementMetrics = new RegressionMetrics(increments, increments.length)
        */

        //Model Predicted Output, only in stepPred > 0
        var mpoRes: Seq[Double] = Seq()
        if(stepPred > 0) {
          //Now test the Model Predicted Output and its performance.
          val mpo = model.modelPredictedOutput(stepPred) _
          val testData = trainTest._1._2


          val predictedOutput:List[Double] = testData.grouped(stepPred).map((partition) => {
            val preds = mpo(partition.head._1).map(_._1)
            if(preds.length == partition.length) {
              preds.toList
            } else {
              preds.take(partition.length).toList
            }
          }).foldRight(List[Double]())(_++_)

          val outputs = testData.map(_._2).toList

          val res2 = predictedOutput zip outputs
          val scoresAndLabels2 = deNormalize1.run(res2.toList)

          val mpoMetrics = new RegressionMetrics(scoresAndLabels2,
            scoresAndLabels2.length)

          logger.info("Printing Model Predicted Output (MPO) Performance Metrics")
          mpoMetrics.print()

          val timeObsMPO = scoresAndLabels2.map(_._2).zipWithIndex.min._2
          val timeModelMPO = scoresAndLabels2.map(_._1).zipWithIndex.min._2
          logger.info("Timing Error; MPO, "+stepPred+
            " hours ahead Prediction: "+(timeObsMPO-timeModelMPO))

          mpoMetrics.generateFitPlot()
          //Plotting time series prediction comparisons
          line((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._2))
          hold()
          line((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._1))
          line((1 to scoresAndLabels2.length).toList, scoresAndLabels2.map(_._1))
          legend(List("Time Series", "Predicted Time Series (one hour ahead)",
            "Predicted Time Series ("+stepPred+" hours ahead)"))
          unhold()

          mpoRes = Seq(year.toDouble, yearTest.toDouble, deltaT.toDouble,
            stepPred.toDouble, num_training.toDouble,
            trainTest._1._2.length.toDouble,
            mpoMetrics.mae, mpoMetrics.rmse, mpoMetrics.Rsq,
            mpoMetrics.corr, mpoMetrics.modelYield,
            timeObsMPO.toDouble - timeModelMPO.toDouble,
            scoresAndLabels2.map(_._1).min)

        }

        metrics.generateFitPlot()
        //Plotting time series prediction comparisons
        line((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._2))
        hold()
        line((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._1))
        spline((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._3))
        hold()
        spline((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._4))
        legend(List(name1, "Predicted "+name1+" (one hour ahead)", "Lower Bar", "Higher Bar"))
        unhold()

        val timeObs = scoresAndLabels.map(_._2).zipWithIndex.min._2
        val timeModel = scoresAndLabels.map(_._1).zipWithIndex.min._2
        logger.info("Timing Error; OSA Prediction: "+(timeObs-timeModel))

        /*incrementMetrics.generateFitPlot()
        line((1 to increments.length).toList, increments.map(_._2))
        hold()
        line((1 to increments.length).toList, increments.map(_._1))
        legend(List("Increments of "+name1,
          "Predicted Increments of "+name1+" (one hour ahead)"))
        unhold()
        */

        logger.info("Printing One Step Ahead (OSA) Performance Metrics")
        metrics.print()

        /*
        logger.info("Results for Prediction of increments")
        incrementMetrics.print()
        */
        action match {
          case "test" =>
            Seq(
              Seq(year.toDouble, yearTest.toDouble, deltaT.toDouble,
                1.0, num_training.toDouble, trainTest._1._2.length.toDouble,
                metrics.mae, metrics.rmse, metrics.Rsq,
                metrics.corr, metrics.modelYield,
                timeObs.toDouble - timeModel.toDouble,
                scoresAndLabels.map(_._1).min),
              mpoRes
            )

          case "predict" => scoresAndLabels.toSeq.map(i => Seq(i._2, i._1))
        }
      }

    val preProcessPipe = DynaMLPipe.fileToStream >
      DynaMLPipe.replaceWhiteSpaces >
      DynaMLPipe.extractTrainingFeatures(
        List(0,1,2,column),
        Map(
          16 -> "999.9", 21 -> "999.9",
          24 -> "9999.", 23 -> "999.9",
          40 -> "99999", 22 -> "9999999.",
          25 -> "999.9", 28 -> "99.99",
          27 -> "9.999", 39 -> "999",
          45 -> "99999.99", 46 -> "99999.99",
          47 -> "99999.99")
      ) > DynaMLPipe.removeMissingLines >
      DynaMLPipe.extractTimeSeries((year,day,hour) => (day * 24) + hour)


    val processTraining = preProcessPipe >
      DynaMLPipe.deltaOperation(deltaT, timelag)

    val processTest = preProcessPipe >
      StreamDataPipe((couple: (Double, Double)) => couple._1 >= stampStart && couple._1 <= stampEnd) >
      DynaMLPipe.deltaOperation(deltaT, timelag)

    val trainTestPipe = DataPipe(processTraining, processTest) >
      DataPipe((data: (Stream[(DenseVector[Double], Double)],
        Stream[(DenseVector[Double], Double)])) => {
        (data._1.take(num_training), data._2)
      }) > DynaMLPipe.gaussianStandardization >
      DataPipe(modelTrainTest)

    trainTestPipe.run(("data/omni2_"+year+".csv",
      "data/omni2_"+yearTest+".csv"))

  }

}

object DstARExperiment {

  def apply(years: List[Int] = (2007 to 2014).toList,
            testYears: List[Int] = (2000 to 2015).toList,
            modelSizes: List[Int] = List(50, 100, 150),
            deltas: List[Int] = List(1, 2, 3),
            stepAhead: Int, bandwidth: Double,
            noise: Double, num_test: Int,
            column: Int, grid: Int, step: Double) = {

    val writer = CSVWriter.open(new File("data/OmniRes.csv"), append = true)

    years.foreach((year) => {
      testYears.foreach((testYear) => {
        deltas.foreach((delta) => {
          modelSizes.foreach((modelSize) => {
            TestOmniAR.runExperiment(
              year, testYear.toString+"/12/12:00",
              testYear.toString+"/12/12:23",
              new FBMKernel(bandwidth),
              delta, 0, stepAhead,
              new DiracKernel(noise),
              modelSize, column,
              grid, step, "GS",
              Map("tolerance" -> "0.0001",
                "step" -> "0.1",
                "maxIterations" -> "100"))
              .foreach(res => writer.writeRow(res))
          })
        })
      })
    })

    writer.close()
  }

  def apply(yearTrain: Int,
            kernel: CovarianceFunction[DenseVector[Double],
              Double, DenseMatrix[Double]],
            num_training: Int,
            deltas: List[Int],
            options: Map[String, String]) = {
    val writer =
      CSVWriter.open(new File("data/"+
        options("fileID")+
        "OmniARStormsRes.csv"),
        append = true)

    val initialKernelState = kernel.state

    deltas.foreach(modelOrder => {
      val stormsPipe =
        DynaMLPipe.fileToStream >
          DynaMLPipe.replaceWhiteSpaces >
          StreamDataPipe((stormEventData: String) => {
            val stormMetaFields = stormEventData.split(',')

            val eventId = stormMetaFields(0)
            val startDate = stormMetaFields(1)
            val startHour = stormMetaFields(2).take(2)

            val endDate = stormMetaFields(3)
            val endHour = stormMetaFields(4).take(2)

            val minDst = stormMetaFields(5).toDouble

            val stormCategory = stormMetaFields(6)
            kernel.setHyperParameters(initialKernelState)
            val res = TestOmniAR.runExperiment(yearTrain,
              startDate+"/"+startHour, endDate+"/"+endHour,
              kernel, modelOrder, 0, 0, new DiracKernel(2.0),
              250, 40, options("grid").toInt, options("step").toDouble,
              options("globalOpt"), Map(), action = "test")

            val row = Seq(eventId, stormCategory, modelOrder, num_training, res.head(7),
              res.head(9), res.head.last-minDst, minDst, res.head(11))

            writer.writeRow(row)
          })

      stormsPipe.run("data/geomagnetic_storms.csv")
    })
  }
}