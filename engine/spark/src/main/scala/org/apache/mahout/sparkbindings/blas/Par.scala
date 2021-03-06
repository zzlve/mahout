/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.mahout.sparkbindings.blas

import org.apache.mahout.logging._
import org.apache.mahout.math.drm.logical.OpPar
import org.apache.mahout.sparkbindings.drm
import org.apache.mahout.sparkbindings.drm.DrmRddInput

import scala.math._

/** Physical adjustment of parallelism */
object Par {

  private final implicit val log = getLog(Par.getClass)

  def exec[K](op: OpPar[K], src: DrmRddInput[K]): DrmRddInput[K] = {

    implicit val ktag = op.keyClassTag
    val srcBlockified = src.isBlockified

    val srcRdd = if (srcBlockified) src.asBlockified(op.ncol) else src.asRowWise()
    val srcNParts = srcRdd.partitions.length

    // To what size?
    val targetParts = if (op.minSplits > 0) srcNParts max op.minSplits
    else if (op.exactSplits > 0) op.exactSplits
    else /* auto adjustment */ {
      val stdParallelism = srcRdd.context.getConf.get("spark.default.parallelism", "1").toInt
      val x1 = 0.95 * stdParallelism
      if (srcNParts <= ceil(x1)) ceil(x1).toInt else ceil(2 * x1).toInt
    }

    debug(s"par $srcNParts => $targetParts.")

    if (targetParts > srcNParts) {

      // Expanding. Always requires deblockified stuff. May require re-shuffling.
      val rdd = src.asRowWise().repartition(numPartitions = targetParts)

      rdd

    } else if (targetParts < srcNParts) {
      // Shrinking.

      if (srcBlockified) {
        drm.rbind(src.asBlockified(op.ncol).coalesce(numPartitions = targetParts))
      } else {
        src.asRowWise().coalesce(numPartitions = targetParts)
      }
    } else {
      // no adjustment required.
      src
    }

  }

}
