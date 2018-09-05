/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package com.mozilla.telemetry.views

import java.time.{LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter

import com.mozilla.telemetry.heka.Dataset
import com.mozilla.telemetry.utils.getOrCreateSparkSession
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Row, SaveMode}
import org.apache.spark.util.LongAccumulator
import org.json4s._
import org.json4s.jackson.JsonMethods._


object CrashAggregateView extends BatchJobBase {
  private val logger = org.apache.log4j.Logger.getLogger(this.getClass.getName)

  private class Conf(args: Array[String]) extends BaseOpts(args) {
    verify()
  }

  def main(args: Array[String]) {
    // load configuration for the time range
    val conf = new Conf(args)

    // set up Spark
    val spark = getOrCreateSparkSession("CrashAggregateView")
    implicit val sc = spark.sparkContext
    val hadoopConf = sc.hadoopConfiguration
    hadoopConf.set("fs.s3n.impl", "org.apache.hadoop.fs.s3native.NativeS3FileSystem")

    for (submissionDate <- datesBetween(conf.from(), conf.to.toOption)) {
      val date = LocalDate.parse(submissionDate, BatchJobBase.DateFormatter)
      val submissionDateDash = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

      val messages = Dataset("telemetry")
        .where("sourceName") {
          case "telemetry" => true
        }.where("sourceVersion") {
          case "4" => true
        }.where("docType") {
          case doc if List("main", "crash") contains doc => true
        }.where("submissionDate") {
          case date if date == submissionDate => true
        }.map(message => {
          val fields = message.fieldsAsMap
          fields.get("docType") match {
            case Some("crash") => {
              val payload = message.payload.getOrElse(fields.getOrElse("submission", "{}")) match {
                case value: String => parse(value)
                case _ => JNothing
              }
              fields + ("payload" -> payload) - "submission"
            }
            case _ => fields
          }
        })

      val (rowRDD, main_processed, main_ignored, browser_crash_processed, browser_crash_ignored, content_crash_ignored) = compareCrashes(sc, messages)

      // create a dataframe containing all the crash aggregates
      val schema = buildSchema()
      val records = spark.createDataFrame(rowRDD.coalesce(1), schema)

      // upload the resulting aggregate Spark records to S3
      records.write.mode(SaveMode.Overwrite).parquet(s"s3://${conf.outputBucket()}/crash_aggregates/v1/submission_date=$submissionDateDash")

      logger.info("=======================================================================================")
      logger.info(s"JOB COMPLETED SUCCESSFULLY FOR $submissionDate")
      logger.info(s"${main_processed.value} main pings processed, ${main_ignored.value} pings ignored")
      logger.info(s"${browser_crash_processed.value} browser crash pings processed, ${browser_crash_ignored.value} pings ignored")
      logger.info(s"${content_crash_ignored.value} content crash pings ignored")
      logger.info("=======================================================================================")

      if (shouldStopContextAtEnd(spark)) { spark.stop() }
    }
  }

  // paths/dimensions within the ping to compare by
  // if the path only has a single element, then the field is interpreted as a literal string rather than a JSON string
  val comparableDimensions = List(
    List("environment.build", "version"),
    List("environment.build", "buildId"),
    List("normalizedChannel"),
    List("appName"),
    List("environment.system", "os", "name"),
    List("environment.system", "os", "version"),
    List("environment.build", "architecture"),
    List("geoCountry"),
    List("environment.addons", "activeExperiment", "id"),
    List("environment.addons", "activeExperiment", "branch"),
    List("environment.settings", "e10sEnabled"),
    List("environment.system", "gfx", "features", "compositor")
  )

  // names of the comparable dimensions above, used as dimension names in the database
  val dimensionNames = List(
    "build_version",
    "build_id",
    "channel",
    "application",
    "os_name",
    "os_version",
    "architecture",
    "country",
    "experiment_id",
    "experiment_branch",
    "e10s_enabled",
    "gfx_compositor"
  )

  val statsNames = List(
    "ping_count",
    "usage_hours", "main_crashes", "content_crashes",
    "plugin_crashes", "gmplugin_crashes", "content_shutdown_crashes", "gpu_crashes",
    "usage_hours_squared", "main_crashes_squared", "content_crashes_squared",
    "plugin_crashes_squared", "gmplugin_crashes_squared", "content_shutdown_crashes_squared",
    "gpu_crashes_squared"
  )

  private def getCountHistogramValue(histogram: JValue): Double = {
    try {
      histogram \ "values" \ "0" match {
        case JInt(count) => count.toDouble
        case _ => 0
      }
    } catch { case _: Throwable => 0 }
  }

  def compareCrashes(sc: SparkContext, messages: RDD[Map[String, Any]]): (
      RDD[Row], LongAccumulator, LongAccumulator, LongAccumulator, LongAccumulator, LongAccumulator) = {
    // get the crash pairs for all of the pings, keeping track of how many we see
    val mainProcessedAccumulator = sc.longAccumulator("Number of processed main pings")
    val mainIgnoredAccumulator = sc.longAccumulator("Number of ignored main pings")
    val crashProcessedAccumulator = sc.longAccumulator("Number of processed crash pings")
    val crashIgnoredAccumulator = sc.longAccumulator("Number of ignored crash pings")
    val contentCrashIgnoredAccumulator = sc.longAccumulator("Number of ignored content crash pings")
    // Filter out content crash pings, since we already obtain them from the main ping.
    // See https://bugzilla.mozilla.org/show_bug.cgi?id=1310673
    val filtered_messages = messages.filter(m => {
      m.get("docType") match {
        case Some("crash") => m.get("payload") match {
          case Some(payload: JValue) => payload \ "payload" \ "processType" match {
            case JString("main") | JNothing => true
            case _ => {
              contentCrashIgnoredAccumulator.add(1)
              false
            }
          }
          case _ => true
        }
        case _ => true
      }
    })

    val crashPairs = filtered_messages.flatMap((pingFields) => {
      getCrashPair(pingFields) match {
        case Some(crashPair) =>
          pingFields.get("docType") match {
            case Some("crash") => crashProcessedAccumulator.add(1)
            case Some("main") => mainProcessedAccumulator.add(1)
            case _ => null
          }
          List(crashPair)
        case None =>
          pingFields.get("docType") match {
            case Some("crash") => crashIgnoredAccumulator.add(1)
            case Some("main") => mainIgnoredAccumulator.add(1)
            case _ => null
          }
          List()
      }
    })

    // aggregate crash pairs by their keys
    val aggregates = crashPairs.reduceByKey(
      (crashStatsA: List[Double], crashStatsB: List[Double]) =>
        (crashStatsA, crashStatsB).zipped.map(_ + _)
    )

    val records = aggregates.map((aggregatedCrashPair: (List[Any], List[Double])) => {
      // extract and compute the record fields
      val (uniqueKey, stats) = aggregatedCrashPair
      val (activityDate, dimensions) = (uniqueKey.head.asInstanceOf[String], uniqueKey.tail.asInstanceOf[List[Option[String]]])
      val dimensionsMap: Map[String, String] = (dimensionNames, dimensions).zipped.flatMap((key, value) =>
        (key, value) match { // remove dimensions that don't have values
          case (k, Some(v)) => Some(k, v)
          case (k, None) => None
        }
      ).toMap
      val statsMap = (statsNames, stats).zipped.toMap

      Row(activityDate, dimensionsMap, statsMap)
    })

    (
      records,
      mainProcessedAccumulator,
      mainIgnoredAccumulator,
      crashProcessedAccumulator,
      crashIgnoredAccumulator,
      contentCrashIgnoredAccumulator
    )
  }

  // scalastyle:off return
  private def getCrashPair(pingFields: Map[String, Any]): Option[(List[java.io.Serializable], List[Double])] = {
    val submissionDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    val activityDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    val info = pingFields.get("payload.info") match {
      case Some(value: String) => parse(value)
      case _ => JObject()
    }
    val keyedHistograms = pingFields.get("payload.keyedHistograms") match {
      case Some(value: String) => parse(value)
      case _ => JObject()
    }

    // obtain the relevant stats for the ping
    val isMainPing = pingFields.get("docType") match {
      case Some("main") => true
      case Some("crash") => false
      case _ => return None
    }
    // obtain the activity date clamped to a reasonable time range
    val submissionDate = pingFields.get("submissionDate") match {
      case Some(date: String) =>
        try {
          LocalDate.parse(date, submissionDateFormatter)
        } catch {
          case _: Throwable => return None
        }
      case _ => return None
    }
    val activityDateRaw = if (isMainPing) {
      info \ "subsessionStartDate" match {
        case JString(date: String) =>
          try {
            LocalDate.parse(date.substring(0, 10), activityDateFormatter)
          } catch {
            case _: Throwable => return None
          }
        case _ => return None
      }
    } else {
      val payload = pingFields.get("payload") match {
        case Some(value: JValue) => value
        case _ => JNothing
      }

      payload \ "payload" \ "crashDate" match {
        case JString(date: String) =>
          try {
            LocalDate.parse(date, activityDateFormatter)
          } catch {
            case _: Throwable => return None
          }
        case _ => return None
      }
    }

    val activityDate = if (activityDateRaw.isBefore(submissionDate.minusDays(7))) { // clamp activity date to a good range
      submissionDate.minusDays(7)
    } else if (activityDateRaw.isAfter(submissionDate)) {
      submissionDate
    } else {
      activityDateRaw
    }
    val activityDateString = activityDate.format(activityDateFormatter) // format activity date as YYYY-MM-DD

    // obtain the unique key of the aggregate that this ping belongs to
    val uniqueKey = activityDateString :: (
      for ((path, index) <- comparableDimensions.zipWithIndex) yield {
        pingFields.get(path.head) match {
          case Some(topLevelField: String) =>
            if (path.tail == List.empty) { // list of length 1, interpret field as string rather than JSON
              Some(topLevelField)
            } else { // JSON field, the rest of the path tells us where to look in the JSON
              val is_gfx_compositor = dimensionNames(index).equals("gfx_compositor")
              val dimensionValue = path.tail.foldLeft(parse(topLevelField))((value, fieldName) => value \ fieldName) // retrieve the value at the given path
              dimensionValue match {
                case JString(value) if is_gfx_compositor && value == "none" => None
                case JString(value) => Some(value)
                case JBool(value) => Some(if (value) "True" else "False")
                case JInt(value) => Some(value.toString)
                case _ => None
              }
            }
          case _ => None
        }
      }
    )

    // validate build IDs
    val buildId = uniqueKey(dimensionNames.indexOf("build_id") + 1) // we add 1 because the first element is taken by activityDateString
    buildId match {
      case Some(value: String) if value.matches("\\d{14}") => null
      case _ => return None
    }

    val usageHours: Double = info \ "subsessionLength" match {
      case JInt(subsessionLength) if isMainPing => // main ping, which should always have the subsession length field
        Math.min(25, Math.max(0, subsessionLength.toDouble / 3600))
      case JNothing if !isMainPing => 0 // crash ping, which shouldn't have the subsession length field
      case _ => return None // invalid ping - main ping without subsession length or crash ping with subsession length
    }
    val mainCrashes = if (isMainPing) 0 else 1 // if this is a crash ping, it represents one main process crash
    val contentCrashes: Double = getCountHistogramValue(keyedHistograms \ "SUBPROCESS_CRASHES_WITH_DUMP" \ "content")
    val gpuCrashes: Double = getCountHistogramValue(keyedHistograms \ "SUBPROCESS_CRASHES_WITH_DUMP" \ "gpu")
    val pluginCrashes: Double = getCountHistogramValue(keyedHistograms \ "SUBPROCESS_CRASHES_WITH_DUMP" \ "plugin")
    val geckoMediaPluginCrashes: Double = getCountHistogramValue(keyedHistograms \ "SUBPROCESS_CRASHES_WITH_DUMP" \ "gmplugin")
    val contentShutdownCrashes: Double = getCountHistogramValue(keyedHistograms \ "SUBPROCESS_KILL_HARD" \ "ShutDownKill")
    val stats = List(
      if (isMainPing) 1 else 0, // number of pings represented by the aggregate
      usageHours, mainCrashes, contentCrashes,
      pluginCrashes, geckoMediaPluginCrashes, contentShutdownCrashes, gpuCrashes,

      // squared versions in order to compute stddev (with $$\sigma = \sqrt{\frac{\sum X^2}{N} - \mu^2}$$)
      usageHours * usageHours, mainCrashes * mainCrashes, contentCrashes * contentCrashes,
      pluginCrashes * pluginCrashes, geckoMediaPluginCrashes * geckoMediaPluginCrashes,
      contentShutdownCrashes * contentShutdownCrashes, gpuCrashes * gpuCrashes
    )

    // return a pair so we can use PairRDD operations on this data later
    Some((uniqueKey, stats))
  }
  // scalastyle:on return

  def buildSchema(): StructType = {
    StructType(
      StructField("activity_date", StringType, nullable = false) ::
      StructField("dimensions", MapType(StringType, StringType, valueContainsNull = true), nullable = false) ::
      StructField("stats", MapType(StringType, DoubleType, valueContainsNull = true), nullable = false) ::
      Nil
    )
  }
}

