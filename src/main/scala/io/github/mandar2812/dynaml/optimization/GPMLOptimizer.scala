/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
* */
package io.github.mandar2812.dynaml.optimization

import breeze.linalg.{DenseVector, norm}
import org.apache.log4j.Logger

/**
  * @author mandar2812 datum 16/1/16.
  *
  * Performs ML-II gradient based hyper-parameter
  * optimization for Gaussian Process regression models
  */
class GPMLOptimizer[I, T, M <: GloballyOptimizable](model: M)
  extends GlobalOptimizer[M] {
  override val system: M = model

  protected val logger = Logger.getLogger(this.getClass)

  override def optimize(initialConfig: Map[String, Double],
                        options: Map[String, String] = Map("tolerance" -> "0.0001",
                          "step" -> "0.005",
                          "maxIterations" -> "50"))
  : (M, Map[String, Double]) = {

    logger.info("Starting Maximum Likelihood based optimization: ML-II")
    logger.info("-----------------------------------------------------")
    //Carry out gradient descent with step size alpha and
    //for a specified number of maximum iterations

    val tolerance = options("tolerance").toDouble
    val alpha = options("step").toDouble
    val maxit = options("maxIterations").toInt

    var count = 1
    var gradNorm = 1.0
    var working_solution = initialConfig

    do {
      val gradient = system.gradEnergy(working_solution)
      logger.info("Gradient at "+count+" iteration is: "+gradient)
      gradNorm = norm(DenseVector(gradient.values.toArray), 2)

      working_solution = working_solution.zip(gradient).map((confAndGrad) => {
        val hyp = confAndGrad._1._1

        val gr:Double = if(confAndGrad._2._2 == Double.PositiveInfinity){
          1.0
        } else if(confAndGrad._2._2 == Double.NegativeInfinity){
          -1.0
        } else if(confAndGrad._2._2 == Double.NaN){
          1.0
        } else {
          confAndGrad._2._2
        }

        val newValue = confAndGrad._1._2 - alpha*gr
        (hyp,newValue)
      })

      count += 1
    } while(count < maxit && gradNorm >= tolerance)
    logger.info("Stopped ML-II at "+count+" iterations")
    logger.info("Final state : "+working_solution)
    (system, working_solution)
  }
}
