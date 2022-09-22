package code.api.MxOF

import code.api.OBPRestHelper
import code.api.util.APIUtil.{OBPEndpoint, ResourceDoc, getAllowedEndpoints}
import code.api.util.ScannedApis
import code.util.Helper.MdcLoggable
import com.openbankproject.commons.util.ScannedApiVersion

import scala.collection.mutable.ArrayBuffer

/*
This file defines which endpoints from all the versions are available in v1
 */
object CNBV9_1_0_0 extends OBPRestHelper with MdcLoggable with ScannedApis {
  // CNBV9
  override val apiVersion = ScannedApiVersion("CNBV9", "CNBV9", "v1.0.0")
  val versionStatus = "DRAFT"

  private[this] val endpoints = APIMethods_AtmsApi.endpoints
  override val allResourceDocs: ArrayBuffer[ResourceDoc] = APIMethods_AtmsApi.resourceDocs.map(
    resourceDoc => resourceDoc.copy(implementedInApiVersion = apiVersion)
  )

  // Filter the possible endpoints by the disabled / enabled Props settings and add them together
  override val routes: List[OBPEndpoint] = getAllowedEndpoints(endpoints, allResourceDocs)

  // Make them available for use!
  routes.foreach(route => {
    registerRoutes(routes, allResourceDocs, apiPrefix)
  })

  logger.info(s"version $version has been run! There are ${routes.length} routes.")
}
