/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.carbondata.spark.testsuite.datacompaction

import java.io.File

import scala.collection.JavaConverters._

import org.apache.spark.sql.common.util.CarbonHiveContext._
import org.apache.spark.sql.common.util.QueryTest
import org.scalatest.BeforeAndAfterAll

import org.apache.carbondata.core.carbon.path.{CarbonStorePath, CarbonTablePath}
import org.apache.carbondata.core.carbon.{AbsoluteTableIdentifier, CarbonTableIdentifier}
import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.util.CarbonProperties
import org.apache.carbondata.lcm.locks.{CarbonLockFactory, ICarbonLock, LockUsage}
import org.apache.carbondata.lcm.status.SegmentStatusManager

/**
  * FT for data compaction Locking scenario.
  */
class DataCompactionLockTest extends QueryTest with BeforeAndAfterAll {

  val absoluteTableIdentifier: AbsoluteTableIdentifier = new
      AbsoluteTableIdentifier(
        CarbonProperties.getInstance.getProperty(CarbonCommonConstants.STORE_LOCATION),
        new CarbonTableIdentifier("default", "compactionLockTestTable", "1")
      )
  val carbonTablePath: CarbonTablePath = CarbonStorePath
    .getCarbonTablePath(absoluteTableIdentifier.getStorePath,
      absoluteTableIdentifier.getCarbonTableIdentifier
    )
  val dataPath: String = carbonTablePath.getMetadataDirectoryPath

  val carbonLock: ICarbonLock =
    CarbonLockFactory.getCarbonLockObj(absoluteTableIdentifier.getCarbonTableIdentifier, LockUsage.TABLE_STATUS_LOCK)


  override def beforeAll {
    CarbonProperties.getInstance().addProperty("carbon.enable.load.merge", "true")
    sql("drop table if exists  compactionLockTestTable")
    CarbonProperties.getInstance()
      .addProperty(CarbonCommonConstants.CARBON_TIMESTAMP_FORMAT, "mm/dd/yyyy")
    sql(
      "CREATE TABLE IF NOT EXISTS compactionLockTestTable (country String, ID Int, date " +
        "Timestamp, name " +
        "String, " +
        "phonetype String, serialname String, salary Int) STORED BY 'org.apache.carbondata" +
        ".format'"
    )

    val currentDirectory = new File(this.getClass.getResource("/").getPath + "/../../")
      .getCanonicalPath
    var csvFilePath1 = currentDirectory + "/src/test/resources/compaction/compaction1.csv"

    var csvFilePath2 = currentDirectory + "/src/test/resources/compaction/compaction2.csv"
    var csvFilePath3 = currentDirectory + "/src/test/resources/compaction/compaction3.csv"

    sql("LOAD DATA LOCAL INPATH '" + csvFilePath1 + "' INTO TABLE compactionLockTestTable " +
      "OPTIONS" +
      "('DELIMITER'= ',', 'QUOTECHAR'= '\"')"
    )
    sql("LOAD DATA LOCAL INPATH '" + csvFilePath2 + "' INTO TABLE compactionLockTestTable  " +
      "OPTIONS" +
      "('DELIMITER'= ',', 'QUOTECHAR'= '\"')"
    )
    sql("LOAD DATA LOCAL INPATH '" + csvFilePath3 + "' INTO TABLE compactionLockTestTable  " +
      "OPTIONS" +
      "('DELIMITER'= ',', 'QUOTECHAR'= '\"')"
    )
    // take the lock so that next compaction will be failed.
    carbonLock.lockWithRetries()

    // compaction should happen here.
    sql("alter table compactionLockTestTable compact 'major'"
    )
  }

  /**
    * Compaction should fail as lock is being held purposefully
    */
  test("check if compaction is failed or not.") {
    var status = true
    var noOfRetries = 0
    while (status && noOfRetries < 10) {

      val segmentStatusManager: SegmentStatusManager = new SegmentStatusManager(
        absoluteTableIdentifier
      )
      val segments = segmentStatusManager.getValidSegments().listOfValidSegments.asScala.toList

      if (!segments.contains("0.1")) {
        // wait for 2 seconds for compaction to complete.
        Thread.sleep(2000)
        noOfRetries += 1
      }
      else {
        status = false
      }
    }
    assert(status)
  }


  override def afterAll {
    /* sql("drop table compactionLockTestTable") */
    CarbonProperties.getInstance()
      .addProperty(CarbonCommonConstants.CARBON_TIMESTAMP_FORMAT, "dd-MM-yyyy")
    carbonLock.unlock()
  }

}
