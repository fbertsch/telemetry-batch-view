/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package com.mozilla.telemetry.views.dau

object DesktopDauView extends GenericDauTrait {
  val jobName: String = "desktop_dau"

  def getGenericDauConf(args: Array[String]): GenericDauConf = {
    val conf = new BaseCliConf(args)
    conf.verify()
    GenericDauConf(
      conf.from.getOrElse(conf.to()),
      conf.to(),
      inputBasePath = s"${conf.bucketProto()}${conf.bucket()}/client_count_daily/v2",
      outputBasePath = s"${conf.bucketProto()}${conf.bucket()}/desktop_dau/v1"
    )
  }
}
