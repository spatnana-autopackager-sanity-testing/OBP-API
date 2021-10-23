package code.api.v4_0_0.dynamic

import code.api.JsonResponseException
import code.api.util.DynamicUtil.Sandbox
import code.api.util.{APIUtil, ErrorMessages, NewStyle}
import code.api.util.NewStyle.HttpCode
import code.api.v4_0_0.JSONFactory400
import code.api.v4_0_0.dynamic.practise.PractiseEndpoint
import com.openbankproject.commons.ExecutionContext
import com.openbankproject.commons.model.BankId
import com.openbankproject.commons.util.Functions.Memo
import com.openbankproject.commons.util.ReflectUtils

import code.api.util.APIUtil.{BooleanBody, DoubleBody, EmptyBody, LongBody, OBPEndpoint, PrimaryDataBody, ResourceDoc, StringBody, getDisabledEndpointOperationIds}
import code.api.util.{CallContext, DynamicUtil}
import code.api.v4_0_0.dynamic.practise.{DynamicEndpointCodeGenerator, PractiseEndpointGroup}

import net.liftweb.common.{Box, Failure, Full}
import net.liftweb.http.{JsonResponse, Req}
import net.liftweb.json.{JNothing, JValue}
import net.liftweb.json.JsonAST.{JBool, JDouble, JInt, JString}
import org.apache.commons.lang3.StringUtils

import java.lang.reflect.ReflectPermission
import java.net.{NetPermission, URLDecoder}
import java.security.Permission
import java.util.PropertyPermission
import scala.collection.immutable.List
import scala.util.control.Breaks.{break, breakable}
import code.api.util.ErrorMessages.DynamicResourceDocMethodDependency

object DynamicEndpoints {
  //TODO, better put all other dynamic endpoints into this list. eg: dynamicEntityEndpoints, dynamicSwaggerDocsEndpoints ....
  val disabledEndpointOperationIds = getDisabledEndpointOperationIds
  
  private val endpointGroups: List[EndpointGroup] =
    if(disabledEndpointOperationIds.contains("OBPv4.0.0-test-dynamic-resource-doc")) {
      DynamicResourceDocsEndpointGroup :: Nil
    }else{
      PractiseEndpointGroup :: DynamicResourceDocsEndpointGroup :: Nil
    }

  /**
   * this will find dynamic endpoint by request.
   * the dynamic endpoints can be in obp database or memory or generated by obp code.
   * This will be the OBP Router for all the dynamic endpoints.
   * 
   */
  private def findEndpoint(req: Req): Option[OBPEndpoint] = {
    var foundEndpoint: Option[OBPEndpoint] = None
    breakable{
      endpointGroups.foreach { endpointGroup => {
        val maybeEndpoint: Option[OBPEndpoint] = endpointGroup.endpoints.find(_.isDefinedAt(req))
        if(maybeEndpoint.isDefined) {
          foundEndpoint = maybeEndpoint
          break
        }
      }}
    }
    foundEndpoint
  }

  /**
   * This endpoint will be registered into Liftweb.
   * It is only one endpoint for Liftweb <---> but it mean many for obp dynamic endpoints
   * Because inside the method body, we override the `isDefinedAt` method,
   * We can loop all the dynamic endpoints from obp database (better check EndpointGroup.endpoints we generate the endpoints 
   * by resourceDocs, then we can create the endpoints object in memory).
   * 
   */
  val dynamicEndpoint: OBPEndpoint = new OBPEndpoint {
    override def isDefinedAt(req: Req): Boolean = findEndpoint(req).isDefined

    override def apply(req: Req): CallContext => Box[JsonResponse] = {
      val Some(endpoint) = findEndpoint(req)
      endpoint(req)
    }
  }

  def dynamicResourceDocs: List[ResourceDoc] = endpointGroups.flatMap(_.docs)
}

trait EndpointGroup {
  protected def resourceDocs: List[ResourceDoc]

  protected lazy val urlPrefix: String = ""

  // reset urlPrefix resourceDocs
  def docs: List[ResourceDoc] = if(StringUtils.isBlank(urlPrefix)) {
    resourceDocs
  } else {
    resourceDocs map { doc =>
      val newUrl = s"/$urlPrefix/${doc.requestUrl}".replace("//", "/")
      val newDoc = doc.copy(requestUrl = newUrl)
      newDoc.connectorMethods = doc.connectorMethods // copy method will not keep var value, So here reset it manually
      newDoc
    }
  }

  /**
   * this method will generate the endpoints from the resourceDocs.
   */
  def endpoints: List[OBPEndpoint] = docs.map(wrapEndpoint)

  //fill callContext with resourceDoc and operationId
  private def wrapEndpoint(resourceDoc: ResourceDoc): OBPEndpoint = {

    val endpointFunction = resourceDoc.wrappedWithAuthCheck(resourceDoc.partialFunction)

    new OBPEndpoint {
      override def isDefinedAt(req: Req): Boolean = req.requestType.method == resourceDoc.requestVerb && endpointFunction.isDefinedAt(req)

      override def apply(req: Req): CallContext => Box[JsonResponse] = {
        (callContext: CallContext) => {
          // fill callContext with resourceDoc and operationId, this will map the resourceDoc to endpoint.
          val newCallContext = callContext.copy(resourceDocument = Some(resourceDoc), operationId = Some(resourceDoc.operationId))
          endpointFunction(req)(newCallContext)
        }
      }
    }
  }
}

/**
 * This class will generate the ResourceDoc class fields(requestBody: Product, successResponse: Product and partialFunction: OBPEndpoint) 
 * by parameters: JValues and Strings.
 * successResponseBody: Option[JValue] --> toCaseObject(from JValue --> Scala code --> DynamicUtil.compileScalaCode --> generate the object.
 * methodBody: String --> prepare the template api level scala code --> DynamicUtil.compileScalaCode --> generate the api level code.
 * 
 * @param exampleRequestBody exampleRequestBody from the post json body, it is JValue here.
 * @param successResponseBody successResponseBody from the post json body,it is JValue here.
 * @param methodBody it is url-encoded string for the api level code.
 */
case class CompiledObjects(exampleRequestBody: Option[JValue], successResponseBody: Option[JValue], methodBody: String) {
  val decodedMethodBody = URLDecoder.decode(methodBody, "UTF-8")
  val requestBody: Product = exampleRequestBody match {
      //this case means, we accept the empty string "" from json post body, we need to map it to None.
    case Some(JString(s)) if StringUtils.isBlank(s) => toCaseObject(None)
     // Here we will generate the object by the JValue (exampleRequestBody)
    case _ => toCaseObject(exampleRequestBody)
  }
  val successResponse: Product = toCaseObject(successResponseBody)

  private val partialFunction: OBPEndpoint = {

    //If the requestBody is PrimaryDataBody, return None. otherwise, return the exampleRequestBody:Option[JValue]
    // In side OBP resourceDoc, requestBody and successResponse must be Product type，
    // both can not be the primitive type: `boolean， string， kong， int， long， double` and List. 
    // PrimaryDataBody is used for OBP mapping these types.
    // Note: List and object will generate the `Case class`, `case class` must not be PrimaryDataBody. only these two 
    // possibilities: case class or PrimaryDataBody
    val requestExample: Option[JValue] = if (requestBody.isInstanceOf[PrimaryDataBody[_]]) {
      None 
    } else exampleRequestBody

    val responseExample: Option[JValue] = if (successResponse.isInstanceOf[PrimaryDataBody[_]]) {
      None 
    } else successResponseBody

    //  buildCaseClasses --> will generate the following case classes string, which are used for the scala template code.
    // case class RequestRootJsonClass(name: String, age: Long)
    // case class ResponseRootJsonClass(person_id: String, name: String, age: Long)
    val (requestBodyCaseClasses, responseBodyCaseClasses) = DynamicEndpointCodeGenerator.buildCaseClasses(requestExample, responseExample)

    val code =
      s"""
         |import code.api.util.CallContext
         |import code.api.util.ErrorMessages.{InvalidJsonFormat, InvalidRequestPayload}
         |import code.api.util.NewStyle.HttpCode
         |import code.api.util.APIUtil.{OBPReturnType, futureToBoxedResponse, scalaFutureToLaFuture, errorJsonResponse}
         |
         |import net.liftweb.common.{Box, EmptyBox, Full}
         |import net.liftweb.http.{JsonResponse, Req}
         |import net.liftweb.json.MappingException
         |
         |import scala.concurrent.Future
         |import com.openbankproject.commons.ExecutionContext.Implicits.global
         |
         |implicit def scalaFutureToBoxedJsonResponse[T](scf: OBPReturnType[T])(implicit m: Manifest[T]): Box[JsonResponse] = {
         |    futureToBoxedResponse(scalaFutureToLaFuture(scf))
         |}
         |
         |implicit val formats = code.api.util.CustomJsonFormats.formats
         |
         |$requestBodyCaseClasses
         |
         |$responseBodyCaseClasses
         |
         |val endpoint: code.api.util.APIUtil.OBPEndpoint = {
         |  case request => { callContext =>
         |    val Some(pathParams) = callContext.resourceDocument.map(_.getPathParams(request.path.partPath))
         |    $decodedMethodBody
         |  }
         |}
         |
         |endpoint
         |
         |""".stripMargin
    val endpointMethod = DynamicUtil.compileScalaCode[OBPEndpoint](code)

    endpointMethod match {
      case Full(func) => func
      case Failure(msg: String, exception: Box[Throwable], _) =>
        throw exception.getOrElse(new RuntimeException(msg))
      case _ => throw new RuntimeException("compiled code return nothing")
    }
  }

  def validateDependency(): Unit = {
    val dependentMethods: List[(String, String, String)] = DynamicUtil.getDynamicCodeDependentMethods(partialFunction.getClass)
    CompiledObjects.validateDependency(dependentMethods);
  }

  def sandboxEndpoint(bankId: Option[String]) : OBPEndpoint = {
    val sandbox = CompiledObjects.sandbox(bankId.getOrElse("*"))

    new OBPEndpoint{
      override def isDefinedAt(req: Req): Boolean = partialFunction.isDefinedAt(req)

      // run dynamic code in sandbox
      override def apply(req: Req): CallContext => Box[JsonResponse] = {cc =>
        val fn = partialFunction.apply(req)

        sandbox.runInSandbox(fn(cc))
      }
    }
  }

  private def toCaseObject(jValue: Option[JValue]): Product = {
     if (jValue.isEmpty || jValue.exists(JNothing ==)) {
      EmptyBody
     } else {
       jValue.orNull match {
         case JBool(b) => BooleanBody(b)
         case JInt(l) => LongBody(l.toLong)
         case JDouble(d) => DoubleBody(d)
         case JString(s) => StringBody(s)
         case v => DynamicUtil.toCaseObject(v)
       }
     }
  }
}

object CompiledObjects {
  private val memoSandbox = new Memo[String, Sandbox]
  // all Permissions put at here
  private val permissions = List[Permission](
    new NetPermission("specifyStreamHandler"),
    new ReflectPermission("suppressAccessChecks"),
    new RuntimePermission("getenv.*"),
    new PropertyPermission("cglib.useCache", "read"),
    new PropertyPermission("net.sf.cglib.test.stressHashCodes", "read"),
    new PropertyPermission("cglib.debugLocation", "read"),
    new RuntimePermission("accessDeclaredMembers"),
    new RuntimePermission("getClassLoader"),
  )

  // all allowed methods put at here, typeName -> methods
  val allowedMethods: Map[String, Set[String]] = Map(
    // companion objects methods
    NewStyle.function.getClass.getTypeName -> "*",
    CompiledObjects.getClass.getTypeName -> "sandbox",
    HttpCode.getClass.getTypeName -> "200",
    DynamicCompileEndpoint.getClass.getTypeName -> "getPathParams, scalaFutureToBoxedJsonResponse",
    APIUtil.getClass.getTypeName -> "errorJsonResponse, errorJsonResponse$default$1, errorJsonResponse$default$2, errorJsonResponse$default$3, errorJsonResponse$default$4, scalaFutureToLaFuture, futureToBoxedResponse",
    ErrorMessages.getClass.getTypeName -> "*",
    ExecutionContext.Implicits.getClass.getTypeName -> "global",
    JSONFactory400.getClass.getTypeName -> "createBanksJson",

    // class methods
    classOf[Sandbox].getTypeName -> "runInSandbox",
    classOf[CallContext].getTypeName -> "*",
    classOf[ResourceDoc].getTypeName -> "getPathParams",
    "scala.reflect.runtime.package$" -> "universe",

    // allow any method of PractiseEndpoint for test
    PractiseEndpoint.getClass.getTypeName + "*" -> "*",

  ).mapValues(v => StringUtils.split(v, ',').map(_.trim).toSet)

  val restrictedTypes = Set(
    "scala.reflect.runtime.",
    "java.lang.reflect.",
    "scala.concurrent.ExecutionContext"
  )

  def isRestrictedType(typeName: String) = ReflectUtils.isObpClass(typeName) || restrictedTypes.exists(typeName.startsWith)

  def sandbox(bankId: String): Sandbox = memoSandbox.memoize(bankId){
    Sandbox.createSandbox(BankId.permission(bankId) :: permissions)
  }

  /**
  * validate dependencies, (className, methodName, signature)
   */
  def validateDependency(dependentMethods: List[(String, String, String)]) = {
    val notAllowedDependentMethods = dependentMethods collect {
      case (typeName, method, _)
        if isRestrictedType(typeName) &&
           !allowedMethods.get(typeName).exists(set => set.contains(method) || set.contains("*")) &&
           !allowedMethods.exists { it =>
             val (tpName, allowedMethods) = it
             tpName.endsWith("*") &&
               typeName.startsWith(StringUtils.substringBeforeLast(tpName, "*")) &&
               (allowedMethods.contains(method) || allowedMethods.contains("*"))
           }
        =>
        s"$typeName.$method"
    }
    // change to JsonResponseException
    if(notAllowedDependentMethods.nonEmpty) {
      val illegalDependency = notAllowedDependentMethods.mkString("[", ", ", "]")
      throw JsonResponseException(s"$DynamicResourceDocMethodDependency $illegalDependency", 400, "none")
    }
  }

}


